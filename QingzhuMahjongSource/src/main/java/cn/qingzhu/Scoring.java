package cn.qingzhu;

import java.util.*;

/** Pure settlement: every score is a transfer, with an inspectable explanation. */
public final class Scoring {
    private Scoring() {}
    public record Payment(int from,int to,long amount,String reason) {
        public Payment {
            if(from<0||from>3||to<0||to>3||from==to||amount<=0) throw new IllegalArgumentException("Invalid transfer");
        }
    }
    public record Gang(int owner,int source,int tile,Game.Kind kind,boolean sourceTing,boolean wild) {}
    public static List<Payment> gang(Rules rules,Gang g) {
        long unit=Rules.points(g.tile())*(g.kind()==Game.Kind.CONCEALED_KONG?2L:1L);
        String reason=g.kind()==Game.Kind.CONCEALED_KONG?"暗杠":g.kind()==Game.Kind.ADDED_KONG?"续杠":"直杠";
        if(g.wild() && (g.kind()==Game.Kind.KONG||g.kind()==Game.Kind.CONCEALED_KONG)) {
            unit=40; reason="耗子杠";
        }
        // Concealed tile values are not included in public ledger labels.
        if(!g.wild() && g.kind()==Game.Kind.KONG && (rules.gangPolicy==Rules.GangPolicy.ALWAYS_SOURCE
            || rules.gangPolicy==Rules.GangPolicy.BEFORE_TING_SOURCE && !g.sourceTing()))
            return List.of(new Payment(g.source(),g.owner(),unit*3,reason+"承包"));
        List<Payment> entries=new ArrayList<>();
        for(int seat=0;seat<4;seat++) if(seat!=g.owner()) entries.add(new Payment(seat,g.owner(),unit,reason));
        return List.copyOf(entries);
    }
    public static List<Payment> settleGangs(Rules rules,List<Gang> gangs,int winner,int source,boolean sourceTing) {
        List<Payment> entries=new ArrayList<>();
        boolean all=source>=0 && (rules.liability==Rules.Liability.SOURCE_PAYS_ALL
            || rules.liability==Rules.Liability.UNREADY_SOURCE_PAYS_ALL&&!sourceTing);
        for(Gang g:gangs) {
            if(rules.gangFollowsWinner && g.owner()!=winner) continue;
            if(all && g.owner()==source) continue;
            for(Payment p:gang(rules,g)) {
                if(all) entries.add(new Payment(source,p.to(),p.amount(),p.reason()+"·点炮包杠"));
                else entries.add(p);
            }
        }
        return List.copyOf(entries);
    }
    public static long handBase(Rules rules,HandRules.Analysis analysis,int winningTile,boolean selfDraw) {
        long value=Rules.points(winningTile);
        if(rules.scoring==Rules.Scoring.MULTIPLY) {
            for(var entry:rules.patternValues.entrySet()) if(analysis.patterns().contains(entry.getKey()))
                value=Math.multiplyExact(value,entry.getValue());
        } else if(rules.scoring==Rules.Scoring.ADD) {
            for(var entry:rules.patternValues.entrySet()) if(analysis.patterns().contains(entry.getKey()))
                value=Math.addExact(value,entry.getValue());
        }
        return selfDraw?Math.multiplyExact(value,2):value;
    }
    public static HandRules.Analysis best(Rules rules,HandRules.Analysis analysis,int tile,boolean selfDraw) {
        analysis=analysis.forWin(selfDraw);
        if(!analysis.win()) throw new IllegalArgumentException("风嘴子胡牌仅限自摸");
        return analysis.variants().stream().map(v->new HandRules.Analysis(true,v.patterns(),v.windGroups()))
            .max(Comparator.<HandRules.Analysis>comparingLong(a->handBase(rules,a,tile,selfDraw)
                +(rules.windMouth?a.windGroups()*5L*(selfDraw?2:1):0))
                .thenComparing(a->a.patterns().stream().sorted().toList().toString()))
            .orElse(analysis);
    }
    public static List<Payment> win(Rules rules,HandRules.Analysis analysis,int tile,int winner,int source,
                                    boolean sourceTing,int dealer) {
        boolean self=source<0;
        analysis=best(rules,analysis,tile,self);
        boolean sourceAll=!self && (!sourceTing || rules.liability==Rules.Liability.SOURCE_PAYS_ALL
            || rules.liability==Rules.Liability.UNREADY_SOURCE_PAYS_ALL&&!sourceTing);
        long base=handBase(rules,analysis,tile,self);
        long mouths=rules.windMouth?analysis.windGroups()*5L*(self?2:1):0;
        List<Payment> entries=new ArrayList<>();
        for(int seat=0;seat<4;seat++) if(seat!=winner) {
            long bonus=winner==dealer||seat==dealer?rules.dealerBonus:0;
            int payer=sourceAll?source:seat;
            entries.add(new Payment(payer,winner,base+bonus,"胡分"+(bonus>0?"（含带庄"+bonus+"）":"")));
            if(mouths>0) entries.add(new Payment(payer,winner,mouths,"风嘴子"));
        }
        return List.copyOf(entries);
    }
    public static List<Long> deltas(List<Payment> ledger) {
        long[] deltas=new long[4];
        for(Payment p:ledger) { deltas[p.from()]=Math.subtractExact(deltas[p.from()],p.amount()); deltas[p.to()]=Math.addExact(deltas[p.to()],p.amount()); }
        return Arrays.stream(deltas).boxed().toList();
    }
}
