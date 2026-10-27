/*
 * Java translation of mahjong 2.0.0, mahjong/shanten.py.
 * Copyright (c) 2017 mahjong Python library contributors. MIT License.
 * See licenses/mahjong-MIT.txt and THIRD_PARTY_NOTICES.md.
 * The upstream search and four-copy handling are preserved.
 */
package cn.qingzhu.vendor;

import java.util.Arrays;

public final class Shanten {
    private static final int[] ORPHANS = {0,8,9,17,18,26,27,28,29,30,31,32,33};

    private Shanten() {}

    public static int calculate(int[] counts, boolean specialHands) {
        if (counts.length != 34 || Arrays.stream(counts).anyMatch(n -> n < 0 || n > 4))
            throw new IllegalArgumentException("Invalid tile counts");
        int total = Arrays.stream(counts).sum();
        if (total > 14 || total % 3 == 0)
            throw new IllegalArgumentException("Invalid concealed tile count: " + total);
        int result = new Regular(counts).calculate(total);
        if (specialHands && total >= 13) {
            int pairs = 0, kinds = 0, terminals = 0;
            boolean terminalPair = false;
            for (int count : counts) {
                if (count >= 2) pairs++;
                if (count >= 1) kinds++;
            }
            result = Math.min(result, 6 - pairs + Math.max(0, 7 - kinds));
            for (int tile : ORPHANS) {
                if (counts[tile] != 0) terminals++;
                if (counts[tile] >= 2) terminalPair = true;
            }
            result = Math.min(result, 13 - terminals - (terminalPair ? 1 : 0));
        }
        return result;
    }

    private static final class Regular {
        final int[] tiles;
        int melds, tatsu, pairs, jidahai, fourCopies, isolated, minimum = 8;

        Regular(int[] counts) { tiles = counts.clone(); }

        int calculate(int total) {
            removeHonors(total);
            for (int i = 0; i < 27; i++) if (tiles[i] == 4) fourCopies |= 1 << i;
            melds += (14 - total) / 3;
            run(0);
            return minimum;
        }

        void run(int depth) {
            if (minimum == -1) return;
            while (depth < 27 && tiles[depth] == 0) depth++;
            if (depth >= 27) { update(); return; }
            int i = depth % 9;
            if (tiles[depth] == 4) {
                set(depth, 1);
                if (i < 7 && tiles[depth + 2] != 0) {
                    if (tiles[depth + 1] != 0) { sequence(depth, 1); run(depth + 1); sequence(depth, -1); }
                    partial(depth, 2, 1); run(depth + 1); partial(depth, 2, -1);
                }
                if (i < 8 && tiles[depth + 1] != 0) {
                    partial(depth, 1, 1); run(depth + 1); partial(depth, 1, -1);
                }
                single(depth, 1); run(depth + 1); single(depth, -1);
                set(depth, -1);
                pair(depth, 1);
                if (i < 7 && tiles[depth + 2] != 0) {
                    if (tiles[depth + 1] != 0) { sequence(depth, 1); run(depth); sequence(depth, -1); }
                    partial(depth, 2, 1); run(depth + 1); partial(depth, 2, -1);
                }
                if (i < 8 && tiles[depth + 1] != 0) {
                    partial(depth, 1, 1); run(depth + 1); partial(depth, 1, -1);
                }
                pair(depth, -1);
            }
            if (tiles[depth] == 3) {
                set(depth, 1); run(depth + 1); set(depth, -1);
                pair(depth, 1);
                if (i < 7 && tiles[depth + 1] != 0 && tiles[depth + 2] != 0) {
                    sequence(depth, 1); run(depth + 1); sequence(depth, -1);
                } else {
                    if (i < 7 && tiles[depth + 2] != 0) {
                        partial(depth, 2, 1); run(depth + 1); partial(depth, 2, -1);
                    }
                    if (i < 8 && tiles[depth + 1] != 0) {
                        partial(depth, 1, 1); run(depth + 1); partial(depth, 1, -1);
                    }
                }
                pair(depth, -1);
                if (i < 7 && tiles[depth + 2] >= 2 && tiles[depth + 1] >= 2) {
                    sequence(depth, 1); sequence(depth, 1); run(depth);
                    sequence(depth, -1); sequence(depth, -1);
                }
            }
            if (tiles[depth] == 2) {
                pair(depth, 1); run(depth + 1); pair(depth, -1);
                if (i < 7 && tiles[depth + 2] != 0 && tiles[depth + 1] != 0) {
                    sequence(depth, 1); run(depth); sequence(depth, -1);
                }
            }
            if (tiles[depth] == 1) {
                if (i < 6 && tiles[depth + 1] == 1 && tiles[depth + 2] != 0 && tiles[depth + 3] != 4) {
                    sequence(depth, 1); run(depth + 2); sequence(depth, -1);
                } else {
                    single(depth, 1); run(depth + 1); single(depth, -1);
                    if (i < 7 && tiles[depth + 2] != 0) {
                        if (tiles[depth + 1] != 0) { sequence(depth, 1); run(depth + 1); sequence(depth, -1); }
                        partial(depth, 2, 1); run(depth + 1); partial(depth, 2, -1);
                    }
                    if (i < 8 && tiles[depth + 1] != 0) {
                        partial(depth, 1, 1); run(depth + 1); partial(depth, 1, -1);
                    }
                }
            }
        }

        void update() {
            int value = 8 - melds * 2 - tatsu - pairs;
            int candidates = melds + tatsu;
            if (pairs != 0) candidates += pairs - 1;
            else if (fourCopies != 0 && isolated != 0 && (fourCopies | isolated) == fourCopies) value++;
            if (candidates > 4) value += candidates - 4;
            if (value != -1 && value < jidahai) value = jidahai;
            minimum = Math.min(minimum, value);
        }

        void set(int k, int direction) { tiles[k] -= 3 * direction; melds += direction; }
        void pair(int k, int direction) { tiles[k] -= 2 * direction; pairs += direction; }
        void sequence(int k, int direction) {
            tiles[k] -= direction; tiles[k + 1] -= direction; tiles[k + 2] -= direction;
            melds += direction;
        }
        void partial(int k, int step, int direction) {
            tiles[k] -= direction; tiles[k + step] -= direction; tatsu += direction;
        }
        void single(int k, int direction) {
            tiles[k] -= direction;
            if (direction == 1) isolated |= 1 << k;
            else isolated &= ~(1 << k);
        }
        void removeHonors(int total) {
            int fours = 0, singles = 0;
            for (int i = 27; i < 34; i++) {
                if (tiles[i] == 4) { melds++; jidahai++; fours |= 1 << (i - 27); singles |= 1 << (i - 27); }
                if (tiles[i] == 3) melds++;
                if (tiles[i] == 2) pairs++;
                if (tiles[i] == 1) singles |= 1 << (i - 27);
            }
            if (jidahai != 0 && total % 3 == 2) jidahai--;
            if (singles != 0) {
                isolated |= 1 << 27;
                if ((fours | singles) == fours) fourCopies |= 1 << 27;
            }
        }
    }
}
