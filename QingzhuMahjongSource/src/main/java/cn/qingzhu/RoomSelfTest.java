package cn.qingzhu;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static cn.qingzhu.RoomProtocol.*;

/** Uses real loopback sockets, including the exact transport shipped inside the EXE. */
public final class RoomSelfTest {
    private static int checks,games,multiResponses;
    private RoomSelfTest() {}
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); checks++; }
    private static void await(BooleanSupplier condition,String label)throws Exception {
        long until=System.nanoTime()+12_000_000_000L;
        while(!condition.getAsBoolean()) { if(System.nanoTime()>until) throw new AssertionError("Timeout: "+label); Thread.sleep(5); }
    }
    private static final class Peer implements AutoCloseable {
        final RoomClient client; final BlockingQueue<Message> messages=new LinkedBlockingQueue<>();
        private long nextCommand;
        Peer(RoomServer server,String nick,String token) {
            client=new RoomClient(new RoomClient.Address("127.0.0.1",server.port(),server.room(),"test-123456",nick,token));
            client.listen(messages::add,s->{}); client.start();
        }
        void send(Command command)throws InterruptedException {
            // Keep the synthetic players under the production 40-message/s cap.
            // Otherwise a fast machine tests rate-limit disconnects unintentionally,
            // and reconnect snapshots can race the next scripted action.
            long delay=nextCommand-System.nanoTime();
            if(delay>0) TimeUnit.NANOSECONDS.sleep(delay);
            client.send(command); nextCommand=System.nanoTime()+50_000_000L;
        }
        Message ack(String id)throws Exception {
            long until=System.nanoTime()+10_000_000_000L;
            while(System.nanoTime()<until) {
                Message m=messages.poll(100,TimeUnit.MILLISECONDS);
                if(m!=null&&m.type()==Reply.ACK&&m.requestId().equals(id)) return m;
            }
            throw new AssertionError("No ACK for "+id);
        }
        @Override public void close(){ client.close(); }
    }
    private static Message joinRaw(RoomServer server,String room,String password,String name,boolean fragmented)throws Exception {
        try(Socket socket=new Socket("127.0.0.1",server.port())) {
            socket.setSoTimeout(5000);
            Command command=Command.join(UUID.randomUUID().toString(),room,password,name,"");
            if(fragmented) {
                ByteArrayOutputStream data=new ByteArrayOutputStream(); RoomProtocol.write(data,command); byte[] bytes=data.toByteArray();
                for(int offset=0;offset<bytes.length;offset+=3) { socket.getOutputStream().write(bytes,offset,Math.min(3,bytes.length-offset)); socket.getOutputStream().flush(); }
            } else RoomProtocol.write(socket.getOutputStream(),command);
            return RoomProtocol.readMessage(socket.getInputStream());
        }
    }
    private static void malformed(RoomServer server,int length,int version)throws Exception {
        try(Socket socket=new Socket("127.0.0.1",server.port())) {
            socket.setSoTimeout(3000); DataOutputStream d=new DataOutputStream(socket.getOutputStream());
            d.writeInt(length); if(length==4) d.writeInt(version); d.flush();
            check(socket.getInputStream().read()==-1,"Malformed/version frame closes only that connection");
        }
    }
    private static Snapshot stable(Peer[] peers)throws Exception {
        await(()->{
            Snapshot first=peers[0].client.snapshot(); if(first==null) return false;
            for(Peer p:peers) if(p.client.snapshot()==null||p.client.snapshot().revision()!=first.revision()) return false;
            return true;
        },"all four snapshots synchronize");
        return peers[0].client.snapshot();
    }
    private static void privacy(Peer[] peers) {
        for(int seat=0;seat<4;seat++) {
            Snapshot s=peers[seat].client.snapshot(); if(s.game()==null) continue;
            check(s.seat()==seat&&s.game().viewer()==seat,"Authenticated viewer seat");
            if(s.game().phase()==Game.Phase.OVER) {
                for(Game.PlayerView player:s.game().players()) {
                    check(player.hand().size()==player.handCount(),"Ended-round hands cross wire in full");
                    check(player.river().stream().allMatch(t->t>=0)&&player.melds().stream().flatMap(m->m.tiles().stream()).allMatch(t->t>=0),"Ended-round discards and kongs are public");
                }
                continue;
            }
            for(int other=0;other<4;other++) if(other!=seat) {
                check(s.game().players().get(other).hand().isEmpty(),"No other private hand crosses wire");
                check(s.game().players().get(other).drawn()==-1,"No opponent drawn tile crosses wire");
            }
        }
    }
    private static void room(Rules rules,int rounds,boolean faults)throws Exception {
        Peer[] peers=new Peer[4];
        try(RoomServer server=new RoomServer("127.0.0.1",0,"654321","test-123456","房主",rules)) {
            if(faults) {
                check(joinRaw(server,server.room(),"incorrect","bad",false).code().equals("WRONG_PASSWORD"),"Wrong password rejected before seat allocation");
                check(joinRaw(server,"111111","test-123456","bad",false).code().equals("WRONG_ROOM"),"Wrong room rejected");
                malformed(server,MAX_FRAME+1,0); malformed(server,4,999);
            }
            for(int i=0;i<4;i++) {
                peers[i]=new Peer(server,i==0?"房主":"玩家"+i,i==0?server.hostToken():"");
                final int index=i; await(()->peers[index].client.connected()&&peers[index].client.snapshot()!=null,"seat "+i+" joins");
                check(peers[i].client.snapshot().seat()==i,"Four distinct seats, host reserved zero");
            }
            Snapshot lobby=stable(peers); check(lobby.stage()==Stage.LOBBY&&lobby.game()==null,"No cards before ready");
            check(joinRaw(server,server.room(),"test-123456","第五人",true).code().equals("ROOM_FULL"),"Fifth player rejected, fragmented frame supported");
            for(int i=0;i<3;i++) check(peers[i].ack(peers[i].client.ready(true)).code().equals("OK"),"First three ready");
            check(stable(peers).game()==null,"Three ready never starts game");
            peers[3].ack(peers[3].client.ready(true));
            await(()->peers[0].client.snapshot().game()!=null,"four ready starts"); stable(peers); privacy(peers);
            if(faults) {
                Snapshot initial=peers[0].client.snapshot();
                Command wrong=Command.act(UUID.randomUUID().toString(),initial.game().round(),initial.context(),new Game.Action(Game.Kind.DISCARD,peers[1].client.snapshot().game().self().hand().getFirst()));
                peers[1].client.send(wrong); check(peers[1].ack(wrong.id()).code().equals("INVALID_ACTION"),"Cannot play another seat's turn");
                Game.Action first=initial.game().actions().stream().filter(a->a.kind()==Game.Kind.DISCARD).findFirst().orElseThrow();
                Command duplicate=Command.act(UUID.randomUUID().toString(),initial.game().round(),initial.context(),first);
                peers[0].client.send(duplicate); peers[0].client.send(duplicate);
                check(peers[0].ack(duplicate.id()).code().equals("OK")&&peers[0].ack(duplicate.id()).code().equals("OK"),"Same request ACK repeated");
                await(()->peers[0].client.snapshot().game().revision()>initial.game().revision(),"first action applied");
                check(stable(peers).game().revision()==initial.game().revision()+1,"Duplicate action applied exactly once");
                peers[0].client.send(Command.act(duplicate.id(),initial.game().round(),initial.context(),Game.Action.pass()));
                check(peers[0].ack(duplicate.id()).code().equals("REQUEST_ID_CONFLICT"),"Same ID different action rejected");
                String token=peers[2].client.token(); peers[2].close();
                await(()->peers[0].client.snapshot().stage()==Stage.PAUSED,"disconnect pauses room");
                Snapshot paused=peers[0].client.snapshot(); int active=-1;
                for(int i=0;i<4;i++) if(i!=2&&!peers[i].client.snapshot().game().actions().isEmpty()) { active=i; break; }
                if(active>=0) {
                    Command blocked=Command.act(UUID.randomUUID().toString(),paused.game().round(),paused.context(),peers[active].client.snapshot().game().actions().getFirst());
                    peers[active].client.send(blocked); check(peers[active].ack(blocked.id()).code().equals("ROOM_PAUSED"),"Cannot move while peer offline");
                }
                peers[2]=new Peer(server,"恢复玩家",token);
                await(()->peers[2].client.connected()&&peers[0].client.snapshot().stage()==Stage.PLAYING,"resume token restores seat");
                Snapshot resumed=stable(peers); check(peers[2].client.snapshot().seat()==2,"Resume uses original seat");
                check(resumed.context()==paused.context()&&resumed.game().equals(paused.game()),"Reconnect leaves game and response context unchanged");
                int recoveredRound=resumed.game().round();
                for(int i=0;i<4;i++) check(peers[i].client.snapshot().game().round()==recoveredRound,"All peers keep round on reconnect");
                int acting=-1;
                for(int i=0;i<4;i++) if(!peers[i].client.snapshot().game().actions().isEmpty()) { acting=i; break; }
                check(acting>=0,"Can act after rejoin");
                Snapshot moving=peers[acting].client.snapshot();
                Command interrupted=Command.act(UUID.randomUUID().toString(),moving.game().round(),moving.context(),moving.game().actions().getFirst());
                peers[acting].client.send(interrupted); peers[acting].client.reconnect();
                check(peers[acting].ack(interrupted.id()).code().equals("OK"),"Automatic reconnect retries interrupted request");
                await(()->peers[0].client.snapshot().game().revision()>moving.game().revision(),"interrupted move commits");
                check(stable(peers).game().revision()==moving.game().revision()+1,"Reconnect retry changes game exactly once");
            }
            Random random=new Random(733);
            ArrayDeque<String> recent=new ArrayDeque<>();
            for(int round=0;round<rounds;round++) {
                for(int step=0;step<800;step++) {
                    Snapshot s=stable(peers); privacy(peers);
                    if(s.stage()==Stage.SETTLED) break;
                    int selected=-1,responders=0;
                    for(int i=0;i<4;i++) if(!peers[i].client.snapshot().game().actions().isEmpty()) { responders++; if(selected<0) selected=i; }
                    check(selected>=0,"Active game can progress");
                    if(responders>1) multiResponses++;
                    Snapshot mine=peers[selected].client.snapshot(); List<Game.Action> legal=mine.game().actions();
                    Game.Action action=legal.stream().filter(a->a.kind()==Game.Kind.WIN||a.kind()==Game.Kind.TING).findFirst().orElse(legal.get(random.nextInt(legal.size())));
                    Command command=Command.act(UUID.randomUUID().toString(),mine.game().round(),mine.context(),action);
                    recent.add("seat="+selected+", rev="+mine.revision()+", context="+mine.context()+", phase="+mine.game().phase()+", action="+action);
                    if(recent.size()>10) recent.removeFirst();
                    peers[selected].send(command); Message reply=peers[selected].ack(command.id());
                    check(reply.code().equals("OK"),"Wire action accepted: "+reply.code()+" / "+reply.text()+"; seat="+selected+", round="+round+", step="+step+"; recent="+recent+"; snapshots="+Arrays.stream(peers).map(p->p.client.snapshot().revision()+"/"+p.client.snapshot().context()+"/"+p.client.snapshot().stage()).toList());
                    await(()->peers[0].client.snapshot().revision()>s.revision(),"snapshot after move"); stable(peers);
                }
                Snapshot finalState=stable(peers); check(finalState.stage()==Stage.SETTLED,"Four-player round ends");
                Game.Result result=finalState.game().result(); check(result.deltas().stream().mapToLong(Long::longValue).sum()==0,"Network settlement zero sum");
                check(Scoring.deltas(result.ledger()).equals(result.deltas()),"Network ledger reconciles");
                for(Peer peer:peers) {
                    check(peer.client.snapshot().game().result().equals(result),"Same public result for all four");
                    check(peer.client.snapshot().game().players().equals(finalState.game().players()),"All four receive the same complete end-round cards");
                }
                privacy(peers); games++;
                if(round+1<rounds) {
                    int next=finalState.game().round()+1;
                    for(Peer peer:peers) check(peer.ack(peer.client.ready(true)).code().equals("OK"),"Ready for next round");
                    await(()->peers[0].client.snapshot().game().round()==next,"next round starts once"); stable(peers);
                    check(peers[0].client.snapshot().game().dealer()==(result.winner()<0?finalState.game().dealer():result.winner()),"Winning seat is next dealer");
                    privacy(peers);
                }
            }
        } finally { for(Peer peer:peers) if(peer!=null) peer.close(); }
    }
    private static Game responseFixture(Rules rules) {
        Game game=new Game(77,rules,List.of("房主","玩家1","玩家2","玩家3"));
        Map<Integer,List<Integer>> hands=Map.of(0,List.of(28),
            1,List.of(0,1,2,9,10,11,18,19,20,27,27,27,28),
            2,List.of(3,4,5,12,13,14,21,22,23,30,30,30,28));
        List<Integer> pool=new ArrayList<>(); for(int t=0;t<34;t++) for(int n=0;n<4;n++) pool.add(t);
        for(var hand:hands.values()) for(int t:hand) { if(!pool.remove(Integer.valueOf(t))) throw new AssertionError("Invalid response fixture"); }
        Collections.shuffle(pool,new Random(37));
        for(int seat=0;seat<4;seat++) {
            Game.Player p=game.players[seat]; p.hand.clear(); p.hand.addAll(hands.getOrDefault(seat,List.of()));
            while(p.hand.size()<(seat==0?14:13)) p.hand.add(pool.removeLast()); p.drawn=seat==0?p.hand.getLast():-1;
        }
        game.wall.clear(); game.wall.addAll(pool); game.current=0; game.invalidate(); game.checkInvariants(); return game;
    }
    private static void simultaneous()throws Exception {
        Rules rules=new Rules(Rules.Mode.FRIENDS,false,6,Rules.GangPolicy.EVERYONE,false,Rules.Liability.NORMAL,
            Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,false,false,0,Rules.Hidden.DECLARATION);
        Peer[] peers=new Peer[4];
        try(RoomServer server=new RoomServer("127.0.0.1",0,"654321","test-123456","房主",rules,()->responseFixture(rules))) {
            for(int i=0;i<4;i++) { peers[i]=new Peer(server,i==0?"房主":"玩家"+i,i==0?server.hostToken():""); final int seat=i; await(()->peers[seat].client.snapshot()!=null,"response fixture joins"); }
            for(Peer peer:peers) peer.ack(peer.client.ready(true)); await(()->peers[0].client.snapshot().game()!=null,"fixture begins"); stable(peers);
            peers[0].ack(peers[0].client.act(new Game.Action(Game.Kind.DISCARD,28)));
            await(()->peers[1].client.snapshot().game().phase()==Game.Phase.REACTION,"two hu offers"); stable(peers);
            Snapshot original=peers[1].client.snapshot();
            check(original.game().actions().contains(new Game.Action(Game.Kind.WIN,28))&&peers[2].client.snapshot().game().actions().contains(new Game.Action(Game.Kind.WIN,28)),"Both winners receive private offers");
            Command far=Command.act(UUID.randomUUID().toString(),original.game().round(),original.context(),new Game.Action(Game.Kind.WIN,28));
            Command near=Command.act(UUID.randomUUID().toString(),original.game().round(),original.context(),new Game.Action(Game.Kind.WIN,28));
            peers[2].client.send(far); check(peers[2].ack(far.id()).code().equals("OK"),"Far responder first accepted");
            await(()->peers[1].client.snapshot().revision()>original.revision(),"revision changes inside same response window");
            check(peers[1].client.snapshot().context()==original.context(),"Response context stable when another response arrives");
            peers[1].client.send(near); check(peers[1].ack(near.id()).code().equals("OK"),"Old revision but same response context accepted");
            // Any incidental pong candidate must also explicitly pass, just as a real fourth player would.
            await(()->peers[0].client.snapshot().revision()>original.revision()+1,"second response arrives"); stable(peers);
            for(Peer peer:peers) if(peer.client.snapshot().game().actions().contains(Game.Action.pass())) peer.ack(peer.client.act(Game.Action.pass()));
            await(()->peers[0].client.snapshot().stage()==Stage.SETTLED,"resolve simultaneous hu");
            check(stable(peers).game().result().winner()==1,"Nearest winner, independent of network arrival order"); multiResponses++; games++;
        } finally { for(Peer peer:peers) if(peer!=null) peer.close(); }
    }
    private static void hostIdentity()throws Exception {
        try(RoomServer server=new RoomServer("127.0.0.1",0,"654321","test-123456","房主",Rules.friends());
            Socket socket=new Socket("127.0.0.1",server.port())) {
            socket.setSoTimeout(3000);
            RoomProtocol.write(socket.getOutputStream(),Command.join("host",server.room(),"test-123456","房主",""));
            Message reply=RoomProtocol.readMessage(socket.getInputStream());
            check(reply.type()==Reply.WELCOME&&reply.snapshot().seat()!=0,"Known host label and nickname cannot claim creator seat");
        }
    }
    public static void run(Path output)throws Exception {
        checks=games=multiResponses=0; long start=System.nanoTime(); Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("network-report.json"));
        try {
            hostIdentity(); room(Rules.friends(),4,true); room(Rules.coins(),2,false); simultaneous();
            String result="PASS network: "+checks+" checks; "+games+" full four-client rounds; "+multiResponses+" multi-responder windows; "+String.format(Locale.ROOT,"%.2f",(System.nanoTime()-start)/1e9)+" seconds";
            Files.writeString(output.resolve("network-results.txt"),result+"\n");
            Files.writeString(output.resolve("network-report.json"),"{\"status\":\"passed\",\"checks\":"+checks+",\"rounds\":"+games+",\"multiResponderWindows\":"+multiResponses+"}\n");
            Files.deleteIfExists(output.resolve("failure.txt")); System.out.println(result);
        } catch(Throwable error) { Files.writeString(output.resolve("failure.txt"),error.toString()); throw error; }
    }
}
