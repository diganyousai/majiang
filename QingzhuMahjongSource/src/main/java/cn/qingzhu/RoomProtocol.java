package cn.qingzhu;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Versioned, bounded frames. Explicit DTO encoding; never Java object deserialization. */
public final class RoomProtocol {
    public static final int VERSION=1, DEFAULT_PORT=17890, MAX_FRAME=262144;
    public enum Request { JOIN, READY, ACT, PING }
    public enum Reply { WELCOME, SNAPSHOT, ACK, PONG, ERROR }
    public enum Stage { LOBBY, PLAYING, PAUSED, SETTLED, CLOSED }
    public record Command(Request type,String id,String room,String password,String nickname,String token,
                          int round,long context,Game.Action action,boolean ready) {
        public static Command join(String id,String room,String password,String nick,String token) {
            return new Command(Request.JOIN,id,room,password,nick,token,0,0,null,false);
        }
        public static Command ready(String id,boolean ready) { return new Command(Request.READY,id,"","","","",0,0,null,ready); }
        public static Command act(String id,int round,long context,Game.Action action) { return new Command(Request.ACT,id,"","","","",round,context,action,false); }
        public static Command ping() { return new Command(Request.PING,"","","","","",0,0,null,false); }
    }
    public record Seat(String name,boolean occupied,boolean online,boolean ready) {}
    public record Snapshot(String room,int seat,long revision,long context,Stage stage,List<Seat> seats,String rules,Game.View game) {
        public Snapshot { seats=List.copyOf(seats); }
    }
    public record Message(Reply type,String requestId,String code,String text,String token,Snapshot snapshot) {}
    private RoomProtocol() {}
    private interface Writer { void write(DataOutputStream out)throws IOException; }
    private static void frame(OutputStream stream,Writer writer)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(VERSION); writer.write(out); out.flush(); byte[] payload=bytes.toByteArray();
        if(payload.length>MAX_FRAME) throw new IOException("Frame too large");
        DataOutputStream wire=new DataOutputStream(stream); wire.writeInt(payload.length); wire.write(payload); wire.flush();
    }
    private static DataInputStream frame(InputStream stream)throws IOException {
        DataInputStream wire=new DataInputStream(stream); int size=wire.readInt();
        if(size<4||size>MAX_FRAME) throw new IOException("Invalid frame size");
        byte[] payload=wire.readNBytes(size); if(payload.length!=size) throw new EOFException("Incomplete frame");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(payload));
        if(in.readInt()!=VERSION) throw new IOException("协议版本不一致，请使用相同版本的联机程序"); return in;
    }
    private static void text(DataOutputStream out,String value)throws IOException {
        byte[] bytes=Objects.requireNonNull(value).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>16384) throw new IOException("String too long"); out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in)throws IOException {
        int n=count(in,16384); byte[] bytes=in.readNBytes(n); if(bytes.length!=n) throw new EOFException();
        return new String(bytes,StandardCharsets.UTF_8);
    }
    private static int count(DataInputStream in,int max)throws IOException {
        int n=in.readInt(); if(n<0||n>max) throw new IOException("Invalid collection/string length"); return n;
    }
    private static <T> T enumeration(DataInputStream in,T[] values)throws IOException {
        int n=in.readInt(); if(n<0||n>=values.length) throw new IOException("Invalid enum"); return values[n];
    }
    private static void end(DataInputStream in)throws IOException { if(in.available()!=0) throw new IOException("Unexpected trailing bytes"); }
    private static void ints(DataOutputStream out,Collection<Integer> list)throws IOException { out.writeInt(list.size()); for(int n:list) out.writeInt(n); }
    private static List<Integer> ints(DataInputStream in,int max)throws IOException {
        int n=count(in,max); List<Integer> result=new ArrayList<>(n); for(int i=0;i<n;i++) result.add(in.readInt()); return List.copyOf(result);
    }
    private static void action(DataOutputStream out,Game.Action a)throws IOException {
        out.writeBoolean(a!=null); if(a!=null) { out.writeInt(a.kind().ordinal()); out.writeInt(a.tile()); ints(out,a.sequence()); }
    }
    private static Game.Action action(DataInputStream in)throws IOException {
        return in.readBoolean()?new Game.Action(enumeration(in,Game.Kind.values()),in.readInt(),ints(in,4)):null;
    }
    private static void melds(DataOutputStream out,List<Game.Meld> melds)throws IOException {
        out.writeInt(melds.size()); for(var m:melds) { out.writeInt(m.kind().ordinal()); ints(out,m.tiles()); out.writeInt(m.source()); }
    }
    private static List<Game.Meld> melds(DataInputStream in)throws IOException {
        int n=count(in,4); List<Game.Meld> result=new ArrayList<>();
        for(int i=0;i<n;i++) result.add(new Game.Meld(enumeration(in,Game.Kind.values()),ints(in,4),in.readInt())); return List.copyOf(result);
    }
    public static void write(OutputStream out,Command c)throws IOException {
        frame(out,d->{ d.writeInt(c.type().ordinal()); text(d,c.id()); text(d,c.room()); text(d,c.password());
            text(d,c.nickname()); text(d,c.token()); d.writeInt(c.round()); d.writeLong(c.context()); action(d,c.action()); d.writeBoolean(c.ready()); });
    }
    public static Command readCommand(InputStream stream)throws IOException {
        DataInputStream d=frame(stream);
        Command c=new Command(enumeration(d,Request.values()),text(d),text(d),text(d),text(d),text(d),d.readInt(),d.readLong(),action(d),d.readBoolean());
        end(d); return c;
    }
    public static void write(OutputStream out,Message m)throws IOException {
        frame(out,d->{ d.writeInt(m.type().ordinal()); text(d,m.requestId()); text(d,m.code()); text(d,m.text()); text(d,m.token());
            d.writeBoolean(m.snapshot()!=null); if(m.snapshot()!=null) snapshot(d,m.snapshot()); });
    }
    public static Message readMessage(InputStream stream)throws IOException {
        DataInputStream d=frame(stream);
        Message m=new Message(enumeration(d,Reply.values()),text(d),text(d),text(d),text(d),d.readBoolean()?snapshot(d):null); end(d); return m;
    }
    private static void snapshot(DataOutputStream d,Snapshot s)throws IOException {
        text(d,s.room()); d.writeInt(s.seat()); d.writeLong(s.revision()); d.writeLong(s.context()); d.writeInt(s.stage().ordinal());
        d.writeInt(s.seats().size()); for(Seat p:s.seats()) { text(d,p.name()); d.writeBoolean(p.occupied()); d.writeBoolean(p.online()); d.writeBoolean(p.ready()); }
        text(d,s.rules()); d.writeBoolean(s.game()!=null); if(s.game()!=null) view(d,s.game());
    }
    private static Snapshot snapshot(DataInputStream d)throws IOException {
        String room=text(d); int seat=d.readInt(); long revision=d.readLong(),context=d.readLong(); Stage stage=enumeration(d,Stage.values());
        int n=count(d,4); if(n!=4||seat<0||seat>3) throw new IOException("Invalid seat data"); List<Seat> seats=new ArrayList<>();
        for(int i=0;i<n;i++) seats.add(new Seat(text(d),d.readBoolean(),d.readBoolean(),d.readBoolean()));
        return new Snapshot(room,seat,revision,context,stage,seats,text(d),d.readBoolean()?view(d):null);
    }
    private static void view(DataOutputStream d,Game.View v)throws IOException {
        d.writeLong(v.revision()); d.writeInt(v.viewer()); d.writeInt(v.round()); d.writeInt(v.dealer()); d.writeInt(v.current());
        d.writeInt(v.phase().ordinal()); d.writeInt(v.remaining()); d.writeInt(v.players().size());
        for(var p:v.players()) { text(d,p.name()); ints(d,p.hand()); d.writeInt(p.handCount()); melds(d,p.melds()); ints(d,p.river());
            d.writeLong(p.score()); d.writeInt(p.drawn()); d.writeBoolean(p.ting()); }
        d.writeInt(v.actions().size()); for(var a:v.actions()) action(d,a);
        d.writeInt(v.events().size()); for(String e:v.events()) text(d,e);
        d.writeInt(v.lastSource()); d.writeInt(v.lastTile()); d.writeBoolean(v.result()!=null); if(v.result()!=null) result(d,v.result());
        ints(d,v.wildTiles()); text(d,v.ruleTitle());
    }
    private static Game.View view(DataInputStream d)throws IOException {
        long revision=d.readLong(); int viewer=d.readInt(),round=d.readInt(),dealer=d.readInt(),current=d.readInt();
        Game.Phase phase=enumeration(d,Game.Phase.values()); int remaining=d.readInt(); int n=count(d,4);
        if(n!=4||viewer<0||viewer>3||dealer<0||dealer>3||current<0||current>3) throw new IOException("Invalid game seats");
        List<Game.PlayerView> players=new ArrayList<>();
        for(int i=0;i<n;i++) players.add(new Game.PlayerView(text(d),ints(d,14),count(d,14),melds(d),ints(d,136),d.readLong(),d.readInt(),d.readBoolean()));
        n=count(d,256); List<Game.Action> actions=new ArrayList<>(); for(int i=0;i<n;i++) { Game.Action a=action(d); if(a==null) throw new IOException("Null action"); actions.add(a); }
        n=count(d,100); List<String> events=new ArrayList<>(); for(int i=0;i<n;i++) events.add(text(d));
        int lastSource=d.readInt(),lastTile=d.readInt(); Game.Result result=d.readBoolean()?result(d):null;
        return new Game.View(revision,viewer,round,dealer,current,phase,remaining,players,actions,events,lastSource,lastTile,result,Set.copyOf(ints(d,2)),text(d));
    }
    private static void result(DataOutputStream d,Game.Result r)throws IOException {
        d.writeInt(r.winner()); d.writeInt(r.source()); d.writeInt(r.tile()); text(d,r.method());
        for(long delta:r.deltas()) d.writeLong(delta); ints(d,r.winningHand()); melds(d,r.melds());
        d.writeInt(r.ledger().size()); for(var p:r.ledger()) { d.writeInt(p.from()); d.writeInt(p.to()); d.writeLong(p.amount()); text(d,p.reason()); }
        d.writeInt(r.patterns().size()); for(var p:r.patterns()) d.writeInt(p.ordinal());
    }
    private static Game.Result result(DataInputStream d)throws IOException {
        int winner=d.readInt(),source=d.readInt(),tile=d.readInt(); String method=text(d); List<Long> deltas=new ArrayList<>();
        for(int i=0;i<4;i++) deltas.add(d.readLong()); List<Integer> hand=ints(d,14); List<Game.Meld> melds=melds(d);
        int n=count(d,512); List<Scoring.Payment> ledger=new ArrayList<>();
        for(int i=0;i<n;i++) ledger.add(new Scoring.Payment(d.readInt(),d.readInt(),d.readLong(),text(d)));
        n=count(d,Rules.Pattern.values().length); Set<Rules.Pattern> patterns=EnumSet.noneOf(Rules.Pattern.class);
        for(int i=0;i<n;i++) patterns.add(enumeration(d,Rules.Pattern.values()));
        return new Game.Result(winner,source,tile,method,deltas,hand,melds,ledger,patterns);
    }
}
