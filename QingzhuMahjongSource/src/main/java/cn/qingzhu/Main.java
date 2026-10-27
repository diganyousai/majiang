package cn.qingzhu;

import javax.swing.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.PrintWriter;
import java.io.StringWriter;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> report(error));
        try {
            if (args.length == 2 && args[0].equals("--network-test")) { RoomSelfTest.run(Path.of(args[1])); return; }
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            for (String key : new String[]{"Panel.background", "OptionPane.background", "TabbedPane.background", "Viewport.background"})
                UIManager.put(key, new javax.swing.plaf.ColorUIResource(Artwork.PAPER));
            for (String key : new String[]{"Label.foreground", "CheckBox.foreground", "TextArea.foreground"})
                UIManager.put(key, new javax.swing.plaf.ColorUIResource(Artwork.INK));
            for (Object key : UIManager.getDefaults().keySet().toArray())
                if (UIManager.get(key) instanceof javax.swing.plaf.FontUIResource)
                    UIManager.put(key, new javax.swing.plaf.FontUIResource(Artwork.font(14, false)));
            if (args.length == 2 && args[0].equals("--smoke-test")) { Smoke.run(Path.of(args[1])); return; }
            if (args.length == 2 && args[0].equals("--network-smoke")) { RoomSmoke.run(Path.of(args[1])); return; }
            if (args.length == 2 && args[0].equals("--network-ui-test")) { RoomSmoke.run(Path.of(args[1]), false); return; }
            SwingUtilities.invokeLater(RoomDialogs::launch);
        } catch (Throwable e) {
            if (args.length > 0 && (args[0].equals("--smoke-test") || args[0].startsWith("--network-"))) e.printStackTrace(); else report(e);
            System.exit(1);
        }
    }
    static void report(Throwable error) {
        error.printStackTrace();
        try {
            String local = System.getenv("LOCALAPPDATA");
            Path folder = Path.of(local == null ? System.getProperty("user.home") : local, "QingzhuMahjongJava");
            Files.createDirectories(folder);
            StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
            Files.writeString(folder.resolve("error.log"), trace.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                "发生错误，日志已保存到：\n" + folder.resolve("error.log"), "青竹麻将", JOptionPane.ERROR_MESSAGE));
        } catch (Exception ignored) { }
    }
}
