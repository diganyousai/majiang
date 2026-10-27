package cn.qingzhu;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import static cn.qingzhu.RoomProtocol.*;

/** One authoritative four-player room. Every room mutation runs on a single actor. */
public final class RoomServer implements AutoCloseable {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final ServerSocket listener;
    private final Rules rules;
    private final Supplier<Game> initialGame;
    private final String room,password;
    private final Session[] seats=new Session[4];
    private final ThreadPoolExecutor actor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),Thread.ofPlatform().daemon().name("room-actor").factory());
    private final ScheduledExecutorService clock=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("room-heartbeat").factory());
    private final Set<Connection> connections=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean();
    private volatile String summary="等待玩家加入";
    private Game game;
    private long revision,context;
    private boolean broken;
    private record Cached(Command command,Message result) {}
    private static final class Session {
        final int seat; final String name,token; String joinId;
        Connection link; boolean ready;
        final Map<String,Cached> processed=new LinkedHashMap<>() {
            @Override protected boolean removeEldestEntry(Map.Entry<String,Cached> e) { return size()>4096; }
        };
        Session(int seat,String name,String token,String joinId) { this.seat=seat; this.name=name; this.token=token; this.joinId=joinId; }
    }
    public RoomServer(String bind,int port,String room,String password,String hostName,Rules rules)throws IOException {
        this(bind,port,room,password,hostName,rules,null);
    }
    // Dependency injection for isolated integration fixtures; there is no network command to replace a game.
    RoomServer(String bind,int port,String room,String password,String hostName,Rules rules,Supplier<Game> initialGame)throws IOException {
        if(!room.matches("[A-Za-z0-9]{4,12}")||password.length()<6||password.length()>64) throw new IllegalArgumentException("房间号需4～12位字母数字，口令需6～64位");
        validateName(hostName); this.room=room; this.password=password; this.rules=Objects.requireNonNull(rules); this.initialGame=initialGame;
        seats[0]=new Session(0,hostName,secret(),"");
        listener=new ServerSocket(); listener.setReuseAddress(true);
        try { listener.bind(new InetSocketAddress(bind,port)); } catch(IOException e) { listener.close(); throw e; }
        Thread.startVirtualThread(this::accept);
        clock.scheduleAtFixedRate(()->enqueue(this::maintain),2,2,TimeUnit.SECONDS);
    }
    public static String secret() { byte[] bytes=new byte[24]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public static String newCode() { return String.valueOf(100000+RANDOM.nextInt(900000)); }
    public String room() { return room; }
    public int port() { return listener.getLocalPort(); }
    public String hostToken() { return seats[0].token; }
    public String summary() { return summary; }
    public boolean isClosed() { return closed.get(); }
    private static void validateName(String name) {
        if(name==null||name.isBlank()||name.length()>16||name.codePoints().anyMatch(c->Character.isISOControl(c)||c=='<'||c=='>'))
            throw new IllegalArgumentException("昵称需1～16个字符，不能含控制符或尖括号");
    }
    private void accept() {
        while(!closed.get()) try {
            Socket socket=listener.accept(); socket.setTcpNoDelay(true); socket.setSoTimeout(15000);
            if(connections.size()>=16) { socket.close(); continue; }
            Connection c=new Connection(socket); connections.add(c); c.start();
        } catch(IOException e) { if(!closed.get()) System.err.println("Room accept error: "+e.getMessage()); }
    }
    private void enqueue(Runnable task) {
        if(closed.get()) return;
        try { actor.execute(task); } catch(RejectedExecutionException ignored) { }
    }
    private final class Connection {
        final Socket socket; final ArrayBlockingQueue<Message> outgoing=new ArrayBlockingQueue<>(32);
        final AtomicBoolean ended=new AtomicBoolean();
        volatile boolean finishAfterWrite; volatile long lastSeen=System.nanoTime();
        Session session; int received; long rateStart=System.nanoTime();
        Connection(Socket socket) { this.socket=socket; }
        void start() {
            Thread.startVirtualThread(()->{
                try {
                    while(!ended.get()) {
                        Message m=outgoing.poll(2,TimeUnit.SECONDS); if(m==null) continue;
                        RoomProtocol.write(socket.getOutputStream(),m);
                        if(finishAfterWrite&&m.type()==Reply.ERROR) break;
                    }
                } catch(IOException|InterruptedException ignored) { } finally { stop(); }
            });
            Thread.startVirtualThread(()->{
                try {
                    while(!ended.get()&&!finishAfterWrite) {
                        Command c=RoomProtocol.readCommand(socket.getInputStream()); lastSeen=System.nanoTime();
                        if(lastSeen-rateStart>1_000_000_000L) { rateStart=lastSeen; received=0; }
                        if(++received>40) { stop(); break; }
                        try { actor.execute(()->handle(this,c)); } catch(RejectedExecutionException e) { stop(); }
                    }
                } catch(IOException|RuntimeException ignored) { } finally { if(!finishAfterWrite) stop(); }
            });
        }
        void send(Message m) { if(!ended.get()&&!outgoing.offer(m)) stop(); }
        void rejectAndClose(String code,String text) {
            finishAfterWrite=true; outgoing.clear(); send(new Message(Reply.ERROR,"",code,text,"",null));
        }
        void stop() {
            if(ended.compareAndSet(false,true)) {
                try { socket.close(); } catch(IOException ignored) { }
                connections.remove(this); enqueue(()->disconnected(this));
            }
        }
    }
    private void handle(Connection c,Command command) {
        if(c.ended.get()||c.finishAfterWrite||closed.get()) return;
        try {
            if(command.type()==Request.JOIN) { join(c,command); return; }
            if(c.session==null||c.session.link!=c) { c.rejectAndClose("NOT_JOINED","请先加入房间"); return; }
            if(command.type()==Request.PING) { c.send(new Message(Reply.PONG,"","","","",null)); return; }
            Session s=c.session;
            if(command.id().isBlank()||command.id().length()>80) { error(c,command,"BAD_REQUEST","请求编号无效"); return; }
            Cached previous=s.processed.get(command.id());
            if(previous!=null) {
                if(!previous.command().equals(command)) error(c,command,"REQUEST_ID_CONFLICT","同一请求编号对应不同操作");
                else { c.send(previous.result()); sendSnapshot(s); } return;
            }
            String code="OK",text="";
            if(command.type()==Request.READY) {
                if(game!=null&&game.phase!=Game.Phase.OVER) { code="GAME_ACTIVE"; text="本局尚未结束"; }
                else {
                    s.ready=command.ready();
                    if(allOnline()&&Arrays.stream(seats).allMatch(p->p.ready)) {
                        if(game==null) game=initialGame==null?new Game(RANDOM.nextLong(),rules,Arrays.stream(seats).map(p->p.name).toList()):initialGame.get();
                        else game.newRound(false);
                        for(Session p:seats) p.ready=false; context++;
                    }
                    revision++;
                }
            } else if(command.type()==Request.ACT) {
                if(broken) { code="ROOM_CLOSED"; text="房间发生错误，请主机重新建房"; }
                else if(game==null||game.phase==Game.Phase.OVER) { code="NOT_PLAYING"; text="当前不在行牌阶段"; }
                else if(!allOnline()) { code="ROOM_PAUSED"; text="有人离线，等待重连后继续"; }
                else if(command.round()!=game.round||command.context()!=context) { code="STALE_CONTEXT"; text="操作窗口已变化，请按最新牌桌操作"; }
                else if(command.action()==null||!game.legalActions(s.seat).contains(command.action())) { code="INVALID_ACTION"; text="当前不能执行这个操作"; }
                else {
                    Game.Phase before=game.phase; game.act(s.seat,command.action()); game.checkInvariants();
                    // Multiple responders share one context even though game revision changes on each reply.
                    if(before==Game.Phase.DISCARD||game.phase!=before||game.responses.isEmpty()) context++;
                    revision++;
                }
            } else { code="BAD_REQUEST"; text="不支持的请求"; }
            Message reply=new Message(Reply.ACK,command.id(),code,text,"",null);
            s.processed.put(command.id(),new Cached(command,reply)); c.send(reply); broadcast();
        } catch(IllegalArgumentException e) { error(c,command,"BAD_REQUEST",e.getMessage()); }
        catch(RuntimeException e) { broken=true; revision++; e.printStackTrace(); error(c,command,"SERVER_ERROR","服务器处理失败，房间已停止行牌"); broadcast(); }
    }
    private void join(Connection c,Command command) {
        if(c.session!=null) { error(c,command,"ALREADY_JOINED","连接已经加入房间"); return; }
        if(!room.equals(command.room())) { c.rejectAndClose("WRONG_ROOM","房间号不正确"); return; }
        if(!MessageDigest.isEqual(password.getBytes(java.nio.charset.StandardCharsets.UTF_8),command.password().getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            c.rejectAndClose("WRONG_PASSWORD","房间口令不正确"); return;
        }
        validateName(command.nickname());
        if(command.id().length()>80||command.id().isBlank()) { c.rejectAndClose("BAD_REQUEST","加入请求编号无效"); return; }
        Session selected=null;
        if(!command.token().isEmpty()) {
            for(Session s:seats) if(s!=null&&s.token.equals(command.token())) selected=s;
            if(selected==null) { c.rejectAndClose("INVALID_TOKEN","房间身份已失效，请重新加入当前房间"); return; }
            selected.joinId=command.id();
        } else {
            for(Session s:seats) if(s!=null&&!s.joinId.isEmpty()&&s.joinId.equals(command.id())) selected=s;
            if(selected==null) for(int i=1;i<4;i++) if(seats[i]==null) { selected=new Session(i,command.nickname(),secret(),command.id()); seats[i]=selected; break; }
            if(selected==null&&game==null) for(int i=1;i<4;i++) if(seats[i].link==null||seats[i].link.ended.get()) {
                selected=new Session(i,command.nickname(),secret(),command.id()); seats[i]=selected; break;
            }
            if(selected==null) { c.rejectAndClose("ROOM_FULL","四个座位已占用；原玩家请在原电脑使用相同昵称重新加入"); return; }
        }
        if(selected.link!=null&&selected.link!=c) selected.link.rejectAndClose("REPLACED","该玩家已从另一窗口重新加入，本窗口停止重连");
        selected.link=c; c.session=selected; revision++;
        c.send(new Message(Reply.WELCOME,command.id(),"OK","",selected.token,snapshot(selected))); broadcast();
    }
    private void error(Connection c,Command command,String code,String text) { c.send(new Message(Reply.ACK,command.id(),code,text,"",null)); }
    private boolean allOnline() { return Arrays.stream(seats).allMatch(s->s!=null&&s.link!=null&&!s.link.ended.get()); }
    private Stage stage() {
        if(closed.get()||broken) return Stage.CLOSED;
        if(game==null) return Stage.LOBBY;
        if(game.phase==Game.Phase.OVER) return Stage.SETTLED;
        return allOnline()?Stage.PLAYING:Stage.PAUSED;
    }
    private Snapshot snapshot(Session self) {
        List<Seat> players=new ArrayList<>();
        for(Session s:seats) {
            boolean online=s!=null&&s.link!=null&&!s.link.ended.get();
            boolean available=s==null||game==null&&s.seat!=0&&!online;
            players.add(available?new Seat("空座位",false,false,false):new Seat(s.name,true,online,s.ready));
        }
        return new Snapshot(room,self.seat,revision,context,stage(),players,rules.description(),game==null?null:game.view(self.seat));
    }
    private void sendSnapshot(Session s) { if(s.link!=null) s.link.send(new Message(Reply.SNAPSHOT,"","","","",snapshot(s))); }
    private void broadcast() {
        for(Session s:seats) if(s!=null) sendSnapshot(s);
        long online=Arrays.stream(seats).filter(s->s!=null&&s.link!=null&&!s.link.ended.get()).count();
        summary="在线 "+online+"/4 · "+switch(stage()) { case LOBBY->"等待四人准备"; case PLAYING->"对局中"; case PAUSED->"有人离线，已暂停"; case SETTLED->"等待下一局准备"; case CLOSED->"已关闭"; };
    }
    private void disconnected(Connection c) {
        if(c.session!=null&&c.session.link==c) {
            c.session.link=null;
            if(game==null||game.phase==Game.Phase.OVER) c.session.ready=false;
            revision++; broadcast();
        }
    }
    private void maintain() {
        long now=System.nanoTime();
        for(Connection c:connections) if(now-c.lastSeen>15_000_000_000L || c.session==null&&now-c.rateStart>10_000_000_000L) c.stop();
        for(Session s:seats) if(s!=null&&s.link!=null&&s.link.ended.get()) disconnected(s.link);
    }
    @Override public void close() {
        if(closed.compareAndSet(false,true)) {
            try { listener.close(); } catch(IOException ignored) { }
            for(Connection c:connections) c.stop(); clock.shutdownNow(); actor.shutdownNow(); summary="房间已关闭";
        }
    }
}
