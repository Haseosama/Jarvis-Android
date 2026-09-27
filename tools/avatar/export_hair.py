"""Writes the hairstyles the user can choose (Settings > Appearance > Coiffure) as app assets (format JHR1), from the styles
prepare_hair.py took out of their collections. The app fits the chosen one onto the chosen head when it loads it (avatar/HairStyle.kt,
the same radial fit as hair_fit.py): one file serves every face.

Usage: python export_hair.py <folder of prepare_hair.py's .npz> [<assets folder>]
Needs numpy and scipy.

JHR1: "JHR1", vertex count, triangle count, AZ, EL (int32); the source skull's centre (3 float32); the positions' box: min and step
(3 + 3 float32); the source skull's radius on the grid of directions (EL x AZ float32); the positions (3 uint16 each, in the box); one
byte per vertex, its strand's key (the strand's shade and whether it may turn grey), padded to 4 bytes; the triangles (3 uint16 each).
"""
import json, os, struct, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hair_fit

# the styles offered, and their names in the settings (the heads weigh 20 000 to 26 000 triangles: a style stays under MAX_TRIS so
# that any head with any style is within the avatar's budget of 46 451)
STYLES = [
    ("women09_00", "Carré court", "Short bob"), ("women09_05", "Carré frange", "Bob with bangs"),
    ("women09_02", "Dégradé à frange", "Layered with bangs"), ("women09_01", "Longs lisses", "Long and straight"),
    ("women09_04", "Longs ondulés", "Long and wavy"), ("women09_03", "Longs tressés", "Long with braids"),
    ("women09_07", "Frange de côté", "Side fringe"), ("women09_10", "Carré plongeant", "Long bob"),
    ("women09_09", "Chignon", "Bun"), ("women09_08", "Tirés en arrière", "Pulled back"), ("women09_11", "Couettes", "Pigtails"),
    ("men09_05", "Courte dégradée", "Short fade"), ("men09_02", "Courte texturée", "Short textured"), ("men09_00", "Bouclée", "Curly"),
    ("men09_03", "Banane", "Quiff"), ("men09_04", "Mèche sur le côté", "Side swept"), ("men09_11", "Coiffée en arrière", "Slicked back"),
    ("men09_06", "Mèche décoiffée", "Tousled"), ("men09_08", "Raie au milieu", "Middle part"), ("men09_10", "Mi-longue", "Medium length"),
    ("men09_09", "Frange arrondie", "Rounded fringe"), ("men09_12", "Frange longue", "Long fringe"),
    ("men09_07", "Queue de cheval", "Ponytail"),
]
MAX_TRIS = 20_500


def thin(V, F, max_tris, seed=3):
    """Whole strands left out at random until the style is under [max_tris] (a thick head of hair shows no gap)."""
    if len(F) <= max_tris:
        return V, F
    lab = hair_fit.strands(F, len(V))
    flab = lab[F[:, 0]]
    rng = np.random.default_rng(seed)
    order = rng.permutation(lab.max() + 1)
    cnt = np.bincount(flab, minlength=lab.max() + 1)
    drop = np.zeros(lab.max() + 1, bool)
    total = len(F)
    for s in order:
        if total <= max_tris:
            break
        if cnt[s] == 0:
            continue
        drop[s] = True; total -= cnt[s]
    F = F[~drop[flab]]
    used = np.unique(F); remap = -np.ones(len(V), np.int64); remap[used] = np.arange(len(used))
    return V[used], remap[F]


def write(npz, path):
    d = np.load(npz)
    HV, HF = d["head_v"].astype(float), d["head_f"].astype(np.int64)
    V, F = d["hair_v"].astype(float), d["hair_f"].astype(np.int64)
    cs = hair_fit.centre(HV)
    R = hair_fit.radius_map(HV, HF, cs).astype(np.float32)
    # the parts buried in the source head (roots pushed into the scalp, the inner side of a cap) were never seen: out, before the fit
    # could bring them out onto the new head's face
    r, az, el = hair_fit._dirs(V, cs)
    inside = r - hair_fit._sample(R, az, el) < -0.02
    F = F[~inside[F].all(1)]
    used = np.unique(F); remap = -np.ones(len(V), np.int64); remap[used] = np.arange(len(used))
    V, F = V[used], remap[F]
    V, F = thin(V, F, MAX_TRIS)
    assert len(V) < 65536
    lo = V.min(0); step = np.maximum((V.max(0) - lo) / 65535.0, 1e-9)
    q = np.round((V - lo) / step).astype(np.uint16)
    lab = hair_fit.strands(F, len(V))
    key = np.random.default_rng(11).integers(0, 256, lab.max() + 1).astype(np.uint8)[lab]
    buf = bytearray(b"JHR1") + struct.pack("<4i", len(V), len(F), hair_fit.AZ, hair_fit.EL)
    buf += struct.pack("<9f", *cs, *lo, *step) + R.tobytes() + q.tobytes()
    kb = key.tobytes(); buf += kb + b"\0" * ((4 - len(kb) % 4) % 4)
    buf += F.astype(np.uint16).tobytes()
    open(path, "wb").write(bytes(buf))
    return len(V), len(F)


if __name__ == "__main__":
    src = sys.argv[1]
    root = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets")
    out = os.path.join(root, "avatar", "hair")
    os.makedirs(out, exist_ok=True)
    listing = []
    for sid, fr, en in STYLES:
        nv, nf = write(os.path.join(src, sid + ".npz"), os.path.join(out, sid + ".bin"))
        listing.append(dict(id=sid, fr=fr, en=en, women=sid.startswith("women")))
        print(sid, fr, nv, "vertices", nf, "triangles", os.path.getsize(os.path.join(out, sid + ".bin")), "bytes")
    json.dump(listing, open(os.path.join(out, "styles.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
