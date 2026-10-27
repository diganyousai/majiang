package cn.qingzhu;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Artwork {
    public static final String APP_VERSION = "2.2.1";
    public static final Color GOLD = new Color(0xE4BE78), LIGHT = new Color(0xF4F5F7);
    public static final Color MUTED = new Color(0xB9CBCB), INK = new Color(0x182641);
    public static final Color NAVY = new Color(0x152342), PAPER = new Color(0xF4F5F7);
    public static final Color TABLE = new Color(0x20584F), TABLE_EDGE = new Color(0x163F3D);
    private static final BufferedImage[] FACES = new BufferedImage[34];
    private static final BufferedImage BACK;
    // Only standard suits/honors. The engine orders dragons as red/green/white,
    // whereas mahjong_graphic uses white/green/red (5z/6z/7z).
    private static final String[] HONOR_FILES = {"1z", "2z", "3z", "4z", "7z", "6z", "5z"};
    private record TileSize(int tile, int width, int height) {}
    private static final Map<TileSize, BufferedImage> SCALED = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<TileSize, BufferedImage> eldest) { return size() > 512; }
    };
    public static final BufferedImage ICON;
    static {
        try {
            ICON = load("/assets/icon.png");
            for (int t = 0; t < 34; t++) FACES[t] = load("/assets/tiles/" + faceCode(t) + ".png");
            BACK = load("/assets/tiles/back.png");
            if (BACK.getWidth() > BACK.getHeight()) throw new IOException("Expected a portrait tile back");
        } catch (IOException | RuntimeException e) { throw new ExceptionInInitializerError(e); }
    }
    private Artwork() {}
    private static BufferedImage load(String path) throws IOException {
        try (InputStream stream = Artwork.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing artwork: " + path);
            BufferedImage image = ImageIO.read(stream);
            if (image == null) throw new IOException("Invalid artwork: " + path);
            return image;
        }
    }
    static String faceCode(int tile) {
        if (tile < 0 || tile >= 34) throw new IllegalArgumentException("Unknown standard tile: " + tile);
        return tile < 27 ? Integer.toString(tile % 9 + 1) + "mps".charAt(tile / 9) : HONOR_FILES[tile - 27];
    }
    public static Font font(int size, boolean bold) {
        return new Font("Microsoft YaHei UI", bold ? Font.BOLD : Font.PLAIN, size);
    }
    public static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    }
    public static void text(Graphics2D g, String s, int x, int y, int size, Color color, boolean bold) {
        g.setFont(font(size, bold)); g.setColor(color); g.drawString(s, x, y);
    }
    public static void centered(Graphics2D g, String s, int x, int y, int width, int size, Color color, boolean bold) {
        g.setFont(font(size, bold)); g.setColor(color);
        g.drawString(s, x + (width - g.getFontMetrics().stringWidth(s)) / 2, y);
    }
    public static void tile(Graphics2D g, int x, int y, int w, int h, int tile, boolean selected) {
        if (w <= 0 || h <= 0) return;
        if (tile >= 34) throw new IllegalArgumentException("Unknown standard tile: " + tile);
        BufferedImage source = tile < 0 ? BACK : FACES[tile];
        double ratio = Math.min(w / (double) source.getWidth(), h / (double) source.getHeight());
        int width = Math.max(1, (int)Math.round(source.getWidth() * ratio));
        int height = Math.max(1, (int)Math.round(source.getHeight() * ratio));
        x += (w - width) / 2; y += (h - height) / 2;
        if (selected) {
            g.setColor(GOLD); g.fillRoundRect(x - 3, y - 3, width + 6, height + 6, 8, 8);
        }
        g.setColor(new Color(0, 0, 0, 45)); g.fillRoundRect(x + 1, y + 2, width, height, 6, 6);
        g.drawImage(scaled(tile < 0 ? -1 : tile, source, width, height), x, y, null);
    }
    private static synchronized BufferedImage scaled(int tile, BufferedImage source, int width, int height) {
        TileSize key = new TileSize(tile, width, height);
        BufferedImage image = SCALED.get(key);
        if (image != null) return image;
        image = source;
        // Progressive downsampling keeps fine circle/bamboo details readable in rivers.
        do {
            int nextW = Math.max(width, image.getWidth() / 2), nextH = Math.max(height, image.getHeight() / 2);
            BufferedImage next = new BufferedImage(nextW, nextH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics(); quality(g);
            g.drawImage(image, 0, 0, nextW, nextH, null); g.dispose(); image = next;
        } while (image.getWidth() != width || image.getHeight() != height);
        SCALED.put(key, image);
        return image;
    }
}
