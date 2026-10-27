package cn.qingzhu;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import static cn.qingzhu.Game.*;
import cn.qingzhu.Game.Action;

/** Executed by both the source JAR and the packaged EXE, against real Swing controls. */
final class Smoke {
    private static GameWindow window;
    private static int initialSpeed;
    private static final List<String> checks = new ArrayList<>();
    private Smoke() {}
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void require(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks.add(label); }
    static void run(Path folder) throws Exception {
        Files.createDirectories(folder);
        try {
            edt(() -> {
                window = new GameWindow(42); initialSpeed = window.speed.getSelectedIndex();
                window.smokeMode = true; window.sound.setSelected(false);
                window.setAlwaysOnTop(true); window.setVisible(true); window.toFront(); window.requestFocus();
            });
            edt(() -> testSettings(folder));
            Robot robot = new Robot(); robot.setAutoDelay(100); robot.waitForIdle();
            for (int[] size : new int[][]{{1400,940},{1060,760},{1000,720}}) {
                edt(() -> window.setSize(size[0],size[1])); robot.waitForIdle();
                edt(() -> {
                    BufferedImage img = capture(window.getContentPane());
                    try { ImageIO.write(img, "png", folder.resolve("window-" + size[0] + ".png").toFile()); }
                    catch (Exception e) { throw new RuntimeException(e); }
                    require(window.board.hitBoxCount() == 14, "hand layout at " + size[0]);
                    for (int i = 0; i < window.board.hitBoxCount(); i++) {
                        Rectangle r = window.board.screenTileBounds(i);
                        if (r.x < 0 || r.y < 0 || r.getMaxX() > window.board.getWidth() || r.getMaxY() > window.board.getHeight())
                            throw new AssertionError("Tile outside board");
                    }
                    require(window.discard.getWidth() > 0 && window.discard.getHeight() > 0, "controls at " + size[0]);
                });
            }
            AtomicReference<Point> click = new AtomicReference<>();
            edt(() -> {
                window.setSize(1400,940);
                capture(window.getContentPane());
            }); robot.waitForIdle(); robot.delay(350);
            edt(() -> {
                capture(window.getContentPane());
                window.toFront(); window.requestFocus();
                Rectangle r = window.board.screenTileBounds(0); Point origin = window.board.getLocationOnScreen();
                click.set(new Point(origin.x + r.x + r.width / 2, origin.y + r.y + r.height / 2));
            });
            robot.delay(350);
            ImageIO.write(robot.createScreenCapture(window.getBounds()), "png", folder.resolve("native-desktop.png").toFile());
            robot.mouseMove(click.get().x,click.get().y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
            edt(() -> {
                require(window.board.selected == 0 && window.discard.isEnabled(), "real mouse selection");
                Point p = window.discard.getLocationOnScreen(); click.set(new Point(p.x + window.discard.getWidth()/2,p.y + window.discard.getHeight()/2));
            });
            robot.mouseMove(click.get().x,click.get().y); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.waitForIdle();
            edt(() -> {
                require(window.game.players[0].river.size() == 1, "real mouse discard");
                window.pause.doClick(); require(window.board.paused && !window.discard.isEnabled(), "pause disables action");
                window.pause.doClick(); require(!window.board.paused, "resume");
            });
            int steps = 0;
            while (window.game.phase != Phase.OVER && steps++ < 700) {
                AtomicReference<View> state = new AtomicReference<>();
                edt(() -> {
                    int seat = window.game.current;
                    if (window.game.phase != Phase.DISCARD)
                        for (int i = 0; i < 4; i++) if (!window.game.legalActions(i).isEmpty()) { seat = i; break; }
                    state.set(window.game.view(seat));
                });
                View v = state.get(); Action a = Bot.choose(v);
                if (v.viewer() == 0 && (v.phase() == Phase.REACTION || v.phase() == Phase.ROB_KONG)) {
                    edt(() -> {
                        JButton pass = null;
                        for (Component c : window.actionPanel.getComponents()) if (c instanceof JButton b && "PASS".equals(b.getName())) pass = b;
                        require(pass != null && pass.isEnabled(), "human claim controls");
                        try { ImageIO.write(capture(window.getContentPane()), "png", folder.resolve("claim.png").toFile()); }
                        catch (Exception e) { throw new RuntimeException(e); }
                    });
                }
                edt(() -> window.perform(v.viewer(),a));
            }
            edt(() -> {
                require(window.game.phase == Phase.OVER && window.resultDialog != null, "complete game and result dialog");
                window.game.checkInvariants();
                try {
                    ImageIO.write(capture(window.resultDialog.getContentPane()),"png",folder.resolve("result.png").toFile());
                    ImageIO.write(capture(window.getContentPane()),"png",folder.resolve("finished.png").toFile());
                } catch (Exception e) { throw new RuntimeException(e); }
                int expectedDealer = window.game.result.winner() < 0 ? window.game.dealer : window.game.result.winner();
                window.next.doClick(); require(window.game.round == 2 && window.game.dealer == expectedDealer, "winner becomes dealer");
                window.auto.doClick(); require(window.auto.isSelected(), "autoplay toggle");
                window.auto.doClick(); require(!window.auto.isSelected(), "return human control");
            });
            edt(() -> {
                arrangeHumanClaim();
                JButton ting = null;
                for (Component c : window.actionPanel.getComponents())
                    if (c instanceof JButton b && "TING".equals(b.getName())) ting = b;
                require(ting != null && ting.isEnabled(), "human ting control");
                try { ImageIO.write(capture(window.getContentPane()), "png", folder.resolve("claim.png").toFile()); }
                catch (Exception e) { throw new RuntimeException(e); }
                ting.doClick();
                MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
                if (path.length > 0 && path[0] instanceof JPopupMenu menu) {
                    require(menu.getComponentCount() > 0, "ting discard menu opens");
                    ((JMenuItem)menu.getComponent(0)).doClick(); MenuSelectionManager.defaultManager().clearSelectedPath();
                }
                require(window.game.players[0].ting, "ting button commits report");
                require(window.game.view(1).players().get(0).river().getLast() == -1, "reported discard is hidden");
                require(window.game.phase == Phase.DISCARD && window.game.current == 1, "reported discard has no claim window");
                window.game.checkInvariants();
                window.newRound(true); window.speed.setSelectedIndex(2); window.smokeMode = false; window.auto.doClick();
                window.pause.doClick();
            });
            long pausedRevision = window.game.revision;
            Thread.sleep(500);
            edt(() -> { require(window.game.revision == pausedRevision, "paused autoplay stays still"); window.pause.doClick(); });
            long deadline = System.nanoTime() + 120_000_000_000L;
            AtomicReference<Phase> phase = new AtomicReference<>();
            do {
                Thread.sleep(100);
                edt(() -> phase.set(window.game.phase));
            } while (phase.get() != Phase.OVER && System.nanoTime() < deadline);
            edt(() -> {
                require(window.game.phase == Phase.OVER, "timer and background-worker autoplay completes");
                window.game.checkInvariants();
            });
            String json = "{\n  \"status\": \"passed\",\n  \"runtime\": \"" + System.getProperty("java.runtime.version")
                + "\",\n  \"steps\": " + steps + ",\n  \"checks\": [\n    \"" + String.join("\",\n    \"", checks) + "\"\n  ]\n}\n";
            Files.writeString(folder.resolve("smoke-report.json"),json);
            Files.deleteIfExists(folder.resolve("failure.txt"));
        } catch (Throwable error) {
            Files.writeString(folder.resolve("failure.txt"), error.toString()); throw new RuntimeException(error);
        } finally {
            if (window != null) edt(() -> {
                window.smokeMode = true; window.pause.setSelected(true);
                window.speed.setSelectedIndex(initialSpeed); window.dispose();
            });
        }
    }
    private static void arrangeHumanClaim() {
        Game game = window.game;
        List<Integer> human = List.of(0,1,2,9,10,11,18,19,20,27,27,27,28,32);
        List<Integer> pool = new ArrayList<>();
        for (int t = 0; t < 34; t++) for (int n = 0; n < 4; n++) pool.add(t);
        for (int t : human) pool.remove(Integer.valueOf(t));
        Collections.shuffle(pool,new java.util.Random(53));
        for (int seat = 0; seat < 4; seat++) {
            Player p = game.players[seat]; p.hand.clear(); p.melds.clear(); p.river.clear(); p.hiddenRiver.clear(); p.drawn = -1;
            p.ting = false; p.passedHu = false; p.lockedWaits = java.util.Set.of();
            if (seat == 0) p.hand.addAll(human);
            while (p.hand.size() < (seat == 0 ? 14 : 13)) p.hand.add(pool.removeLast());
            Collections.sort(p.hand);
        }
        game.wall.clear(); game.wall.addAll(pool); game.offers.clear(); game.responses.clear(); game.result = null;
        game.current = 0; game.phase = Phase.DISCARD; game.players[0].drawn = 32; game.lastSource = game.lastTile = -1;
        game.invalidate(); game.checkInvariants(); window.refresh();
    }
    private static <T extends Component> List<T> components(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) found.add(type.cast(c));
            if (c instanceof Container container) found.addAll(components(container, type));
        }
        return found;
    }
    private static void testSettings(Path folder) {
        for (boolean coins : List.of(false, true)) {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Timer timer = new Timer(200, event -> {
                JDialog dialog = null;
                for (Window w : Window.getWindows()) if (w instanceof JDialog d && d.isShowing() && d.getTitle().equals("创建扣点点牌桌")) dialog = d;
                if (dialog == null) { failure.set(new AssertionError("Settings dialog absent")); return; }
                JOptionPane pane = components(dialog, JOptionPane.class).getFirst();
                try {
                    var combos = components(dialog, JComboBox.class);
                    JComboBox<?> mode = combos.stream().filter(c -> c.getItemAt(0).equals("亲友房")).findFirst().orElseThrow();
                    JComboBox<?> wild = combos.stream().filter(c -> c.getItemCount() == 4 && c.getItemAt(1).equals("捉耗子")).findFirst().orElseThrow();
                    JComboBox<?> scoring = combos.stream().filter(c -> c.getItemCount() == 3 && c.getItemAt(1).equals("翻倍")).findFirst().orElseThrow();
                    JCheckBox pure = components(dialog, JCheckBox.class).stream().filter(c -> c.getText().equals("清一色")).findFirst().orElseThrow();
                    require(components(dialog, JTextField.class).stream().filter(c -> c.getColumns() == 6).allMatch(c -> c.getText().isEmpty()), "friend pattern amounts start blank");
                    if (coins) {
                        mode.setSelectedIndex(1); require(!wild.isEnabled() && !scoring.isEnabled(), "coin settings fixed");
                        ImageIO.write(capture(dialog.getContentPane()), "png", folder.resolve("room-coins.png").toFile());
                    } else {
                        ImageIO.write(capture(dialog.getContentPane()), "png", folder.resolve("room-settings.png").toFile());
                        JTabbedPane tabs = components(dialog, JTabbedPane.class).getFirst(); tabs.setSelectedIndex(1);
                        ImageIO.write(capture(dialog.getContentPane()), "png", folder.resolve("room-patterns.png").toFile());
                        scoring.setSelectedIndex(1); require(pure.isEnabled(), "pattern options enabled on explicit mode"); pure.doClick();
                        wild.setSelectedIndex(1);
                        require(scoring.getSelectedIndex() == 0 && !scoring.isEnabled() && !pure.isEnabled(), "wildcard disables all pattern scoring");
                    }
                    pane.setValue(JOptionPane.OK_OPTION);
                } catch (Throwable error) { failure.set(error); pane.setValue(JOptionPane.CANCEL_OPTION); }
            });
            timer.setRepeats(false); timer.start();
            Rules chosen = RulesDialog.choose(window); timer.stop();
            if (failure.get() != null) throw new AssertionError("Settings smoke failed", failure.get());
            require(chosen != null, "room settings accepted");
            if (coins) require(chosen.mode == Rules.Mode.COIN && chosen.minTing == 3 && chosen.patternValues.size() == 6, "coin preset returned");
            else require(chosen.wild == Rules.Wild.ONE && chosen.scoring == Rules.Scoring.NONE && chosen.patternValues.isEmpty(), "disabled stale pattern selection not charged");
        }
    }
    private static BufferedImage capture(Component component) {
        BufferedImage image = new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics(); component.printAll(g); g.dispose(); return image;
    }
}
