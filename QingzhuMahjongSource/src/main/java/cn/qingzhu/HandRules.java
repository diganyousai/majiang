package cn.qingzhu;

import java.util.*;
import static cn.qingzhu.Rules.Pattern.*;

/** Shape analysis with optional wild cards and distinct honor groups. Pure and cached. */
public final class HandRules {
    public record Variant(Set<Rules.Pattern> patterns, int windGroups) {
        public Variant { patterns = Set.copyOf(patterns); }
    }
    public record Analysis(boolean win, Set<Rules.Pattern> patterns, int windGroups, Set<Variant> variants) {
        public Analysis { patterns = Set.copyOf(patterns); variants = Set.copyOf(variants); }
        public Analysis(boolean win, Set<Rules.Pattern> patterns, int windGroups) {
            this(win, patterns, windGroups, Set.of(new Variant(patterns,windGroups)));
        }
        public Analysis forWin(boolean selfDraw) {
            if (selfDraw || !win || variants.stream().allMatch(v -> v.windGroups()==0)) return this;
            Set<Variant> allowed=new HashSet<>();
            EnumSet<Rules.Pattern> allowedPatterns=EnumSet.noneOf(Rules.Pattern.class);
            for (Variant v:variants) if (v.windGroups()==0) { allowed.add(v); allowedPatterns.addAll(v.patterns()); }
            return allowed.isEmpty()?NO:new Analysis(true,allowedPatterns,0,allowed);
        }
    }
    private static final Analysis NO = new Analysis(false,Set.of(),0);
    private static final Set<Integer> ORPHAN_TILES = Set.of(0,8,9,17,18,26,27,28,29,30,31,32,33);
    private final Rules rules;
    private final Set<Integer> wildTiles;
    private final Map<String,Analysis> cache = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Analysis> e) { return size() > 12000; }
    };
    public HandRules(Rules rules, Set<Integer> wildTiles) { this.rules=rules; this.wildTiles=Set.copyOf(wildTiles); }
    public Analysis analyze(List<Integer> hand, List<Game.Meld> melds, int extra) {
        List<Integer> full = new ArrayList<>(hand); if (extra >= 0) full.add(extra);
        if (full.size() != 14 - 3*melds.size()) return NO;
        int[] original = Tiles.counts(full);
        int[] physical = original.clone();
        for (Game.Meld m : melds) for (int t : m.tiles()) physical[t]++;
        if (Arrays.stream(physical).anyMatch(c -> c > 4)) return NO;
        String key = Arrays.toString(original)+melds.toString();
        Analysis found = cache.get(key); if (found != null) return found;
        Analysis result = compute(full,original,melds); cache.put(key,result); return result;
    }
    private Analysis compute(List<Integer> full, int[] original, List<Game.Meld> melds) {
        EnumSet<Rules.Pattern> patterns = EnumSet.noneOf(Rules.Pattern.class);
        boolean specialAllowed = rules.wild == Rules.Wild.NONE;
        boolean pairs = specialAllowed && melds.isEmpty() && Arrays.stream(original).allMatch(n -> n%2==0);
        boolean orphans = specialAllowed && melds.isEmpty() && ORPHAN_TILES.stream().allMatch(t -> original[t]>0)
            && Arrays.stream(ORPHAN_TILES.stream().mapToInt(t -> original[t]).toArray()).sum()==14;
        if (pairs) patterns.add(Arrays.stream(original).anyMatch(n -> n==4) ? LUXURY_PAIRS : SEVEN_PAIRS);
        if (orphans) patterns.add(ORPHANS);
        int[] counts = original.clone(); int wild=0;
        for (int t : wildTiles) { wild+=counts[t]; counts[t]=0; }
        if (!pairs && !orphans && wild==0 && !rules.windMouth && !Tiles.win(full,melds.size(),-1)) return NO;
        Set<Integer> decompositions = new HashSet<>();
        if (!orphans) {
            Map<String,Set<Integer>> memo = new HashMap<>();
            for (int t=0;t<34;t++) {
                if (counts[t]>=2) { counts[t]-=2; decompositions.addAll(groups(counts,wild,4-melds.size(),memo)); counts[t]+=2; }
                if (counts[t]>=1 && wild>=1) { counts[t]--; decompositions.addAll(groups(counts,wild-1,4-melds.size(),memo)); counts[t]++; }
            }
            if (wild>=2) decompositions.addAll(groups(counts,wild-2,4-melds.size(),memo));
        }
        if (!pairs && !orphans && decompositions.isEmpty()) return NO;
        boolean allPongs = decompositions.stream().anyMatch(m -> (m & 512)!=0);
        boolean dragon = decompositions.stream().anyMatch(m -> ((m&7)==7)||((m&56)==56)||((m&448)==448));
        int wind = decompositions.stream().mapToInt(m -> m>>>10).max().orElse(0);
        if (allPongs && !pairs && !orphans) patterns.add(ALL_PONGS);
        if (dragon) patterns.add(DRAGON);
        List<Integer> all = new ArrayList<>(full); for (Game.Meld m : melds) all.addAll(m.tiles());
        boolean honor=false; Set<Integer> suits = new HashSet<>();
        for (int t : all) if (!wildTiles.contains(t)) { if(t>=27) honor=true; else suits.add(t/9); }
        if (suits.isEmpty() && honor) patterns.add(ALL_HONORS);
        if (suits.size()==1) patterns.add(honor ? MIXED : PURE);
        // A score must use one actual decomposition, not combine bonuses from incompatible ones.
        EnumSet<Rules.Pattern> common=EnumSet.copyOf(patterns);
        common.removeAll(Set.of(ALL_PONGS,DRAGON,SEVEN_PAIRS,LUXURY_PAIRS,ORPHANS));
        Set<Variant> variants=new HashSet<>();
        for(int mask:decompositions) {
            EnumSet<Rules.Pattern> item=common.clone();
            if((mask&512)!=0) item.add(ALL_PONGS);
            if((mask&7)==7||(mask&56)==56||(mask&448)==448) item.add(DRAGON);
            variants.add(new Variant(item,mask>>>10));
        }
        if(pairs) { EnumSet<Rules.Pattern> item=common.clone(); item.add(patterns.contains(LUXURY_PAIRS)?LUXURY_PAIRS:SEVEN_PAIRS); variants.add(new Variant(item,0)); }
        if(orphans) { EnumSet<Rules.Pattern> item=common.clone(); item.add(ORPHANS); variants.add(new Variant(item,0)); }
        return new Analysis(true,patterns,wind,variants);
    }
    // bit 0..8: complete 123/456/789 sequences per suit; bit9: all triplets; upper bits: wind-mouth count.
    private Set<Integer> groups(int[] counts,int wild,int needed,Map<String,Set<Integer>> memo) {
        int first=-1, total=wild;
        for(int t=0;t<34;t++) { total+=counts[t]; if(first<0 && counts[t]>0) first=t; }
        if(total!=needed*3) return Set.of();
        if(needed==0) return Set.of(512);
        if(first<0) return Set.of(512); // all-wild groups: shape valid; no artificial pattern bonuses
        String key=Arrays.toString(counts)+":"+wild+":"+needed;
        Set<Integer> saved=memo.get(key); if(saved!=null) return saved;
        Set<Integer> out=new HashSet<>();
        for(int n=1;n<=Math.min(3,counts[first]);n++) if(3-n<=wild) {
            counts[first]-=n; out.addAll(groups(counts,wild-(3-n),needed-1,memo)); counts[first]+=n;
        }
        if(first<27) for(int start=Math.max(first/9*9,first-2);start<=Math.min(first,first/9*9+6);start++) {
            int bit=start%9%3==0 ? 1<<(start/9*3+start%9/3) : 0;
            consumeGroup(counts,wild,needed,new int[]{start,start+1,start+2},first,bit,0,memo,out);
        }
        if(rules.windMouth && first>=27) {
            int lo=first<31?27:31, hi=first<31?31:34;
            for(int a=lo;a<hi;a++) for(int b=a+1;b<hi;b++) for(int c=b+1;c<hi;c++)
                if(a==first||b==first||c==first) consumeGroup(counts,wild,needed,new int[]{a,b,c},first,0,1,memo,out);
        }
        Set<Integer> result=Set.copyOf(out); memo.put(key,result); return result;
    }
    private void consumeGroup(int[] counts,int wild,int needed,int[] group,int first,int bit,int wind,
                              Map<String,Set<Integer>> memo,Set<Integer> out) {
        counts[first]--;
        int[] others=Arrays.stream(group).filter(t->t!=first).toArray();
        for(int mask=0;mask<4;mask++) {
            int cost=Integer.bitCount(mask); if(cost>wild) continue;
            boolean valid=true;
            for(int i=0;i<2;i++) if((mask&(1<<i))==0 && counts[others[i]]==0) valid=false;
            if(!valid) continue;
            for(int i=0;i<2;i++) if((mask&(1<<i))==0) counts[others[i]]--;
            for(int m:groups(counts,wild-cost,needed-1,memo)) out.add(((m&511)|bit)|(((m>>>10)+wind)<<10));
            for(int i=0;i<2;i++) if((mask&(1<<i))==0) counts[others[i]]++;
        }
        counts[first]++;
    }
    public Set<Integer> waits(List<Integer> hand,List<Game.Meld> melds,int minPoints) {
        Set<Integer> result=new TreeSet<>();
        for(int t=0;t<34;t++) if(Rules.points(t)>=minPoints && analyze(hand,melds,t).win()) result.add(t);
        return Set.copyOf(result);
    }
}
