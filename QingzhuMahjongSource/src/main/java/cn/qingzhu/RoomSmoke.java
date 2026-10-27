package cn.qingzhu;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Four actual Swing clients talking over sockets, including seats 1/2/3 and resume. */
public final class RoomSmoke {
    private static final List<String> checks=new ArrayList<>();
    private RoomSmoke() {}
    private static void edt(Runnable action)throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void check(boolean condition,String label) { if(!condition) throw new AssertionError(label); checks.add(label); }
    private static void await(BooleanSupplier condition,String label,long seconds)throws Exception {
        long until=System.nanoTime()+seconds*1_000_000_000L;
        while(!condition.getAsBoolean()) { if(System.nanoTime()>until) throw new AssertionError("Timeout: "+label); Thread.sleep(25); }
    }
    private static void capture(Component component,Path path) {
        try {
            BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics(); component.printAll(g); g.dispose(); ImageIO.write(image,"png",path.toFile());
        } catch(Exception e) { throw new RuntimeException(e); }
    }
    public static void run(Path folder)throws Exception {
        run(folder,true);
    }
    public static void run(Path folder,boolean nativeMouse)throws Exception {
        Files.createDirectories(folder); checks.clear(); MultiplayerWindow[] windows=new MultiplayerWindow[4];
        Files.deleteIfExists(folder.resolve("online-smoke-report.json"));
        try(RoomServer server=new RoomServer("127.0.0.1",0,"765432","smoke-123456","房主",Rules.friends())) {
            try {
                for(int i=0;i<4;i++) {
                    final int seat=i;
                    edt(()->{
                        windows[seat]=new MultiplayerWindow(new RoomClient.Address("127.0.0.1",server.port(),server.room(),"smoke-123456",seat==0?"房主":"玩家"+seat,seat==0?server.hostToken():""));
                        Rectangle available=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
                        windows[seat].setSize(Math.min(1060,available.width),Math.min(760,available.height));
                        windows[seat].setLocation(available.x,available.y); windows[seat].setVisible(true); windows[seat].connect();
                    });
                    await(()->windows[seat].client.snapshot()!=null,"join "+seat,10);
                    check(windows[seat].client.snapshot().seat()==seat,"unique viewer seat "+seat);
                }
                edt(()->capture(windows[0].getContentPane(),folder.resolve("online-lobby.png")));
                for(int i=0;i<3;i++) { final int seat=i; edt(()->windows[seat].ready.doClick()); }
                await(()->windows[0].client.snapshot().seats().stream().filter(RoomProtocol.Seat::ready).count()==3,"three ready",10);
                check(windows[0].client.snapshot().game()==null,"three players cannot start");
                edt(()->windows[3].ready.doClick());
                await(()->java.util.Arrays.stream(windows).allMatch(w->w.client.snapshot().game()!=null),"game starts",10);
                edt(()->{
                    for(int i=0;i<4;i++) {
                        MultiplayerWindow w=windows[i]; capture(w.getContentPane(),folder.resolve("online-seat-"+(i+1)+".png"));
                        check(w.board.view.viewer()==i,"render correct seat "+i);
                        check(w.board.hitBoxCount()==w.board.view.self().hand().size(),"own hand clickable for seat "+i);
                        for(int other=0;other<4;other++) if(other!=i) check(w.board.view.players().get(other).hand().isEmpty(),"no remote hand in GUI "+i+"/"+other);
                    }
                });
                Robot robot=nativeMouse?new Robot():null;
                if(robot!=null) robot.setAutoDelay(120);
                String input=nativeMouse?"real mouse":"Swing event dispatch";
                AtomicReference<Point> click=new AtomicReference<>();
                java.util.concurrent.atomic.AtomicInteger clicked=new java.util.concurrent.atomic.AtomicInteger();
                edt(()->windows[0].discard.addActionListener(e->clicked.incrementAndGet()));
                long revision=windows[0].client.snapshot().game().revision();
                edt(()->{
                    windows[0].setAlwaysOnTop(true); windows[0].toFront(); windows[0].requestFocus(); capture(windows[0].getContentPane(),folder.resolve("online-before-discard.png"));
                    Rectangle tile=windows[0].board.screenTileBounds(0); Point origin=windows[0].board.getLocationOnScreen();
                    click.set(new Point(origin.x+tile.x+tile.width/2,origin.y+tile.y+tile.height/2));
                });
                if(robot!=null) {
                    robot.delay(300); robot.mouseMove(click.get().x,click.get().y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
                } else edt(()->{
                    Board board=windows[0].board; Rectangle tile=board.screenTileBounds(0);
                    board.dispatchEvent(new java.awt.event.MouseEvent(board,java.awt.event.MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,
                        tile.x+tile.width/2,tile.y+tile.height/2,1,false,java.awt.event.MouseEvent.BUTTON1));
                });
                edt(()->{
                    capture(windows[0].getContentPane(),folder.resolve("online-selected.png"));
                    check(windows[0].board.selected==0&&windows[0].discard.isEnabled(),input+" selects online discard; selected="+windows[0].board.selected+", enabled="+windows[0].discard.isEnabled()+", bounds="+windows[0].discard.getBounds());
                    Point p=windows[0].discard.getLocationOnScreen(); click.set(new Point(p.x+windows[0].discard.getWidth()/2,p.y+windows[0].discard.getHeight()/2));
                    check(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().contains(click.get()),"discard center above taskbar / within usable screen");
                });
                if(robot!=null) { robot.mouseMove(click.get().x,click.get().y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle(); }
                else edt(()->windows[0].discard.doClick());
                edt(()->{ capture(windows[0].getContentPane(),folder.resolve("online-after-click.png")); check(clicked.get()==1,input+" fires discard button"); });
                await(()->windows[0].client.snapshot().game().revision()>revision,"wire discard",10); check(true,input+" submits through room server");
                edt(()->windows[0].setAlwaysOnTop(false));
                String token=windows[2].client.token(); edt(()->windows[2].dispose());
                await(()->windows[0].client.snapshot().stage()==RoomProtocol.Stage.PAUSED,"offline pause",10);
                edt(()->{ check(windows[0].board.paused,"offline pause visible"); capture(windows[0].getContentPane(),folder.resolve("online-paused.png")); });
                edt(()->{
                    windows[2]=new MultiplayerWindow(new RoomClient.Address("127.0.0.1",server.port(),server.room(),"smoke-123456","恢复玩家",token));
                    windows[2].setVisible(true); windows[2].connect();
                });
                await(()->windows[2].client.snapshot()!=null&&windows[0].client.snapshot().stage()==RoomProtocol.Stage.PLAYING,"rejoin",10);
                check(windows[2].client.snapshot().seat()==2,"closed client resumes original seat");
                edt(()->{ for(MultiplayerWindow w:windows) w.auto.doClick(); });
                await(()->java.util.Arrays.stream(windows).allMatch(w->w.client.snapshot().stage()==RoomProtocol.Stage.SETTLED),"four online clients finish round",180);
                edt(()->{
                    for(MultiplayerWindow w:windows) {
                        check(w.resultDialog!=null,"each client has result dialog"); w.auto.doClick();
                        check(w.board.view.players().stream().allMatch(p->p.hand().size()==p.handCount()),"each client has complete end-round hands");
                        w.review.doClick();
                        check(w.reviewDialog!=null&&w.reviewDialog.isShowing(),"each player can open full-table review");
                    }
                    capture(windows[0].resultDialog.getContentPane(),folder.resolve("online-result.png"));
                    capture(windows[0].reviewDialog.getContentPane(),folder.resolve("online-full-table.png"));
                    capture(windows[0].getContentPane(),folder.resolve("online-revealed-board.png"));
                });
                int nextDealer=windows[0].client.snapshot().game().result().winner();
                if(nextDealer<0) nextDealer=windows[0].client.snapshot().game().dealer();
                for(MultiplayerWindow w:windows) edt(()->w.ready.doClick());
                await(()->java.util.Arrays.stream(windows).allMatch(w->w.client.snapshot().game().round()==2),"ready next round",10);
                final int expectedDealer=nextDealer;
                edt(()->{
                    check(windows[0].client.snapshot().game().dealer()==expectedDealer,"correct next dealer online");
                    for(MultiplayerWindow w:windows) {
                        check(w.reviewDialog==null,"old review closes when next round starts");
                        for(int other=0;other<4;other++) if(other!=w.board.view.viewer())
                            check(w.board.view.players().get(other).hand().isEmpty(),"next round hides opponents again");
                    }
                    capture(windows[0].getContentPane(),folder.resolve("online-next-round.png"));
                });
                Files.writeString(folder.resolve("online-smoke-report.json"),"{\n  \"status\": \"passed\",\n  \"input\": \""+input+"\",\n  \"checks\": [\n    \""+String.join("\",\n    \"",checks)+"\"\n  ]\n}\n");
                Files.deleteIfExists(folder.resolve("failure.txt"));
            } finally { edt(()->{ for(MultiplayerWindow w:windows) if(w!=null) w.dispose(); }); }
        } catch(Throwable error) {
            java.io.StringWriter trace=new java.io.StringWriter(); error.printStackTrace(new java.io.PrintWriter(trace));
            Files.writeString(folder.resolve("failure.txt"),trace.toString()); throw error;
        }
    }
}
