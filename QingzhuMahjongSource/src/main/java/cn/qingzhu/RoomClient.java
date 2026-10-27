package cn.qingzhu;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static cn.qingzhu.RoomProtocol.*;

/** Transport and reconnect independent of Swing. Retried commands retain their request IDs. */
public final class RoomClient implements AutoCloseable {
    public record Address(String host,int port,String room,String password,String nickname,String token) {}
    private final Address address;
    private final String joinId=UUID.randomUUID().toString();
    private final ConcurrentMap<String,Command> pending=new ConcurrentHashMap<>();
    private final ExecutorService writer=Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("client-writer").factory());
    private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("client-heartbeat").factory());
    private final AtomicBoolean closed=new AtomicBoolean(),started=new AtomicBoolean();
    private volatile Socket socket;
    private volatile OutputStream output;
    private volatile String token;
    private volatile Snapshot snapshot;
    private volatile boolean connected;
    private volatile Consumer<Message> listener=m->{};
    private volatile Consumer<String> status=s->{};
    public RoomClient(Address address) { this.address=address; token=address.token()==null?"":address.token(); }
    public void listen(Consumer<Message> listener,Consumer<String> status) { this.listener=listener; this.status=status; }
    public Snapshot snapshot() { return snapshot; }
    public String token() { return token; }
    public boolean connected() { return connected; }
    public boolean isClosed() { return closed.get(); }
    public int pendingCount() { return pending.size(); }
    public void start() {
        if(!started.compareAndSet(false,true)) return;
        heartbeat.scheduleAtFixedRate(()->writeAsync(Command.ping()),4,4,TimeUnit.SECONDS);
        Thread.startVirtualThread(this::connectLoop);
    }
    private void connectLoop() {
        int attempt=0;
        while(!closed.get()) {
            Socket link=new Socket();
            try {
                status.accept(attempt==0?"连接主机中…":"连接中断，正在重连…");
                link.connect(new InetSocketAddress(address.host(),address.port()),4000); link.setTcpNoDelay(true); link.setSoTimeout(15000); socket=link;
                RoomProtocol.write(link.getOutputStream(),Command.join(joinId,address.room(),address.password(),address.nickname(),token));
                Message welcome=RoomProtocol.readMessage(link.getInputStream());
                if(welcome.type()!=Reply.WELCOME) { receive(welcome); if(!closed.get()) { status.accept(welcome.text()); close(); } break; }
                token=welcome.token(); output=link.getOutputStream(); connected=true; attempt=0; receive(welcome); status.accept("已连接");
                for(Command command:pending.values()) writeAsync(command);
                while(!closed.get()) receive(RoomProtocol.readMessage(link.getInputStream()));
            } catch(IOException|RuntimeException e) {
                if(!closed.get()) status.accept("连接中断，自动重连："+(e instanceof SocketTimeoutException?"等待主机响应超时":e.getMessage()));
            } finally {
                connected=false; output=null; try { link.close(); } catch(IOException ignored) { }
            }
            if(!closed.get()) try { Thread.sleep(Math.min(4000,500L<<Math.min(attempt++,3))); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
    private void receive(Message m) {
        if(m.type()==Reply.ACK) pending.remove(m.requestId());
        if(m.snapshot()!=null) {
            Snapshot current=snapshot;
            if(current==null||m.snapshot().revision()>=current.revision()) snapshot=m.snapshot(); else return;
        }
        listener.accept(m);
        if(m.type()==Reply.ERROR) { status.accept(m.text()); close(); }
    }
    public String ready(boolean ready) { Command c=Command.ready(UUID.randomUUID().toString(),ready); send(c); return c.id(); }
    public String act(Game.Action action) {
        Snapshot s=snapshot; if(s==null||s.game()==null) throw new IllegalStateException("尚未开始对局");
        Command c=Command.act(UUID.randomUUID().toString(),s.game().round(),s.context(),action); send(c); return c.id();
    }
    public void send(Command command) {
        if(closed.get()) throw new IllegalStateException("连接已关闭");
        if(pending.size()>=32&&!pending.containsKey(command.id())) throw new IllegalStateException("待确认操作过多，请等待连接恢复");
        pending.putIfAbsent(command.id(),command); writeAsync(command);
    }
    private void writeAsync(Command command) {
        if(closed.get()) return;
        try { writer.execute(()->{
            OutputStream out=output; if(out==null) return;
            try { RoomProtocol.write(out,command); } catch(IOException e) { reconnect(); }
        }); } catch(RejectedExecutionException ignored) { }
    }
    /** Explicit retry used by the client UI; keeps token and pending request IDs. */
    public void reconnect() { Socket link=socket; if(link!=null) try { link.close(); } catch(IOException ignored) { } }
    @Override public void close() {
        if(closed.compareAndSet(false,true)) { connected=false; reconnect(); heartbeat.shutdownNow(); writer.shutdownNow(); }
    }
}
