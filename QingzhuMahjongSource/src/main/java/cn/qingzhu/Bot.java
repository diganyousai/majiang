package cn.qingzhu;

import cn.qingzhu.vendor.Shanten;
import java.util.*;
import static cn.qingzhu.Game.*;

/** Decisions depend exclusively on a seat's immutable, privacy-filtered view. */
public final class Bot {
    private final Map<String, Integer> cache = new HashMap<>();
    private int distance(int[] counts, boolean opened) {
        String key = Arrays.toString(counts) + opened;
        return cache.computeIfAbsent(key, ignored -> Shanten.calculate(counts, !opened));
    }
    public static Action choose(View view) { return new Bot().decide(view); }
    public static int hint(View view) {
        List<Action> discards = view.actions().stream().filter(a -> a.kind() == Kind.DISCARD).toList();
        if (discards.size() == 1) return discards.getFirst().tile();
        return new Bot().discard(view.self().hand(), !view.self().melds().isEmpty(), visible(view));
    }
    private static int[] visible(View view) {
        int[] counts = new int[34];
        for (PlayerView p : view.players()) {
            for (int t : p.river()) if (t >= 0) counts[t]++;
            for (Meld m : p.melds()) for (int t : m.tiles()) if (t >= 0) counts[t]++;
        }
        return counts;
    }
    private Action decide(View view) {
        List<Action> actions = view.actions();
        if (actions.isEmpty()) throw new IllegalArgumentException("No action for viewer");
        for (Action a : actions) if (a.kind() == Kind.WIN) return a;
        for (Action a : actions) if (a.kind() == Kind.TING) return a;
        List<Action> discards = actions.stream().filter(a -> a.kind() == Kind.DISCARD).toList();
        if (view.self().ting() && discards.size() == 1) {
            for (Action a : actions) if (a.kind() == Kind.CONCEALED_KONG || a.kind() == Kind.ADDED_KONG) return a;
            return discards.getFirst();
        }
        PlayerView p = view.self(); boolean opened = !p.melds().isEmpty();
        int baseline = distance(Tiles.counts(p.hand()), opened);
        if (view.phase() == Phase.REACTION || view.phase() == Phase.ROB_KONG) {
            Action best = Action.pass(); int bestDistance = baseline;
            for (Action a : actions) {
                if (a.kind() != Kind.CHOW && a.kind() != Kind.PONG && a.kind() != Kind.KONG) continue;
                List<Integer> trial = new ArrayList<>(p.hand());
                List<Integer> needed = new ArrayList<>(a.kind() == Kind.CHOW ? a.sequence()
                    : Collections.nCopies(a.kind() == Kind.KONG ? 4 : 3, a.tile()));
                needed.remove(Integer.valueOf(a.tile()));
                for (int t : needed) trial.remove(Integer.valueOf(t));
                int d = distance(Tiles.counts(trial), true);
                if (a.kind() == Kind.KONG && d <= baseline) return a;
                if (d < bestDistance) { bestDistance = d; best = a; }
            }
            return best;
        }
        for (Action a : actions) if (a.kind() == Kind.CONCEALED_KONG || a.kind() == Kind.ADDED_KONG) {
            List<Integer> trial = new ArrayList<>(p.hand());
            for (int i = 0; i < (a.kind() == Kind.CONCEALED_KONG ? 4 : 1); i++) trial.remove(Integer.valueOf(a.tile()));
            if (distance(Tiles.counts(trial), true) <= baseline + 1) return a;
        }
        return new Action(Kind.DISCARD, discard(p.hand(), opened, visible(view)));
    }
    private int discard(List<Integer> hand, boolean opened, int[] visible) {
        int bestTile = hand.getFirst(), bestDistance = Integer.MAX_VALUE, bestOuts = -1;
        double bestStructure = -1;
        for (int tile : new TreeSet<>(hand)) {
            int[] counts = Tiles.counts(hand); counts[tile]--; visible[tile]++;
            int d = distance(counts, opened), outs = 0;
            for (int t = 0; t < 34; t++) {
                int remaining = 4 - counts[t] - visible[t];
                if (remaining <= 0) continue;
                counts[t]++;
                if (distance(counts, opened) < d) outs += remaining;
                counts[t]--;
            }
            visible[tile]--;
            double structure = 0;
            for (int n : counts) structure += Math.min(n, 3) * (Math.min(n, 3) - 1) * .4;
            for (int t = 0; t < 27; t++) if (counts[t] > 0) {
                if (t % 9 < 8 && counts[t + 1] > 0) structure += .8;
                if (t % 9 < 7 && counts[t + 2] > 0) structure += .35;
            }
            if (d < bestDistance || d == bestDistance && (outs > bestOuts || outs == bestOuts && structure > bestStructure)) {
                bestTile = tile; bestDistance = d; bestOuts = outs; bestStructure = structure;
            }
        }
        return bestTile;
    }
}
