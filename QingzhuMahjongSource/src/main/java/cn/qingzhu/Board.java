package cn.qingzhu;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import static cn.qingzhu.Game.*;
import static cn.qingzhu.Artwork.*;

public final class Board extends JPanel {
    static final int WIDTH = 1100, HEIGHT = 720;
    View view;
    int selected = -1;
    boolean paused, auto;
    private double scale = 1;
    private int offsetX, offsetY;
    private final List<Rectangle> hitBoxes = new ArrayList<>();
    IntConsumer onSelect = ignored -> {};
    Runnable onDiscard = () -> {};

    public Board() {
        setBackground(TABLE_EDGE); setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setToolTipText(""); setFocusable(true);
        getAccessibleContext().setAccessibleName("麻将牌桌");
        addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (paused || auto || view == null) return;
                int index = tileIndex(e.getPoint());
                if (index >= 0) {
                    selected = index; onSelect.accept(index); repaint();
                    if (e.getClickCount() == 2) onDiscard.run();
                }
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                setCursor(Cursor.getPredefinedCursor(!paused && !auto && tileIndex(e.getPoint()) >= 0
                    ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
    }
    private int tileIndex(Point p) {
        Point local = new Point((int)((p.x - offsetX) / scale), (int)((p.y - offsetY) / scale));
        for (int i = 0; i < hitBoxes.size(); i++) if (hitBoxes.get(i).contains(local)) {
            if(view!=null&&view.self().ting()&&(view.phase()!=Phase.DISCARD||view.current()!=view.viewer()
                ||view.self().drawn()<0||i!=view.self().hand().size()-1)) return -1;
            return i;
        }
        return -1;
    }
    @Override public String getToolTipText(MouseEvent e) {
        int i = tileIndex(e.getPoint());
        return view != null && i >= 0 && i < view.self().hand().size() ? Tiles.name(view.self().hand().get(i)) : null;
    }
    Rectangle screenTileBounds(int i) {
        Rectangle r = hitBoxes.get(i);
        return new Rectangle(offsetX + (int)(r.x * scale), offsetY + (int)(r.y * scale),
            (int)(r.width * scale), (int)(r.height * scale));
    }
    int hitBoxCount() { return hitBoxes.size(); }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create(); quality(g);
        scale = Math.min(getWidth() / (double) WIDTH, getHeight() / (double) HEIGHT);
        offsetX = (int)((getWidth() - WIDTH * scale) / 2); offsetY = (int)((getHeight() - HEIGHT * scale) / 2);
        g.translate(offsetX, offsetY); g.scale(scale, scale);
        g.setPaint(new GradientPaint(0, 0, TABLE, WIDTH, HEIGHT, TABLE_EDGE));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(new Color(0x548178)); g.drawRoundRect(12, 8, WIDTH - 24, HEIGHT - 16, 20, 20);
        g.setColor(new Color(255, 255, 255, 9));
        for (int y = 16; y < HEIGHT - 16; y += 6) g.drawLine(20, y, WIDTH - 20, y);
        g.setColor(new Color(0, 0, 0, 25)); g.fillRoundRect(153, 607, 927, 101, 14, 14);
        g.setColor(new Color(0x548178)); g.drawLine(165, 600, 1067, 600);
        hitBoxes.clear();
        if (view != null) {
            badge(g, seatAt(0), 24, 630, 132); badge(g, seatAt(1), 928, 125, 145);
            badge(g, seatAt(2), 458, 14, 184); badge(g, seatAt(3), 24, 125, 145);
            concealed(g, 2); concealed(g, 1); concealed(g, 3);
            river(g, seatAt(2), 390, 113, 10, 31, 43, 170);
            river(g, seatAt(0), 390, 419, 10, 31, 43, 170);
            river(g, seatAt(3), 147, 222, 7, 31, 43, 286);
            river(g, seatAt(1), 730, 222, 7, 31, 43, 286);
            center(g); hand(g);
        }
        if (paused) {
            g.setColor(new Color(8, 28, 24, 190)); g.fillRect(0, 0, WIDTH, HEIGHT);
            centered(g, "已暂停", 0, 348, WIDTH, 32, LIGHT, true);
        }
        g.dispose();
    }
    private int seatAt(int position) { return (view.viewer() + position) % 4; }
    private void badge(Graphics2D g, int seat, int x, int y, int width) {
        PlayerView p = view.players().get(seat);
        boolean active = view.phase() != Phase.OVER && view.current() == seat;
        if (active) {
            g.setColor(GOLD); g.fillRoundRect(x - 5, y - 5, width + 10, 55, 6, 6);
            g.setColor(NAVY); g.fillRoundRect(x - 3, y - 3, width + 6, 51, 5, 5);
        }
        int position = Math.floorMod(seat - view.viewer(), 4);
        g.setColor(new Color(position == 0 ? 0xEAC47D : position == 1 ? 0x9FC2DA : position == 2 ? 0xE3B4B0 : 0xADCDA0));
        g.fillOval(x, y + 1, 38, 38);
        centered(g, Tiles.HONORS[Math.floorMod(seat - view.dealer(), 4)], x, y + 27, 38, 20, INK, true);
        text(g, p.name() + (p.ting() ? " [听]" : ""), x + 48, y + 16, 16, p.ting() ? GOLD : LIGHT, true);
        text(g, (seat == view.dealer() ? "庄 · " : "") + (p.score() > 0 ? "+" : "") + p.score() + " 分",
            x + 48, y + 38, 13, MUTED, false);
    }
    private void concealed(Graphics2D original, int seat) {
        PlayerView p = view.players().get(seatAt(seat));
        Graphics2D g = (Graphics2D) original.create();
        if (seat == 1) { g.translate(1080, 200); g.rotate(Math.PI / 2); }
        else if (seat == 3) { g.translate(20, 570); g.rotate(-Math.PI / 2); }
        else g.translate(550, 63);
        int tileCount = p.handCount() + p.melds().stream().mapToInt(m -> m.tiles().size()).sum();
        int tileW = seat == 2 ? 27 : Math.min(23, (365 - 9 * p.melds().size()) / Math.max(1, tileCount) - 1);
        int tileH = tileW * 99 / 65;
        int total = p.handCount() * (tileW + 1);
        for (Meld m : p.melds()) total += m.tiles().size() * (tileW + 1) + 9;
        int x = seat == 2 ? -total / 2 : (370 - total) / 2;
        for (int i = 0; i < p.handCount(); i++) {
            int face=view.phase()==Phase.OVER&&i<p.hand().size()?p.hand().get(i):-1;
            tile(g, x, 0, tileW, tileH, face, false); x += tileW + 1;
        }
        for (Meld m : p.melds()) {
            x += 9;
            for (int t : m.tiles()) { tile(g, x, 0, tileW, tileH, t, false); x += tileW + 1; }
        }
        g.dispose();
    }
    private void river(Graphics2D g, int seat, int x, int y, int columns, int w, int h, int maxHeight) {
        List<Integer> river = view.players().get(seat).river();
        int rows = Math.max(1, (river.size() + columns - 1) / columns);
        int stepY = Math.min(h + 3, maxHeight / rows);
        if (stepY < h + 3) { h = stepY - 2; w = Math.min(w, h * 65 / 99); }
        for (int i = 0; i < river.size(); i++) {
            boolean latest = view.lastSource() == seat && i == river.size() - 1;
            tile(g, x + i % columns * 34, y + i / columns * stepY, w, h, river.get(i), latest);
        }
    }
    private void center(Graphics2D g) {
        int x = 447, y = 291;
        g.setColor(NAVY); g.fillRoundRect(x, y, 206, 109, 10, 10);
        g.setColor(new Color(0x657B91)); g.drawRoundRect(x, y, 206, 109, 10, 10);
        centered(g, "第 " + view.round() + " 局", x, y + 25, 206, 14, MUTED, false);
        if (!view.wildTiles().isEmpty()) centered(g, "耗子：" + String.join(" / ", view.wildTiles().stream().sorted().map(Tiles::name).toList()),
            x - 60, y - 12, 326, 14, GOLD, true);
        centered(g, Integer.toString(view.remaining()), x + 30, y + 67, 94, 38, LIGHT, true);
        text(g, "张", x + 126, y + 63, 15, MUTED, false);
        String status = view.phase() == Phase.OVER ? view.result().method() :
            view.phase() == Phase.ROB_KONG ? "抢杠响应" : view.phase() == Phase.REACTION ? "等待应牌" :
                view.current() == view.viewer() ? (auto ? "托管中" : "轮到你出牌") : view.players().get(view.current()).name() + "出牌";
        centered(g, status, x, y + 92, 206, 15, GOLD, true);
    }
    private void hand(Graphics2D g) {
        PlayerView p = view.self();
        int size = p.hand().size(), width = size * 53 + (p.drawn() >= 0 ? 13 : 0);
        for (Meld m : p.melds()) width += m.tiles().size() * 34 + 12;
        int x = 165 + Math.max(0, (900 - width) / 2), y = 627;
        for (int i = 0; i < size; i++) {
            if (p.drawn() >= 0 && i == size - 1) x += 13;
            int top = y - (i == selected ? 13 : 0);
            tile(g, x, top, 50, 76, p.hand().get(i), i == selected);
            hitBoxes.add(new Rectangle(x, top, 50, 76)); x += 53;
        }
        for (Meld m : p.melds()) {
            x += 12;
            for (int t : m.tiles()) { tile(g, x, y + 24, 32, 49, t, false); x += 34; }
        }
    }
}
