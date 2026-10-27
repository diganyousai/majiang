"""Generate independent fixtures from the original mahjong 2.0.0 library."""
import random
from pathlib import Path
from mahjong.agari import Agari
from mahjong.shanten import Shanten

rng = random.Random(20261018)
rows = []
wall = [t for t in range(34) for _ in range(4)]


def record(hand, opened):
    counts = [hand.count(t) for t in range(34)]
    distance = Shanten.calculate_shanten(counts, use_chiitoitsu=not opened, use_kokushi=not opened)
    won = len(hand) % 3 == 2 and Agari.is_agari(counts)
    rows.append(f"{''.join(map(str, counts))}\t{int(opened)}\t{distance}\t{int(won)}")


for _ in range(6000):
    size = rng.choice([1, 2, 4, 5, 7, 8, 10, 11, 13, 14])
    record(rng.sample(wall, size), size < 13 or rng.choice([True, False]))

for _ in range(4000):
    melds = rng.randrange(5)
    pair = rng.randrange(34)
    hand = [pair, pair]
    for _ in range(melds):
        if rng.choice([True, False]):
            start = rng.randrange(3) * 9 + rng.randrange(7)
            hand += [start, start + 1, start + 2]
        else:
            hand += [rng.randrange(34)] * 3
    if max(hand.count(t) for t in set(hand)) <= 4:
        record(hand, melds < 4)
        record(hand[:-1], melds < 4)

for _ in range(500):
    record([t for t in rng.sample(range(34), 7) for _ in range(2)], False)
    orphans = [0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33]
    record(orphans + [rng.choice(orphans)], False)

target = Path(__file__).resolve().parents[1] / "src/test/resources/oracle.tsv"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text("\n".join(rows) + "\n", encoding="ascii")
print(f"Generated {len(rows)} mahjong 2.0.0 reference cases")
