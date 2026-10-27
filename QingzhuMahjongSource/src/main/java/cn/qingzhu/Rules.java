package cn.qingzhu;

import java.util.*;

/** Immutable room configuration. No UI or game state. Points are virtual scores. */
public final class Rules {
    public enum Mode { FRIENDS, COIN }
    public enum GangPolicy { BEFORE_TING_SOURCE, ALWAYS_SOURCE, EVERYONE }
    public enum Liability { NORMAL, SOURCE_PAYS_ALL, UNREADY_SOURCE_PAYS_ALL }
    public enum Scoring { NONE, MULTIPLY, ADD }
    public enum Wild { NONE, ONE, WIND, TWO }
    public enum Hidden { DECLARATION }
    public enum Pattern {
        ALL_HONORS("风一色"), PURE("清一色"), MIXED("混一色"), DRAGON("一条龙"),
        ALL_PONGS("碰碰胡"), SEVEN_PAIRS("七对"), LUXURY_PAIRS("豪华七对"), ORPHANS("十三幺");
        public final String label;
        Pattern(String label) { this.label = label; }
    }
    public final Mode mode;
    public final boolean requireTing, gangFollowsWinner, windMouth, followDealer;
    public final int minTing, dealerBonus;
    public final GangPolicy gangPolicy;
    public final Liability liability;
    public final Scoring scoring;
    public final Wild wild;
    public final Hidden hidden;
    public final Map<Pattern,Integer> patternValues;

    public Rules(Mode mode, boolean requireTing, int minTing, GangPolicy gangPolicy,
                 boolean gangFollowsWinner, Liability liability, Scoring scoring,
                 Map<Pattern,Integer> patternValues, Wild wild, boolean windMouth,
                 boolean followDealer, int dealerBonus, Hidden hidden) {
        this.mode = Objects.requireNonNull(mode); this.gangPolicy = Objects.requireNonNull(gangPolicy);
        this.liability = Objects.requireNonNull(liability); this.scoring = Objects.requireNonNull(scoring);
        this.wild = Objects.requireNonNull(wild); this.hidden = Objects.requireNonNull(hidden);
        if (minTing < 3 || minTing > 10 || !Set.of(0,5,10).contains(dealerBonus))
            throw new IllegalArgumentException("Invalid room score setting");
        if (followDealer) throw new IllegalArgumentException("跟庄判定尚未确认，不能启用");
        if (scoring == Scoring.NONE && !patternValues.isEmpty()) throw new IllegalArgumentException("未开启牌型计分");
        if (wild != Wild.NONE && (scoring != Scoring.NONE || !patternValues.isEmpty()))
            throw new IllegalArgumentException("耗子与翻倍/加点互斥");
        if (mode == Mode.FRIENDS && patternValues.containsKey(Pattern.MIXED))
            throw new IllegalArgumentException("亲友房没有混一色计分");
        patternValues.forEach((p,v) -> { if (v < 1 || v > 16) throw new IllegalArgumentException("牌型数值限 1～16"); });
        this.requireTing = requireTing; this.minTing = minTing;
        this.gangFollowsWinner = gangFollowsWinner; this.windMouth = windMouth;
        this.followDealer = followDealer; this.dealerBonus = dealerBonus;
        this.patternValues = Map.copyOf(patternValues);
    }
    public static Rules friends() {
        return new Rules(Mode.FRIENDS,true,6,GangPolicy.BEFORE_TING_SOURCE,false,Liability.NORMAL,
            Scoring.NONE,Map.of(),Wild.NONE,false,false,0,Hidden.DECLARATION);
    }
    public static Rules coins() {
        return new Rules(Mode.COIN,true,3,GangPolicy.BEFORE_TING_SOURCE,false,Liability.NORMAL,
            Scoring.MULTIPLY,Map.of(Pattern.MIXED,2,Pattern.PURE,3,Pattern.DRAGON,2,
                Pattern.SEVEN_PAIRS,2,Pattern.LUXURY_PAIRS,4,Pattern.ORPHANS,4),
            Wild.NONE,false,false,0,Hidden.DECLARATION);
    }
    public static int points(int tile) { return tile >= 27 ? 10 : tile % 9 + 1; }
    public String title() { return mode == Mode.COIN ? "金币规则（模拟积分）" : "亲友房"; }
    public String description() {
        return title() + " · 136张 · 不可吃 · " + (requireTing ? "须报听" : "无需报听")
            + " · 报听门槛" + minTing + "点\n1/2点不胡，3/4/5点仅自摸，6～10点可点炮；一炮一胡。\n"
            + "报听后锁定听口、不可碰，杠前后听口必须相同。\n"
            + "牌型计分：" + new String[]{"关闭","翻倍","加点"}[scoring.ordinal()] + "；耗子："
            + new String[]{"关闭","捉耗子","风耗子","捉双耗子"}[wild.ordinal()] + "\n"
            + "点杠：" + new String[]{"听前杠包杠","点杠者包杠","点杠者不包杠"}[gangPolicy.ordinal()]
            + "；包杠：" + new String[]{"无","点炮包胡包杠","未报听点炮包杠"}[liability.ordinal()] + "\n"
            + "杠随胡走：" + (gangFollowsWinner?"开":"关") + "；风嘴子：" + (windMouth?"开":"关") + "；带庄：" + dealerBonus + "\n"
            + "胡点取胡入牌；承包支付三家总额；仅报听弃牌隐藏，不能碰、杠、胡。\n"
            + "跟庄判定待确认，未启用；点炮者包胡家杠选项按要求不启用。\n"
            + "过胡按本人下次摸牌恢复；三张同牌不得先碰后杠。\n"
            + (windMouth?"风嘴子组成的胡牌仅限自摸，不可点炮或抢杠胡。\n":"")
            + "亲友房牌型数值默认留空，可手动设置（1～16）；不是预设官方数值。\n"
            + String.join("、",patternValues.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(e->e.getKey().label+"="+e.getValue()).toList());
    }
}
