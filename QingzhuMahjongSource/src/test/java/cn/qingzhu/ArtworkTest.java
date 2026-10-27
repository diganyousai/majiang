package cn.qingzhu;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Arrays;

/** Checks the dragon mapping and hidden rendering; emits the complete visual review sheet. */
public final class ArtworkTest {
    private ArtworkTest() {}
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static BufferedImage render(int tile) {
        BufferedImage image = new BufferedImage(64, 90, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); Artwork.quality(g);
        Artwork.tile(g, 5, 5, 50, 76, tile, false); g.dispose(); return image;
    }
    private static long colorPixels(BufferedImage image, boolean red) {
        long count = 0;
        for (int y = 10; y < 80; y++) for (int x = 10; x < 50; x++) {
            Color c = new Color(image.getRGB(x, y), true);
            if (c.getAlpha() > 200 && (red ? c.getRed() > c.getGreen() * 1.5 && c.getRed() > c.getBlue() * 1.5
                : c.getGreen() > c.getRed() * 1.5 && c.getGreen() > c.getBlue() * 1.3)) count++;
        }
        return count;
    }
    public static void main(String[] args) throws Exception {
        require(Tiles.name(31).equals("中") && colorPixels(render(31), true) > 80, "Red dragon must render red 中");
        require(Tiles.name(32).equals("发") && colorPixels(render(32), false) > 80, "Green dragon must render green 發");
        require(Tiles.name(33).equals("白") && colorPixels(render(33), true) == 0 && colorPixels(render(33), false) == 0,
            "White dragon must stay blank, not green/red");
        BufferedImage back = render(-1), hidden = render(-2);
        require(Arrays.equals(back.getRGB(0, 0, 64, 90, null, 0, 64), hidden.getRGB(0, 0, 64, 90, null, 0, 64)),
            "Every hidden tile uses exactly the same back");
        Path output = Path.of(args[0]); Files.createDirectories(output);
        BufferedImage sheet = new BufferedImage(1080, 780, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics(); Artwork.quality(g);
        g.setColor(Artwork.TABLE_EDGE); g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        Artwork.text(g, "青竹扣点点 " + Artwork.APP_VERSION + " · 牌面预览", 30, 40, 24, Artwork.LIGHT, true);
        Artwork.text(g, "34 种标准牌面 · 每种 4 张 · 共 136 张", 30, 68, 15, Artwork.MUTED, false);
        for (int tile = 0; tile < 34; tile++) {
            int x = 32 + tile % 9 * 116, y = 92 + tile / 9 * 161;
            Artwork.tile(g, x, y, 74, 106, tile, tile == 31);
            Artwork.tile(g, x + 80, y + 60, 26, 40, tile, false);
            Artwork.centered(g, Tiles.name(tile), x - 5, y + 132, 112, 16, Artwork.LIGHT, false);
        }
        Artwork.tile(g, 844, 575, 74, 106, -1, false);
        Artwork.centered(g, "统一牌背", 824, 707, 112, 16, Artwork.LIGHT, false);
        Artwork.text(g, "Artwork: lietxia/mahjong_graphic · default style", 30, 762, 14, Artwork.MUTED, false);
        g.dispose(); ImageIO.write(sheet, "png", output.resolve("tile-catalog.png").toFile());
        System.out.println("Artwork checks passed: 34 faces, dragon mapping, hidden back; tile-catalog.png emitted.");
    }
}
