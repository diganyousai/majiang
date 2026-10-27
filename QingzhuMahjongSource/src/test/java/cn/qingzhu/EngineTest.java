package cn.qingzhu;

import cn.qingzhu.vendor.Shanten;
import java.nio.file.*;
import java.util.*;
import static cn.qingzhu.Game.*;
import static cn.qingzhu.Rules.Pattern.*;

/** Independent fixed expectations, upstream shape oracle, and full state-machine games. */
public final class EngineTest {
    private static int checks, games;
    static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); checks++; }
    static List<Integer> list(int... values) { return Arrays.stream(values).boxed().toList(); }
    static Rules config(boolean ting,int minimum,Rules.GangPolicy policy,boolean follows,Rules.Liability liability,
                        Rules.Scoring scoring,Map<Rules.Pattern,Integer> amounts,Rules.Wild wild,boolean mouths,int bonus) {
        return new Rules(Rules.Mode.FRIENDS,ting,minimum,policy,follows,liability,scoring,amounts,wild,mouths,false,bonus,Rules.Hidden.DECLARATION);
    }
    static Rules plain(boolean ting) {
        return config(ting,6,Rules.GangPolicy.BEFORE_TING_SOURCE,false,Rules.Liability.NORMAL,Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,false,0);
    }
    static Game arrange(Rules rules,Map<Integer,List<Integer>> hands,Map<Integer,List<Meld>> groups) {
        Game g=new Game(53,rules); int[] counts=new int[34]; Arrays.fill(counts,4);
        for(var hand:hands.values()) for(int t:hand) counts[t]--;
        for(var melds:groups.values()) for(var m:melds) for(int t:m.tiles()) counts[t]--;
        List<Integer> available=new ArrayList<>();
        for(int t=0;t<34;t++) { if(counts[t]<0) throw new IllegalArgumentException("Invalid fixture at "+t); for(int n=0;n<counts[t];n++) available.add(t); }
        Collections.shuffle(available,new Random(53));
        for(int seat=0;seat<4;seat++) {
            Player p=g.players[seat]; p.hand.clear(); p.melds.clear(); p.river.clear(); p.hiddenRiver.clear(); p.ting=false; p.passedHu=false;
            p.melds.addAll(groups.getOrDefault(seat,List.of())); p.hand.addAll(hands.getOrDefault(seat,List.of()));
            while(p.hand.size()<(seat==0?14:13)-3*p.melds.size()) p.hand.add(available.removeLast());
            p.drawn=seat==0?p.hand.getLast():-1;
        }
        g.wall.clear(); g.wall.addAll(available); g.current=0; g.phase=Phase.DISCARD;
        g.offers.clear(); g.responses.clear(); g.lastSource=g.lastTile=-1; g.result=null;
        g.gangs.clear(); g.invalidate(); g.checkInvariants(); return g;
    }
    static Game arrange(Map<Integer,List<Integer>> hands,Map<Integer,List<Meld>> groups) { return arrange(plain(false),hands,groups); }
    static void ting(Game g,int seat) {
        Player p=g.players[seat]; List<Integer> hand=new ArrayList<>(p.hand);
        if(hand.size()==14-3*p.melds.size()) hand.remove(Integer.valueOf(p.drawn));
        p.ting=true; p.lockedWaits=g.handRules.waits(hand,p.melds,3); g.invalidate();
    }
    static void passAll(Game g) {
        for(int seat=0;seat<4;seat++) if(g.legalActions(seat).contains(Action.pass())) g.act(seat,Action.pass());
    }
    static boolean has(Game g,int seat,Kind kind,int tile) { return g.legalActions(seat).contains(new Action(kind,tile)); }
    static void runToEnd(Game g,boolean smart) {
        Random r=new Random(77);
        for(int step=0;step<800&&g.phase!=Phase.OVER;step++) {
            g.checkInvariants(); int seat=g.current;
            if(g.phase!=Phase.DISCARD) for(int i=0;i<4;i++) if(!g.legalActions(i).isEmpty()) { seat=i; break; }
            View v=g.view(seat); List<Action> actions=v.actions(); check(!actions.isEmpty(),"Progress has legal action");
            Action chosen;
            if(smart) chosen=Bot.choose(v);
            else chosen=actions.stream().filter(a->a.kind()==Kind.WIN||a.kind()==Kind.TING).findFirst().orElse(actions.get(r.nextInt(actions.size())));
            g.act(seat,chosen);
        }
        check(g.phase==Phase.OVER,"Full game terminates"); g.checkInvariants();
        check(g.result.deltas().stream().mapToLong(Long::longValue).sum()==0,"Round zero sum");
        check(Scoring.deltas(g.result.ledger()).equals(g.result.deltas()),"Ledger reconciles");
        for(int seat=0;seat<4;seat++) check(g.players[seat].score-g.startingScores[seat]==g.result.deltas().get(seat),"Realtime score not counted twice");
        if(g.result.winner()>=0) check(g.handRules.analyze(g.result.winningHand(),g.result.melds(),-1).win(),"Winner valid");
        games++;
    }
    private static void shapes() {
        HandRules h=new HandRules(Rules.friends(),Set.of());
        check(Rules.points(0)==1&&Rules.points(8)==9&&Rules.points(27)==10&&Rules.points(33)==10,"Tile points");
        check(h.analyze(list(0,1,2,9,10,11,18,19,20,27,27,27,28,28),List.of(),-1).win(),"Standard hand");
        check(h.analyze(list(0,0,3,3,9,9,13,13,22,22,27,27,33,33),List.of(),-1).patterns().contains(SEVEN_PAIRS),"Seven pairs");
        var luxury=h.analyze(list(0,0,0,0,9,9,13,13,22,22,27,27,33,33),List.of(),-1);
        check(luxury.win()&&luxury.patterns().contains(LUXURY_PAIRS)&&!luxury.patterns().contains(SEVEN_PAIRS),"Quad counts two pairs, no double seven bonus");
        check(h.analyze(list(0,8,9,17,18,26,27,28,29,30,31,32,33,33),List.of(),-1).patterns().contains(ORPHANS),"Orphans");
        var dragon=h.analyze(list(0,1,2,3,4,5,6,7,8,0,0,0,8,8),List.of(),-1);
        check(dragon.patterns().containsAll(Set.of(PURE,DRAGON)),"Pure dragon");
        check(h.analyze(list(0,1,2,3,4,5,6,7,8,27,27,27,33,33),List.of(),-1).patterns().contains(MIXED),"Mixed suit");
        check(h.analyze(list(27,27,27,28,28,28,29,29,29,30,30,30,33,33),List.of(),-1).patterns().containsAll(Set.of(ALL_HONORS,ALL_PONGS)),"All honors and pongs");
        var mouths= config(false,3,Rules.GangPolicy.EVERYONE,false,Rules.Liability.NORMAL,Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,true,0);
        HandRules wind=new HandRules(mouths,Set.of());
        List<Integer> windHand=list(27,28,30,31,32,33,0,1,2,9,10,11,18,18);
        check(!h.analyze(windHand,List.of(),-1).win(),"Wind mouths disabled by default");
        check(wind.analyze(windHand,List.of(),-1).windGroups()==2,"Distinct winds and dragons groups");
        check(!wind.analyze(list(27,28,31,31,32,33,0,1,2,9,10,11,18,18),List.of(),-1).win(),"Cannot mix winds with dragons");
        Rules wildRules=config(false,3,Rules.GangPolicy.EVERYONE,false,Rules.Liability.NORMAL,Rules.Scoring.NONE,Map.of(),Rules.Wild.ONE,false,0);
        HandRules wild=new HandRules(wildRules,Set.of(33));
        check(wild.analyze(list(0,1,33,9,10,11,18,19,20,27,27,27,28,28),List.of(),-1).win(),"Wildcard completes sequence");
        check(!wild.analyze(list(0,0,3,3,9,9,13,13,22,22,27,27,32,32),List.of(),-1).win(),"Wildcard mode forbids seven pairs even without wild in hand");
        check(!wild.analyze(list(0,8,9,17,18,26,27,28,29,30,31,32,32,33),List.of(),-1).win(),"Wildcard mode forbids orphans");
        check(wild.analyze(list(33,33,33,33,0,1,9,10,11,18,19,20,27,27),List.of(),-1).win(),"Multiple wildcards");
        try { config(true,6,Rules.GangPolicy.EVERYONE,false,Rules.Liability.NORMAL,Rules.Scoring.MULTIPLY,Map.of(PURE,3),Rules.Wild.ONE,false,0); throw new AssertionError("Invalid config allowed"); }
        catch(IllegalArgumentException expected) { checks++; }
    }
    private static void stateMachine() {
        Game g=new Game(1); check(g.wall.size()==83&&g.dealer==0,"136 deal, creator starts");
        View before=g.view(0);
        try { g.act(1,new Action(Kind.DISCARD,g.players[1].hand.getFirst())); throw new AssertionError("Out of turn accepted"); }
        catch(IllegalArgumentException expected) { check(g.view(0).equals(before),"Rejected action no mutation"); }
        List<Integer> waiting=list(0,1,2,9,10,11,18,19,20,27,27,27,28);
        List<Integer> full=new ArrayList<>(waiting); full.add(28);
        g=arrange(plain(true),Map.of(0,full),Map.of());
        check(!has(g,0,Kind.WIN,28),"Must explicitly declare ting"); ting(g,0);
        check(has(g,0,Kind.WIN,28),"Reported ting can win"); g.act(0,new Action(Kind.WIN,28));
        check(g.result.deltas().equals(List.of(60L,-20L,-20L,-20L)),"10 point self draw x2 each");
        g.newRound(false); check(g.dealer==0&&g.players[0].score==60,"Winner retains dealer and score");

        // Report on an honor tile that the next player holds a pair of: it must NOT offer pong.
        List<Integer> reportHand=new ArrayList<>(waiting); reportHand.add(32);
        g=arrange(plain(true),Map.of(0,reportHand,1,list(32,32)),Map.of());
        check(has(g,0,Kind.TING,32),"Ready hand can report"); g.act(0,new Action(Kind.TING,32));
        check(g.players[0].ting&&g.phase==Phase.DISCARD&&g.current==1,"Report discard has no reaction window");
        check(g.view(1).players().get(0).river().equals(list(-1))&&g.view(1).lastTile()==-1,"Report river and last tile hidden");
        check(g.view(0).players().get(0).river().equals(list(32)),"Owner sees own report tile");
        check(g.view(1).events().stream().noneMatch(e->e.contains("发")),"Report tile absent from logs");
        check(!has(g,1,Kind.PONG,32),"Hidden discard cannot pong"); g.checkInvariants();
        g=arrange(plain(true),Map.of(0,reportHand,1,list(32,32,32)),Map.of());
        g.act(0,new Action(Kind.TING,32)); check(!has(g,1,Kind.KONG,32),"Hidden report cannot kong");
        List<Integer> waitReport=list(3,4,5,12,13,14,21,22,23,30,30,30,32);
        g=arrange(plain(true),Map.of(0,reportHand,1,waitReport),Map.of()); ting(g,1);
        g.act(0,new Action(Kind.TING,32)); check(!has(g,1,Kind.WIN,32)&&g.phase==Phase.DISCARD,"Hidden report cannot win");

        List<Integer> lowWait=list(9,10,11,18,19,20,27,27,27,31,31,31,2,33);
        g=arrange(plain(true),Map.of(0,lowWait),Map.of()); check(!has(g,0,Kind.TING,33),"Default ting requires a six-plus wait");
        Rules lowTing=config(true,3,Rules.GangPolicy.EVERYONE,false,Rules.Liability.NORMAL,Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,false,0);
        g=arrange(lowTing,Map.of(0,lowWait),Map.of()); check(has(g,0,Kind.TING,33),"Configured low-point ting allowed");
        g=arrange(plain(true),Map.of(0,list(31,31,31,31,0,1,2,9,10,11,18,19,20,28)),Map.of());
        g.players[0].drawn=31; ting(g,0);
        check(g.players[0].lockedWaits.equals(Set.of(28)),"Fixture single honor wait");
        check(has(g,0,Kind.CONCEALED_KONG,31),"Ting concealed kong keeps waits");
        check(!has(g,0,Kind.DISCARD,0)&&has(g,0,Kind.DISCARD,31),"Ting hand locked to drawn tile");
        g.act(0,new Action(Kind.CONCEALED_KONG,31)); g.checkInvariants();
        check(g.players[0].ting&&g.players[0].lockedWaits.equals(Set.of(28)),"Kong keeps report state");
        // Five-pair shape: removing a natural triplet to form a kong destroys the seven-pair wait.
        g=arrange(lowTing,Map.of(0,list(31,31,31,31,0,0,9,9,13,13,22,22,27,27)),Map.of());
        g.players[0].drawn=31; ting(g,0);
        check(g.players[0].lockedWaits.equals(Set.of(31))&&has(g,0,Kind.WIN,31),"Luxury pairs waits on fourth honor");
        check(!has(g,0,Kind.CONCEALED_KONG,31),"Kong would destroy luxury pairs, so reject");

        for(int point=1;point<=10;point++) {
            int t=point==10?28:point-1;
            List<Integer> base=list(9,10,11,18,19,20,27,27,27,31,31,31,t);
            List<Integer> complete=new ArrayList<>(base); complete.add(t);
            g=arrange(Map.of(0,complete),Map.of()); check(has(g,0,Kind.WIN,t)==(point>=3),"Self draw floor "+point);
            g=arrange(Map.of(0,list(t),1,base),Map.of()); g.act(0,new Action(Kind.DISCARD,t));
            check(has(g,1,Kind.WIN,t)==(point>=6),"Discard win floor "+point);
        }
        g=arrange(Map.of(0,list(4),1,list(2,3,5,6)),Map.of()); g.act(0,new Action(Kind.DISCARD,4));
        for(int seat=0;seat<4;seat++) check(g.legalActions(seat).stream().noneMatch(a->a.kind()==Kind.CHOW),"No chow anywhere");
        g=arrange(Map.of(0,list(31),1,list(31,31,31)),Map.of()); g.act(0,new Action(Kind.DISCARD,31));
        check(has(g,1,Kind.KONG,31)&&!has(g,1,Kind.PONG,31),"Three copies cannot first pong");
        g.act(1,new Action(Kind.KONG,31)); passAll(g); g.checkInvariants();
        check(g.gangs.size()==1&&g.players[0].score==0,"Friends defer gang scoring");

        // Two complete waits, arrival order reversed: nearest winner only.
        List<Integer> w1=list(0,1,2,9,10,11,18,19,20,27,27,27,28);
        List<Integer> w2=list(3,4,5,12,13,14,21,22,23,30,30,30,28);
        g=arrange(Map.of(0,list(28),1,w1,2,w2),Map.of()); g.act(0,new Action(Kind.DISCARD,28));
        g.act(2,new Action(Kind.WIN,28)); g.act(1,new Action(Kind.WIN,28)); passAll(g);
        check(g.result.winner()==1&&g.result.deltas().equals(List.of(-30L,30L,0L,0L)),"Single nearest winner, unready source pays all");
        g.newRound(false); check(g.dealer==1,"Winner becomes next dealer");

        g=arrange(Map.of(0,list(28),1,w1),Map.of()); g.act(0,new Action(Kind.DISCARD,28));
        g.act(1,Action.pass()); check(g.players[1].passedHu || g.current==1,"Pass hu latch"); passAll(g);
        check(!g.players[1].passedHu&&g.current==1,"Pass latch resets at own draw");

        for(boolean rob:List.of(false,true)) {
            List<Integer> robWait=list(3,4,9,10,11,18,19,20,27,27,27,28,28);
            g=arrange(Map.of(0,list(5),1,robWait),Map.of(0,List.of(new Meld(Kind.PONG,list(5,5,5),3))));
            int remaining=g.wall.size(); g.act(0,new Action(Kind.ADDED_KONG,5));
            check(g.phase==Phase.ROB_KONG,"Rob added kong window");
            if(rob) g.act(1,new Action(Kind.WIN,5)); passAll(g);
            if(rob) check(g.result.winner()==1&&g.gangs.isEmpty()&&g.wall.size()==remaining,"Rob prevents gang fee and replacement");
            else check(g.gangs.size()==1&&g.wall.size()==remaining-1,"Completed added kong recorded once");
            g.checkInvariants();
        }
        g=arrange(Rules.coins(),Map.of(0,list(31,31,31,31)),Map.of()); g.act(0,new Action(Kind.CONCEALED_KONG,31));
        check(g.players[0].score==60&&g.players[1].score==-20,"Coin gang realtime");
        check(g.view(1).players().get(0).melds().getFirst().tiles().equals(list(-1,-1,-1,-1)),"Concealed gang stays hidden");
        g.newRound(true); check(g.players[0].score==0,"Restart rolls back current coin gang");
        g=new Game(7); g.players[3].river.addAll(g.wall); g.wall.clear(); g.invalidate();
        g.act(0,new Action(Kind.DISCARD,g.players[0].hand.getLast())); passAll(g);
        check(g.phase==Phase.OVER&&g.result.winner()==-1,"Wall exhaustion draw"); g.checkInvariants();
    }
    private static void ledger() {
        HandRules.Analysis ordinary=new HandRules.Analysis(true,Set.of(),0);
        Rules r=Rules.friends();
        check(Scoring.deltas(Scoring.win(r,ordinary,5,1,0,false,0)).equals(List.of(-18L,18L,0L,0L)),"Unready source covers three shares");
        check(Scoring.deltas(Scoring.win(r,ordinary,5,1,0,true,0)).equals(List.of(-6L,18L,-6L,-6L)),"Ready source all players pay");
        for(Rules.GangPolicy policy:Rules.GangPolicy.values()) for(boolean ready:List.of(false,true)) {
            r=config(false,3,policy,false,Rules.Liability.NORMAL,Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,false,0);
            var entries=Scoring.gang(r,new Scoring.Gang(1,0,5,Kind.KONG,ready,false));
            boolean cover=policy==Rules.GangPolicy.ALWAYS_SOURCE||policy==Rules.GangPolicy.BEFORE_TING_SOURCE&&!ready;
            check(Scoring.deltas(entries).equals(cover?List.of(-18L,18L,0L,0L):List.of(-6L,18L,-6L,-6L)),"Direct gang policy "+policy+ready);
        }
        r=Rules.friends();
        check(Scoring.deltas(Scoring.gang(r,new Scoring.Gang(1,0,31,Kind.ADDED_KONG,false,false))).equals(List.of(-10L,30L,-10L,-10L)),"Added kong values");
        check(Scoring.deltas(Scoring.gang(r,new Scoring.Gang(1,1,31,Kind.CONCEALED_KONG,false,false))).equals(List.of(-20L,60L,-20L,-20L)),"Concealed kong double");
        check(Scoring.deltas(Scoring.gang(r,new Scoring.Gang(1,0,5,Kind.KONG,false,true))).equals(List.of(-40L,120L,-40L,-40L)),"Wildcard gang overrides ordinary payment");
        HandRules.Analysis multiple=new HandRules.Analysis(true,Set.of(PURE,DRAGON),0);
        check(Scoring.handBase(Rules.coins(),multiple,5,true)==72,"Coin 6 x pure3 x dragon2 x self2");
        r=config(false,3,Rules.GangPolicy.EVERYONE,false,Rules.Liability.NORMAL,Rules.Scoring.ADD,Map.of(PURE,3,DRAGON,2),Rules.Wild.NONE,false,5);
        check(Scoring.handBase(r,multiple,5,true)==22,"Add before self multiplier");
        check(Scoring.deltas(Scoring.win(r,multiple,5,1,-1,false,0)).equals(List.of(-27L,71L,-22L,-22L)),"Dealer per-pair bonus outside multiplier");
        r=config(false,3,Rules.GangPolicy.EVERYONE,false,Rules.Liability.NORMAL,Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,true,0);
        check(Scoring.deltas(Scoring.win(r,new HandRules.Analysis(true,Set.of(),2),5,1,-1,false,0)).equals(List.of(-32L,96L,-32L,-32L)),"Two wind groups self doubled separately");
        List<Scoring.Gang> gs=List.of(new Scoring.Gang(0,0,0,Kind.CONCEALED_KONG,false,false),new Scoring.Gang(1,1,5,Kind.CONCEALED_KONG,false,false),new Scoring.Gang(2,2,8,Kind.CONCEALED_KONG,false,false));
        r=config(false,3,Rules.GangPolicy.EVERYONE,true,Rules.Liability.NORMAL,Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,false,0);
        check(Scoring.deltas(Scoring.settleGangs(r,gs,1,0,false)).equals(List.of(-12L,36L,-12L,-12L)),"Only winner gangs survive");
        check(Scoring.settleGangs(r,gs,-1,-1,false).isEmpty(),"No winner means no gang follows winner");
        r=config(false,3,Rules.GangPolicy.EVERYONE,false,Rules.Liability.SOURCE_PAYS_ALL,Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,false,0);
        check(Scoring.deltas(Scoring.settleGangs(r,gs,1,0,true)).equals(List.of(-90L,36L,54L,0L)),"Source covers table gangs, own gang excluded");
        r=config(false,3,Rules.GangPolicy.EVERYONE,false,Rules.Liability.UNREADY_SOURCE_PAYS_ALL,Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,false,0);
        check(Scoring.deltas(Scoring.settleGangs(r,gs,1,0,false)).equals(List.of(-90L,36L,54L,0L)),"Unready source covers gangs");
        check(!Scoring.deltas(Scoring.settleGangs(r,gs,1,0,true)).equals(List.of(-90L,36L,54L,0L)),"Ready source exemption");
        Rules decomposed=config(false,3,Rules.GangPolicy.EVERYONE,false,Rules.Liability.NORMAL,Rules.Scoring.MULTIPLY,Map.of(ALL_PONGS,4),Rules.Wild.NONE,true,0);
        var ambiguous=new HandRules(decomposed,Set.of()).analyze(list(27,27,27,28,28,28,29,29,29,30,30,30,33,33),List.of(),-1);
        var chosen=Scoring.best(decomposed,ambiguous,33,false);
        check(chosen.patterns().contains(ALL_PONGS)&&chosen.windGroups()==0,"Never stack wind mouths from a different decomposition");
    }
    private static void windMouthWins() {
        List<Integer> complete=list(27,28,30,31,32,33,0,1,2,9,10,11,18,18);
        List<Integer> waiting=new ArrayList<>(complete); waiting.remove(Integer.valueOf(32));
        for(boolean reported:List.of(false,true)) {
            Rules rules=config(reported,6,Rules.GangPolicy.EVERYONE,false,Rules.Liability.NORMAL,Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,true,0);
            Game g=arrange(rules,Map.of(0,complete),Map.of()); g.players[0].drawn=32;
            if(reported) ting(g,0);
            check(has(g,0,Kind.WIN,32),"Wind mouth still permits self draw, ting="+reported);
            g.act(0,new Action(Kind.WIN,32));
            check(g.result.method().equals("自摸")&&g.result.ledger().stream().anyMatch(p->p.reason().equals("风嘴子")),"Wind mouth self draw retains its scoring");
            g=arrange(rules,Map.of(0,list(32),1,waiting),Map.of()); if(reported) ting(g,1);
            g.act(0,new Action(Kind.DISCARD,32));
            check(!has(g,1,Kind.WIN,32),"Wind mouth cannot take another player's discard, ting="+reported);

            List<Integer> robWait=list(27,28,30,0,1,2,9,10,11,3,4,18,18);
            g=arrange(rules,Map.of(0,list(5),1,robWait),Map.of(0,List.of(new Meld(Kind.PONG,list(5,5,5),3))));
            if(reported) ting(g,1);
            g.act(0,new Action(Kind.ADDED_KONG,5));
            check(!has(g,1,Kind.WIN,5),"Wind mouth cannot rob an added kong, ting="+reported);

            List<Integer> ordinary=list(0,1,2,9,10,11,18,19,20,27,27,27,28);
            g=arrange(rules,Map.of(0,list(28),1,ordinary),Map.of()); if(reported) ting(g,1);
            g.act(0,new Action(Kind.DISCARD,28)); check(has(g,1,Kind.WIN,28),"Wind option does not block ordinary discard wins");
            g.act(1,new Action(Kind.WIN,28)); passAll(g);
            check(g.result.ledger().stream().noneMatch(p->p.reason().equals("风嘴子")),"Ordinary discard win has no wind-mouth fee");
        }
        Rules rules=config(false,6,Rules.GangPolicy.EVERYONE,false,Rules.Liability.NORMAL,Rules.Scoring.NONE,Map.of(),Rules.Wild.NONE,true,0);
        var ambiguous=new HandRules(rules,Set.of()).analyze(list(27,27,27,28,28,28,29,29,29,30,30,30,33,33),List.of(),-1);
        check(ambiguous.windGroups()>0&&ambiguous.forWin(false).win(),"An ordinary decomposition remains usable when wind decompositions also exist");
        check(Scoring.best(rules,ambiguous,33,false).windGroups()==0,"Discard win must select a non-wind decomposition even if wind scores higher");
        var wind=new HandRules(rules,Set.of()).analyze(complete,List.of(),-1);
        try { Scoring.win(rules,wind,32,1,0,false,0); throw new AssertionError("Wind discard settlement accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
    }
    private static void endRoundReveal() {
        Game g=arrange(plain(false),Map.of(0,list(0,1,2,9,10,11,18,19,20,27,27,27,28,28)),
            Map.of(2,List.of(new Meld(Kind.CONCEALED_KONG,list(31,31,31,31),2))));
        int hidden=g.wall.removeLast(); g.players[3].river.add(hidden); g.players[3].hiddenRiver.add(true);
        g.lastSource=3; g.lastTile=hidden; g.invalidate();
        View before=g.view(1);
        check(before.players().get(0).hand().isEmpty()&&before.players().get(2).melds().getFirst().tiles().contains(-1)
            &&before.players().get(3).river().equals(list(-1))&&before.lastTile()==-1,"Before round end all private zones remain hidden");
        g.act(0,new Action(Kind.WIN,28)); g.checkInvariants();
        for(int viewer=0;viewer<4;viewer++) {
            View view=g.view(viewer);
            for(int seat=0;seat<4;seat++) {
                PlayerView player=view.players().get(seat);
                check(player.hand().equals(g.players[seat].hand)&&player.handCount()==player.hand().size(),"End-round hand revealed to every seat");
                check(player.river().equals(g.players[seat].river)&&player.melds().equals(g.players[seat].melds),"End-round discards and concealed kongs revealed");
            }
            check(view.lastTile()==hidden,"End-round last hidden discard revealed");
        }
        check(before.players().get(0).hand().isEmpty()&&before.players().get(3).river().equals(list(-1)),"Published active snapshot stays immutable");
        View ended=g.view(1); g.newRound(false);
        check(ended.players().get(0).hand().size()==14,"Review snapshot retains only the old round");
        for(int viewer=0;viewer<4;viewer++) for(int seat=0;seat<4;seat++) if(viewer!=seat)
            check(g.view(viewer).players().get(seat).hand().isEmpty(),"Next round restores private hands");
        g=new Game(7); g.players[3].river.addAll(g.wall); g.wall.clear(); g.invalidate();
        g.act(0,new Action(Kind.DISCARD,g.players[0].hand.getLast())); passAll(g);
        check(g.phase==Phase.OVER&&g.result.winner()==-1&&g.view(0).players().stream().allMatch(p->p.hand().size()==p.handCount()),"Drawn round also exposes all four hands");
    }
    public static void main(String[] args)throws Exception {
        long started=System.nanoTime(); int oracle=0;
        for(String row:Files.readAllLines(Path.of(args[0]))) {
            String[] f=row.split("\t"); int[] c=f[0].chars().map(n->n-'0').toArray(); int actual=Shanten.calculate(c,!f[1].equals("1"));
            check(actual==Integer.parseInt(f[2]),"Upstream oracle "+oracle); check((actual==-1)==f[3].equals("1"),"Shape oracle "+oracle); oracle++;
        }
        shapes(); stateMachine(); ledger(); windMouthWins(); endRoundReveal();
        for(int seed=0;seed<12;seed++) runToEnd(new Game(seed,seed%2==0?Rules.friends():Rules.coins()),true);
        for(int seed=0;seed<64;seed++) {
            Rules.Wild w=Rules.Wild.values()[seed%4];
            Rules r=config(true,6,Rules.GangPolicy.values()[seed%3],seed%2==0,Rules.Liability.values()[seed%3],Rules.Scoring.NONE,Map.of(),w,seed%2==1,0);
            Game g=new Game(seed,r); check(g.wildTiles.size()==(w==Rules.Wild.NONE?0:w==Rules.Wild.TWO?2:1),"Wildcard selection count");
            if(w==Rules.Wild.WIND) check(g.wildTiles.stream().allMatch(t->t>=27),"Wind wildcard range");
            runToEnd(g,false);
        }
        System.out.printf(Locale.ROOT,"PASS: %,d assertions; %,d upstream oracle cases; %d full games; %.2f seconds%n",checks,oracle,games,(System.nanoTime()-started)/1e9);
    }
}
