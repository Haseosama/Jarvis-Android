"""Builds Haseo's body for augmented reality (app/src/main/assets/avatar/body_mesh.bin) from "Anatomy Basemesh Human Male Body Model
Sculpture" by zeroran (https://sketchfab.com/3d-models/anatomy-basemesh-human-male-body-model-sculpture-8c49edd7e9bd4dc7ba3c4b493b00d7ab),
CC BY 4.0, downloaded from Sketchfab as glTF.

Usage: python export_body.py <folder with scene.gltf and scene.bin> ../../app/src/main/assets/avatar/body_mesh.bin
Needs numpy and fast-simplification (quadric edge collapse, pip install fast-simplification).

The sculpt is 1.13 million triangles in eleven pieces, 1.80 m tall, standing in an A-pose and looking along +z. It is welded, put in the
head's units (its head, chin to crown, as tall as the app's: 2; its chin at the app's chin, its neck under the app's neck, see ar/ArBody.kt),
its own head cut off a little above its chin (the app's head is drawn over it there), the top of its neck narrowed a little so it stays
inside the app's jaw, then simplified once per polygon level to as many triangles as the head has at that level.
Each vertex gets the garment under it (jacket, trousers, belt, shoes: a bodysuit over the sculpted muscles) with how much of the
collar or cuff colour and of bare skin is blended over it and how much it
follows the arm's bones (the upper arm, the forearm with the hand), for the app to pose the arms; each level gets the web of the dark
look: the edges and nodes of the same surface simplified four times more, laid on its own vertices.

File (little-endian): "JHB2", int32 levels; per side (left, then right; x > 0 is the figure's left) the shoulder, elbow and wrist,
3 float32 each; then per level: int32 vertices V, int32 triangles T, V x 3 float32 positions, V x 3 int8 normals (x127), V uint8 part, V uint8 trim, V uint8 bare (x255),
V int8 side (-1, 0, 1), V uint8 arm weight, V uint8 forearm weight (x255), T x 3 uint16 vertex indices, int32 web edges E, E x (int32
triangle, uint16 a, uint16 b), int32 nodes N, N x (int32 triangle, uint16 vertex).
"""
import struct, sys
import numpy as np
import fast_simplification as fs
from gltf_scene import Scene

SRC, OUT = sys.argv[1], sys.argv[2]

# the sculpt, in metres: its chin, crown, the middle of its neck front to back (under the chin), and its arm's joints (left side)
CHIN, CROWN, NECK_Z = 1.555, 1.795, -0.052
SHOULDER, ELBOW, WRIST = (0.215, 1.445, -0.08), (0.39, 1.255, -0.11), (0.55, 1.135, -0.02)
# the app's head: its chin's height and its neck's middle front to back, in its units (half-heights)
APP_CHIN, APP_NECK_Z = -0.90, -0.50
K = 2.0 / (CROWN - CHIN)
CUT = -0.80                     # the sculpt's head is cut off above this
COLLAR = -0.96                  # where the bodysuit's neck ends, just under the app's chin, in the app's units
# the head's triangle counts at Eco, Léger, Standard and Haute définition (Ultra is Haute définition here, as before)
LEVELS = [20_588, 27_712, 37_161, 84_555]
JACKET, TRIM, TROUSERS, BELT, SHOES, SKIN = range(6)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def to_units(p):
    p = np.asarray(p, dtype=np.float64)
    return np.stack([p[..., 0] * K, (p[..., 1] - CHIN) * K + APP_CHIN, (p[..., 2] - NECK_Z) * K + APP_NECK_Z], axis=-1)


def weld(P, F, eps=1e-5):
    _, idx, inv = np.unique(np.round(P / eps).astype(np.int64), axis=0, return_index=True, return_inverse=True)
    F = inv.reshape(-1)[F]
    keep = (F[:, 0] != F[:, 1]) & (F[:, 1] != F[:, 2]) & (F[:, 0] != F[:, 2])
    return P[idx], F[keep]


def compact(P, F):
    used = np.unique(F)
    remap = -np.ones(len(P), dtype=np.int64); remap[used] = np.arange(len(used))
    return P[used], remap[F]


def normals(P, F):
    n = np.cross(P[F[:, 1]] - P[F[:, 0]], P[F[:, 2]] - P[F[:, 0]])
    N = np.zeros_like(P)
    for j in range(3): np.add.at(N, F[:, j], n)
    return N / np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-12)


# the sculpt, welded, in the app's units, its head cut off and the top of its neck narrowed towards the neck's middle
P, F = [], []
base = 0
for prim in Scene(SRC).primitives():
    P.append(prim["P"]); F.append(prim["F"] + base); base += len(prim["P"])
P, F = weld(np.vstack(P).astype(np.float64), np.vstack(F).astype(np.int64))
P = to_units(P)
squeeze = 1.0 - 0.14 * smoothstep(-1.25, CUT, P[:, 1]) * (np.abs(P[:, 0]) < 1.0)
P[:, 0] *= squeeze
P[:, 2] = APP_NECK_Z + (P[:, 2] - APP_NECK_Z) * squeeze
F = F[(P[F, 1] < CUT).all(axis=1)]
P, F = compact(P, F)
print("sculpt:", len(F), "triangles below the cut")

S_, E_, W_ = to_units(SHOULDER), to_units(ELBOW), to_units(WRIST)


def arm_weights(Q):
    """How much each point follows the upper arm (arm) and, of that, the forearm (fore), and its side: from its place along the arm."""
    side = np.where(Q[:, 0] >= 0, 1, -1)
    q = Q.copy(); q[:, 0] = np.abs(q[:, 0])
    up = (E_ - S_) / np.linalg.norm(E_ - S_)
    along = (q - S_) @ up
    radial = np.linalg.norm((q - S_) - np.outer(along, up), axis=1)
    # the arm from a little inside the shoulder joint out, as long as the point is near the arm's line (not the side of the chest)
    arm = smoothstep(-0.25, 0.45, along) * (1 - smoothstep(0.75, 1.15, radial) * (1 - smoothstep(0.8, 1.3, along)))
    # only the arm itself: out beyond the hips, or high up by the shoulder (the A-pose's hands hang beside the thighs, well apart)
    arm *= (q[:, 0] > to_units((0.25, 0, 0))[0]) | (q[:, 1] > to_units((0, 1.30, 0))[1])
    fd = (W_ - E_) / np.linalg.norm(W_ - E_)
    fore = smoothstep(-0.30, 0.30, (q - E_) @ fd)
    arm = np.where(q[:, 0] < 0.9, arm * smoothstep(0.55, 0.9, q[:, 0]), arm)   # nothing in the middle of the chest
    return side * (arm > 0), arm, fore * (arm > 0)


def parts(Q, side, arm, fore):
    """What each vertex wears: the garment under it, then how much of the collar or cuff colour (trim) and of bare skin (bare) shows
    over it, both 0..1 and blended so no edge follows the triangles."""
    x, y, z = Q[:, 0], Q[:, 1], Q[:, 2]
    part = np.full(len(Q), JACKET)
    hand_d = (W_ - E_) / np.linalg.norm(W_ - E_)
    q = Q.copy(); q[:, 0] = np.abs(q[:, 0])
    past_wrist = (q - W_) @ hand_d
    on_arm = arm > 0.5
    legs = y < to_units((0, 0.955, 0))[1]
    part[legs & ~on_arm] = TROUSERS
    belt = (y >= to_units((0, 0.955, 0))[1]) & (y < to_units((0, 0.99, 0))[1]) & ~on_arm
    part[belt] = BELT
    part[(y < to_units((0, 0.085, 0))[1])] = SHOES
    # the bodysuit's neck up to just under the chin, ending in a thin ring of the theme's colour: no skin of its own on the neck, so
    # the head's neck (drawn over it) only has to fade into cloth, never into another skin
    tz = np.clip((z - APP_NECK_Z) / 0.55, -1.0, 1.0)
    s = y - (COLLAR - 0.05 * tz)
    ring = smoothstep(-0.26, -0.08, s)
    bare = np.zeros(len(Q))
    # the cuffs, then the hands out of them
    hand = smoothstep(-0.06, 0.04, past_wrist) * on_arm
    cuff = np.maximum(smoothstep(-0.26, -0.14, past_wrist) * on_arm - hand, 0.0)
    return part, np.maximum(ring, cuff), np.maximum(bare, hand)


def web(Pl, Fl, T):
    """The web of the dark look on a level: the edges and nodes of the level simplified to about [T] triangles, each laid on the level's
    nearest vertices and carried by a triangle round them (one edge and one node at most per triangle)."""
    pc, fc = fs.simplify(Pl.astype(np.float32), Fl.astype(np.int32), target_reduction=1 - T / len(Fl))
    # nearest level vertex to each coarse one, through a grid
    cell = 0.2
    keys = np.floor(Pl / cell).astype(np.int64)
    grid = {}
    for i, k in enumerate(map(tuple, keys)): grid.setdefault(k, []).append(i)
    near = np.zeros(len(pc), dtype=np.int64)
    for c, p in enumerate(pc):
        k = np.floor(p / cell).astype(np.int64)
        cand = [i for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1) for i in grid.get((k[0] + dx, k[1] + dy, k[2] + dz), [])]
        cand = np.array(cand)
        near[c] = cand[np.argmin(np.linalg.norm(Pl[cand] - p, axis=1))]
    incident = [[] for _ in range(len(Pl))]
    for t, f in enumerate(Fl):
        for v in f: incident[v].append(t)
    edges = set()
    for f in fc:
        for a, b in ((f[0], f[1]), (f[1], f[2]), (f[2], f[0])):
            a, b = near[a], near[b]
            if a != b: edges.add((min(a, b), max(a, b)))
    used_e = np.zeros(len(Fl), dtype=bool); used_n = np.zeros(len(Fl), dtype=bool)
    E, N = [], []
    for a, b in sorted(edges):
        for t in incident[a] + incident[b]:
            if not used_e[t]: used_e[t] = True; E.append((t, a, b)); break
    for v in sorted(set(near.tolist())):
        for t in incident[v]:
            if not used_n[t]: used_n[t] = True; N.append((t, v)); break
    return E, N


out = bytearray(b"JHB2") + struct.pack("<i", len(LEVELS))
for s in (1, -1):
    for j in (S_, E_, W_): out += struct.pack("<3f", j[0] * s, j[1], j[2])
for T in LEVELS:
    pl, fl = fs.simplify(P.astype(np.float32), F.astype(np.int32), target_reduction=1 - T / len(F))
    pl, fl = compact(pl.astype(np.float64), fl.astype(np.int64))
    nl = normals(pl, fl)
    side, arm, fore = arm_weights(pl)
    part, trim, bare = parts(pl, side, arm, fore)
    E, N = web(pl, fl, max(2000, T // 4))
    print(f"level {T}: {len(pl)} vertices, {len(fl)} triangles, web {len(E)} edges {len(N)} nodes")
    assert len(pl) < 65536
    out += struct.pack("<ii", len(pl), len(fl))
    out += pl.astype("<f4").tobytes()
    out += np.round(nl * 127).astype(np.int8).tobytes()
    out += part.astype(np.uint8).tobytes()
    out += np.round(trim * 255).astype(np.uint8).tobytes()
    out += np.round(bare * 255).astype(np.uint8).tobytes()
    out += side.astype(np.int8).tobytes()
    out += np.round(arm * 255).astype(np.uint8).tobytes()
    out += np.round(fore * 255).astype(np.uint8).tobytes()
    out += fl.astype("<u2").tobytes()
    out += struct.pack("<i", len(E)) + b"".join(struct.pack("<iHH", *e) for e in E)
    out += struct.pack("<i", len(N)) + b"".join(struct.pack("<iH", *n) for n in N)
open(OUT, "wb").write(out)
print("wrote", OUT, len(out), "bytes")
