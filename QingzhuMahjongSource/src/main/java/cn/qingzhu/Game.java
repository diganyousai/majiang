package cn.qingzhu;

import java.util.*;

/** Authoritative local state machine. No GUI, clock or network dependencies. */
public final class Game {
    public enum Phase { DISCARD, REACTION, ROB_KONG, OVER }
    public enum Kind { DISCARD, TING, CHOW, PONG, KONG, CONCEALED_KONG, ADDED_KONG, WIN, PASS }
    public record Action(Kind kind,int tile,List<Integer> sequence) {
        public Action { sequence=List.copyOf(sequence); }
        public Action(Kind kind,int tile) { this(kind,tile,List.of()); }
        public static Action pass() { return new Action(Kind.PASS,-1); }
        public String label() { return switch(kind) {
            case DISCARD -> "出牌"; case TING -> "报听"; case CHOW -> "吃"; case PONG -> "碰";
            case KONG -> "明杠"; case CONCEALED_KONG -> "暗杠"; case ADDED_KONG -> "补杠";
            case WIN -> "胡"; case PASS -> "过";
        }; }
    }
    public record Meld(Kind kind,List<Integer> tiles,int source) { public Meld { tiles=List.copyOf(tiles); } }
    static final class Player {
        final String name;
        final List<Integer> hand=new ArrayList<>(),river=new ArrayList<>();
        final List<Boolean> hiddenRiver=new ArrayList<>();
        final List<Meld> melds=new ArrayList<>();
        int drawn=-1; long score; boolean ting,passedHu;
        Set<Integer> lockedWaits=Set.of();
        Player(String name) { this.name=name; }
    }
    public record Result(int winner,int source,int tile,String method,List<Long> deltas,List<Integer> winningHand,
                         List<Meld> melds,List<Scoring.Payment> ledger,Set<Rules.Pattern> patterns) {
        public Result { deltas=List.copyOf(deltas); winningHand=List.copyOf(winningHand); melds=List.copyOf(melds);
            ledger=List.copyOf(ledger); patterns=Set.copyOf(patterns); }
    }
    public record PlayerView(String name,List<Integer> hand,int handCount,List<Meld> melds,List<Integer> river,
                             long score,int drawn,boolean ting) {
        public PlayerView { hand=List.copyOf(hand); melds=List.copyOf(melds); river=List.copyOf(river); }
    }
    public record View(long revision,int viewer,int round,int dealer,int current,Phase phase,int remaining,
                       List<PlayerView> players,List<Action> actions,List<String> events,int lastSource,int lastTile,
                       Result result,Set<Integer> wildTiles,String ruleTitle) {
        public View { players=List.copyOf(players); actions=List.copyOf(actions); events=List.copyOf(events); wildTiles=Set.copyOf(wildTiles); }
        public PlayerView self() { return players.get(viewer); }
    }
    final Player[] players;
    final List<Integer> wall=new ArrayList<>();
    final Map<Integer,List<Action>> offers=new LinkedHashMap<>();
    final Map<Integer,Action> responses=new LinkedHashMap<>();
    final List<String> events=new ArrayList<>();
    final List<Scoring.Gang> gangs=new ArrayList<>();
    final long[] startingScores=new long[4];
    final Random random;
    final Rules rules;
    Set<Integer> wildTiles=Set.of();
    HandRules handRules;
    int round,dealer=-1,current,lastSource=-1,lastTile=-1;
    int kongSeat=-1,kongIndex=-1,kongTile=-1;
    Phase phase; Result result; long revision;
    private long cachedRevision=-1;
    private final Map<Integer,List<Action>> actionCache=new HashMap<>();

    public Game(long seed) { this(seed,Rules.friends()); }
    public Game(long seed,Rules rules) { this(seed,rules,List.of("你","小满","听雨","松风")); }
    public Game(long seed,Rules rules,List<String> names) {
        if(names.size()!=4) throw new IllegalArgumentException("Four players required");
        players=names.stream().map(Player::new).toArray(Player[]::new);
        random=new Random(seed); this.rules=rules; newRound(false);
    }
    public void newRound(boolean restart) {
        if(!restart && round>0 && phase!=Phase.OVER) throw new IllegalStateException("本局尚未结束");
        if(restart) for(int seat=0;seat<4;seat++) players[seat].score=startingScores[seat];
        else { round++; dealer=dealer<0?0:result!=null&&result.winner()>=0?result.winner():dealer; }
        for(int seat=0;seat<4;seat++) startingScores[seat]=players[seat].score;
        wall.clear(); for(int t=0;t<34;t++) for(int n=0;n<4;n++) wall.add(t);
        Collections.shuffle(wall,random);
        for(Player p:players) {
            p.hand.clear(); p.river.clear(); p.hiddenRiver.clear(); p.melds.clear(); p.drawn=-1;
            p.ting=false; p.passedHu=false; p.lockedWaits=Set.of();
            for(int n=0;n<13;n++) p.hand.add(wall.removeLast()); Collections.sort(p.hand);
        }
        Set<Integer> selected=new TreeSet<>();
        if(rules.wild!=Rules.Wild.NONE) {
            selected.add(rules.wild==Rules.Wild.WIND?27+random.nextInt(7):random.nextInt(34));
            if(rules.wild==Rules.Wild.TWO) while(selected.size()<2) selected.add(random.nextInt(34));
        }
        wildTiles=Set.copyOf(selected); handRules=new HandRules(rules,wildTiles);
        offers.clear(); responses.clear(); events.clear(); gangs.clear(); result=null;
        lastSource=lastTile=kongSeat=kongIndex=kongTile=-1;
        log("第 "+round+" 局 · "+players[dealer].name+"坐庄 · "+rules.title());
        if(!wildTiles.isEmpty()) log("耗子："+String.join("、",wildTiles.stream().sorted().map(Tiles::name).toList()));
        invalidate(); draw(dealer,false); invalidate();
    }
    void invalidate() { revision++; actionCache.clear(); cachedRevision=revision; }
    private void log(String message) { events.add(message); if(events.size()>100) events.removeFirst(); }
    private void draw(int seat,boolean replacement) {
        if(wall.isEmpty()) { finishDraw(); return; }
        current=seat; phase=Phase.DISCARD;
        Player p=players[seat]; p.passedHu=false; Collections.sort(p.hand);
        p.drawn=replacement?wall.removeFirst():wall.removeLast(); p.hand.add(p.drawn);
        log(p.name+(replacement?"补牌":"摸牌"));
    }
    public List<Action> legalActions(int seat) {
        if(seat<0||seat>=4) throw new IllegalArgumentException("Invalid seat");
        if(cachedRevision!=revision) { actionCache.clear(); cachedRevision=revision; }
        return actionCache.computeIfAbsent(seat,this::calculateActions);
    }
    private List<Action> calculateActions(int seat) {
        if(phase==Phase.REACTION||phase==Phase.ROB_KONG) {
            if(responses.containsKey(seat)||!offers.containsKey(seat)) return List.of();
            List<Action> out=new ArrayList<>(offers.get(seat)); out.add(Action.pass()); return List.copyOf(out);
        }
        if(phase!=Phase.DISCARD||current!=seat) return List.of();
        Player p=players[seat]; List<Action> out=new ArrayList<>();
        if(p.ting) { if(p.drawn>=0) out.add(new Action(Kind.DISCARD,p.drawn)); }
        else for(int t:new TreeSet<>(p.hand)) {
            out.add(new Action(Kind.DISCARD,t));
            List<Integer> trial=new ArrayList<>(p.hand); trial.remove(Integer.valueOf(t));
            Set<Integer> waits=handRules.waits(trial,p.melds,3);
            if(waits.stream().anyMatch(w->Rules.points(w)>=rules.minTing)) out.add(new Action(Kind.TING,t));
        }
        if(p.drawn>=0 && canWin(seat,p.drawn,true)) out.add(new Action(Kind.WIN,p.drawn));
        if(!wall.isEmpty()) {
            int[] counts=Tiles.counts(p.hand);
            for(int t=0;t<34;t++) if(counts[t]==4 && kongKeepsWaits(seat,t,Kind.CONCEALED_KONG)) out.add(new Action(Kind.CONCEALED_KONG,t));
            for(Meld m:p.melds) if(m.kind()==Kind.PONG && counts[m.tiles().getFirst()]>0
                && kongKeepsWaits(seat,m.tiles().getFirst(),Kind.ADDED_KONG)) out.add(new Action(Kind.ADDED_KONG,m.tiles().getFirst()));
        }
        return List.copyOf(out);
    }
    private boolean kongKeepsWaits(int seat,int tile,Kind kind) {
        Player p=players[seat]; if(!p.ting) return true;
        // A self-declared kong must use the drawn tile, otherwise an unrelated
        // new tile could be absorbed into the locked hand while waits happen to match.
        if(kind!=Kind.KONG&&tile!=p.drawn) return false;
        List<Integer> hand=new ArrayList<>(p.hand); List<Meld> melds=new ArrayList<>(p.melds);
        int remove=kind==Kind.CONCEALED_KONG?4:kind==Kind.KONG?3:1;
        for(int n=0;n<remove;n++) if(!hand.remove(Integer.valueOf(tile))) return false;
        if(kind==Kind.ADDED_KONG) {
            for(int i=0;i<melds.size();i++) if(melds.get(i).kind()==Kind.PONG&&melds.get(i).tiles().getFirst()==tile)
                melds.set(i,new Meld(kind,Collections.nCopies(4,tile),melds.get(i).source()));
        } else melds.add(new Meld(kind,Collections.nCopies(4,tile),seat));
        return handRules.waits(hand,melds,3).equals(p.lockedWaits);
    }
    public void act(int seat,Action action) {
        List<Action> legal=legalActions(seat);
        if(action==null||!legal.contains(action)) throw new IllegalArgumentException("Illegal action: seat="+seat+", "+action+", "+phase);
        Player p=players[seat];
        if(action.kind()!=Kind.WIN && legal.stream().anyMatch(a->a.kind()==Kind.WIN)) p.passedHu=true;
        if(phase==Phase.REACTION||phase==Phase.ROB_KONG) {
            responses.put(seat,action); if(responses.size()==offers.size()) resolve();
        } else switch(action.kind()) {
            case WIN -> finish(seat,-1,action.tile(),"自摸");
            case DISCARD, TING -> {
                p.hand.remove(Integer.valueOf(action.tile())); Collections.sort(p.hand); p.drawn=-1;
                boolean hidden=action.kind()==Kind.TING;
                if(hidden) { p.ting=true; p.lockedWaits=handRules.waits(p.hand,p.melds,3); }
                p.river.add(action.tile()); p.hiddenRiver.add(hidden); lastSource=seat; lastTile=action.tile();
                log(p.name+(hidden?"报听，盖牌":"打出"+Tiles.name(action.tile())));
                // Confirmed requirement: the declaration discard is hidden and cannot be claimed at all.
                if(hidden) { offers.clear(); responses.clear(); draw((seat+1)%4,false); }
                else offerDiscard(seat,action.tile());
            }
            case CONCEALED_KONG -> {
                for(int i=0;i<4;i++) p.hand.remove(Integer.valueOf(action.tile()));
                p.melds.add(new Meld(Kind.CONCEALED_KONG,Collections.nCopies(4,action.tile()),seat));
                recordGang(seat,seat,action.tile(),action.kind()); log(p.name+"暗杠"); draw(seat,true);
            }
            case ADDED_KONG -> {
                kongSeat=seat; kongTile=action.tile();
                for(int i=0;i<p.melds.size();i++) if(p.melds.get(i).kind()==Kind.PONG&&p.melds.get(i).tiles().getFirst()==action.tile()) kongIndex=i;
                offers.clear(); responses.clear(); phase=Phase.ROB_KONG;
                for(int other=0;other<4;other++) if(other!=seat&&canWin(other,action.tile(),false))
                    offers.put(other,List.of(new Action(Kind.WIN,action.tile())));
                log(p.name+"申请补杠"+Tiles.name(action.tile())); if(offers.isEmpty()) completeKong();
            }
            default -> throw new IllegalStateException("Unexpected action");
        }
        invalidate();
    }
    private boolean canWin(int seat,int tile,boolean self) {
        Player p=players[seat]; int point=Rules.points(tile);
        if(point<3 || !self&&point<6 || p.passedHu || rules.requireTing&&!p.ting) return false;
        if(p.ting&&!p.lockedWaits.contains(tile)) return false;
        return handRules.analyze(p.hand,p.melds,self?-1:tile).forWin(self).win();
    }
    private void offerDiscard(int source,int tile) {
        offers.clear(); responses.clear(); phase=Phase.REACTION;
        for(int seat=0;seat<4;seat++) if(seat!=source) {
            Player p=players[seat]; List<Action> options=new ArrayList<>();
            if(canWin(seat,tile,false)) options.add(new Action(Kind.WIN,tile));
            int count=Collections.frequency(p.hand,tile);
            if(count>=3&&!wall.isEmpty()&&kongKeepsWaits(seat,tile,Kind.KONG)) options.add(new Action(Kind.KONG,tile));
            if(count==2&&!p.ting) options.add(new Action(Kind.PONG,tile));
            if(!options.isEmpty()) offers.put(seat,List.copyOf(options));
        }
        if(offers.isEmpty()) resolve();
    }
    private static int priority(Action a) { return a.kind()==Kind.WIN?3:a.kind()==Kind.KONG||a.kind()==Kind.PONG?2:0; }
    private void resolve() {
        boolean rob=phase==Phase.ROB_KONG;
        int source=rob?kongSeat:lastSource, tile=rob?kongTile:lastTile;
        var choice=responses.entrySet().stream().filter(e->e.getValue().kind()!=Kind.PASS)
            .sorted(Comparator.<Map.Entry<Integer,Action>>comparingInt(e->-priority(e.getValue()))
                .thenComparingInt(e->Math.floorMod(e.getKey()-source,4))).findFirst();
        offers.clear(); responses.clear();
        if(choice.isEmpty()) { if(rob) completeKong(); else draw((source+1)%4,false); return; }
        int seat=choice.get().getKey(); Action action=choice.get().getValue();
        if(action.kind()==Kind.WIN) { finish(seat,source,tile,rob?"抢杠胡":"点炮胡"); return; }
        Player p=players[seat]; players[source].river.removeLast(); players[source].hiddenRiver.removeLast();
        lastSource=lastTile=-1;
        int count=action.kind()==Kind.KONG?4:3;
        for(int i=0;i<count-1;i++) p.hand.remove(Integer.valueOf(tile));
        Collections.sort(p.hand); p.drawn=-1; p.melds.add(new Meld(action.kind(),Collections.nCopies(count,tile),source));
        current=seat; phase=Phase.DISCARD; log(p.name+action.label()+Tiles.name(tile));
        if(action.kind()==Kind.KONG) { recordGang(seat,source,tile,Kind.KONG); draw(seat,true); }
    }
    private void completeKong() {
        Player p=players[kongSeat]; p.hand.remove(Integer.valueOf(kongTile));
        Meld old=p.melds.get(kongIndex); p.melds.set(kongIndex,new Meld(Kind.ADDED_KONG,Collections.nCopies(4,kongTile),old.source()));
        int seat=kongSeat; recordGang(seat,old.source(),kongTile,Kind.ADDED_KONG);
        kongSeat=kongIndex=kongTile=-1; draw(seat,true);
    }
    private void recordGang(int seat,int source,int tile,Kind kind) {
        Scoring.Gang event=new Scoring.Gang(seat,source,tile,kind,players[source].ting,wildTiles.contains(tile)); gangs.add(event);
        if(rules.mode==Rules.Mode.COIN) {
            List<Long> deltas=Scoring.deltas(Scoring.gang(rules,event));
            for(int i=0;i<4;i++) players[i].score=Math.addExact(players[i].score,deltas.get(i));
        }
    }
    private List<Scoring.Payment> settle(int winner,int source,List<Scoring.Payment> winEntries) {
        List<Scoring.Payment> ledger=new ArrayList<>();
        if(rules.mode==Rules.Mode.COIN) for(Scoring.Gang gang:gangs) ledger.addAll(Scoring.gang(rules,gang));
        else ledger.addAll(Scoring.settleGangs(rules,gangs,winner,source,source>=0&&players[source].ting));
        ledger.addAll(winEntries); List<Long> deltas=Scoring.deltas(ledger);
        for(int seat=0;seat<4;seat++) players[seat].score=Math.addExact(startingScores[seat],deltas.get(seat));
        return List.copyOf(ledger);
    }
    private void finishDraw() {
        List<Scoring.Payment> ledger=settle(-1,-1,List.of());
        result=new Result(-1,-1,-1,"荒庄",Scoring.deltas(ledger),List.of(),List.of(),ledger,Set.of());
        phase=Phase.OVER; offers.clear(); responses.clear(); log("牌墙已空，本局荒庄");
    }
    private void finish(int winner,int source,int tile,String method) {
        Player p=players[winner]; HandRules.Analysis analysis=Scoring.best(rules,handRules.analyze(p.hand,p.melds,source>=0?tile:-1),tile,source<0);
        List<Scoring.Payment> ledger=settle(winner,source,Scoring.win(rules,analysis,tile,winner,source,source>=0&&players[source].ting,dealer));
        List<Integer> hand=new ArrayList<>(p.hand); if(source>=0) hand.add(tile); Collections.sort(hand);
        result=new Result(winner,source,tile,method,Scoring.deltas(ledger),hand,p.melds,ledger,analysis.patterns());
        phase=Phase.OVER; offers.clear(); responses.clear(); log(p.name+method+"（"+Rules.points(tile)+"点），本局结束");
    }
    public View view(int viewer) {
        if(viewer<0||viewer>=4) throw new IllegalArgumentException("Invalid viewer");
        boolean reveal=phase==Phase.OVER;
        List<PlayerView> out=new ArrayList<>();
        for(int seat=0;seat<4;seat++) {
            Player p=players[seat]; List<Meld> melds=new ArrayList<>(); List<Integer> river=new ArrayList<>();
            for(Meld m:p.melds) melds.add(!reveal&&m.kind()==Kind.CONCEALED_KONG&&viewer!=seat?new Meld(m.kind(),List.of(-1,-1,-1,-1),m.source()):m);
            for(int i=0;i<p.river.size();i++) river.add(!reveal&&viewer!=seat&&i<p.hiddenRiver.size()&&p.hiddenRiver.get(i)?-1:p.river.get(i));
            out.add(new PlayerView(p.name,reveal||viewer==seat?p.hand:List.of(),p.hand.size(),melds,river,p.score,reveal||viewer==seat?p.drawn:-1,p.ting));
        }
        int visibleLast=lastTile;
        if(!reveal&&lastSource>=0&&lastSource!=viewer) {
            Player p=players[lastSource]; if(!p.hiddenRiver.isEmpty()&&p.hiddenRiver.getLast()) visibleLast=-1;
        }
        return new View(revision,viewer,round,dealer,current,phase,wall.size(),out,legalActions(viewer),events,lastSource,visibleLast,result,wildTiles,rules.title());
    }
    public void checkInvariants() {
        List<Integer> all=new ArrayList<>(wall); long totalScore=0;
        for(int seat=0;seat<4;seat++) {
            Player p=players[seat]; all.addAll(p.hand); all.addAll(p.river); totalScore=Math.addExact(totalScore,p.score);
            for(Meld m:p.melds) all.addAll(m.tiles());
            if(phase!=Phase.OVER) {
                int expected=seat==current&&(phase==Phase.DISCARD||phase==Phase.ROB_KONG)?14:13;
                if(p.hand.size()+3*p.melds.size()!=expected) throw new IllegalStateException("Hand size at seat "+seat);
            }
        }
        if(all.size()!=136||Arrays.stream(Tiles.counts(all)).anyMatch(n->n!=4)) throw new IllegalStateException("Tile conservation");
        if(totalScore!=0) throw new IllegalStateException("Score conservation");
    }
}
