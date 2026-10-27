package cn.qingzhu;

import cn.qingzhu.vendor.Shanten;
import java.util.*;

public final class Tiles {
    public static final String[] HONORS = {"东", "南", "西", "北", "中", "发", "白"};
    private static final String NUMBERS = "一二三四五六七八九";
    private static final String[] SUITS = {"万", "筒", "条"};
    private Tiles() {}

    public static String name(int tile) {
        return tile < 0 ? "暗牌" : tile >= 27 ? HONORS[tile - 27] : NUMBERS.charAt(tile % 9) + SUITS[tile / 9];
    }
    public static int[] counts(Collection<Integer> tiles) {
        int[] result = new int[34];
        for (int tile : tiles) result[tile]++;
        return result;
    }
    public static int distance(Collection<Integer> hand, boolean opened) {
        return Shanten.calculate(counts(hand), !opened);
    }
    public static boolean win(List<Integer> hand, int melds, int extra) {
        List<Integer> full = new ArrayList<>(hand);
        if (extra >= 0) full.add(extra);
        if (full.size() != 14 - 3 * melds) return false;
        int[] counts = counts(full);
        if (Arrays.stream(counts).anyMatch(c -> c > 4)) return false;
        return Shanten.calculate(counts, melds == 0) == -1;
    }
}
