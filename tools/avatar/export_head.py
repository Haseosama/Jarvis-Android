"""Builds the avatar's head asset (app/src/main/assets/avatar/head_mesh.bin, format JHM3) from a scanned human head.

Usage: python export_head.py <path to a clone of FatihMakes/Mark-LIV> <path to LeePerrySmith.glb>
Needs numpy. Sources (see app/src/main/assets/avatar/NOTICE.txt):
  * LeePerrySmith.glb, "Infinite, 3D Head Scan" by Lee Perry-Smith (Infinite Realities), CC BY 3.0, from the three.js examples
    (https://github.com/mrdoob/three.js/tree/dev/examples/models/gltf/LeePerrySmith). It gives the real shape of the head, the
    ears, the nose, the jaw and the neck.
  * Mark-LIV (CC BY-NC 4.0): the landmark rings of MediaPipe's face model (eyes, brows, lips), which are moved onto the scan so
    the same animation (blinks, gaze, lip-sync, jaw) drives it.

What is built:
  * the scan, normalised so the crown is at y = +1, the chin at y = -1 and the eyes near y = 0, cut at the base of the neck;
  * the landmark rings, snapped to the nearest scan vertices, and the jaw / brow / lip weights;
  * per-vertex ambient occlusion from the curvature;
  * a hair shell over the scalp, about 700 strands, fringe locks, glowing circuit traces, a forehead chip and seams.
Layout of the vertices: head and neck | hair shell | strand points | circuit points.
Faces: group 0 neck (drawn first), 1 face / ears / sides, 1.2 cranium (no crease lines), 2 hair.
"""
import json, os, struct, sys
import numpy as np

MARK_LIV, GLB = sys.argv[1], sys.argv[2]
sys.path.insert(0, os.path.join(MARK_LIV, "core"))
import avatar_mesh as am


def load_glb(path):
    b = open(path, "rb").read()
    clen, _ = struct.unpack("<I4s", b[12:20])
    j = json.loads(b[20:20 + clen])
    off = 20 + clen
    blen, _ = struct.unpack("<I4s", b[off:off + 8])
    bin_ = b[off + 8: off + 8 + blen]

    def acc(i):
        a = j["accessors"][i]; bv = j["bufferViews"][a["bufferView"]]
        start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        comp = {5126: np.float32, 5125: np.uint32, 5123: np.uint16}[a["componentType"]]
        n = {"SCALAR": 1, "VEC2": 2, "VEC3": 3}[a["type"]]
        arr = np.frombuffer(bin_, dtype=comp, count=a["count"] * n, offset=start)
        return arr.reshape(-1, n) if n > 1 else arr

    p = j["meshes"][0]["primitives"][0]
    return acc(p["attributes"]["POSITION"]).astype(np.float64), acc(p["indices"]).reshape(-1, 3).astype(np.int64)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def vertex_normals(V, F):
    n = np.zeros_like(V)
    fn = np.cross(V[F[:, 1]] - V[F[:, 0]], V[F[:, 2]] - V[F[:, 0]])
    for k in range(3):
        np.add.at(n, F[:, k], fn)
    return n / np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)


# ---- 1. the scan, normalised: crown +1, chin -1, nose tip at the depth of the mask's, cut at the base of the neck -------------
V, F = load_glb(GLB)
crown = V[:, 1].max()
nose_tip = V[np.argmax(V[:, 2])]
front_pts = (V[:, 2] > 0.55 * nose_tip[2]) & (np.abs(V[:, 0]) < 0.9)
chin_y = V[np.argmin(np.where(front_pts, V[:, 1], 9)), 1]
scale = 2.0 / (crown - chin_y)
V[:, 0] *= scale
V[:, 1] = (V[:, 1] - (crown + chin_y) / 2) * scale
V[:, 2] = V[:, 2] * scale - (nose_tip[2] * scale - 0.6796)
CUT = -1.45
keep_v = V[:, 1] > CUT
keep_f = keep_v[F].all(axis=1)
used = np.unique(F[keep_f].ravel())
remap = -np.ones(len(V), dtype=np.int64)
remap[used] = np.arange(len(used))
V, F = V[used], remap[F[keep_f]]
N = vertex_normals(V, F)
n_base = len(V)
print("scan vertices", n_base, "faces", len(F))

# ---- 2. landmark rings: MediaPipe's rings moved onto the scan ------------------------------------------------------------
mask = am.build_head()
Vm = mask["verts"]
rings = {}
ring_xy = {}
# Where the mask's features belong on this scan, measured on it: the face's midline is at x = -0.035 (nose tip, mouth), the eyes are
# closer together than the mask's (x scaled by 0.85), the closed eyelids meet at y = 0.02 (the mask has 0.08) and the lips at y = -0.49
# (the mask has -0.55). Placing them by the mask's own coordinates put the eyes and the mouth about 0.06 too high and too low.
FACE_X0, FACE_SX = -0.035, 0.85
FEATURE_DY = {"eye_l": -0.062, "eye_r": -0.062, "brow_l": -0.062, "brow_r": -0.062, "lips_out": 0.057, "lips_in": 0.057}
front_ids = np.flatnonzero(V[:, 2] > 0.25)
for name, idx in mask["landmarks"].items():
    out = []
    for i in idx:
        x, y = FACE_X0 + FACE_SX * Vm[i, 0], Vm[i, 1] + FEATURE_DY[name]
        d2 = (V[front_ids, 0] - x) ** 2 + (V[front_ids, 1] - y) ** 2
        near = front_ids[np.argsort(d2)[:4]]
        out.append(int(near[np.argmax(V[near, 2])]))  # the outermost of the closest
    rings[name] = np.array(out, dtype=np.int32)
    ring_xy[name] = np.array([[FACE_X0 + FACE_SX * Vm[i, 0], Vm[i, 1] + FEATURE_DY[name]] for i in idx])
lips_out_c = V[rings["lips_out"]].mean(axis=0)
lip_centre = lips_out_c
mouth_y = lips_out_c[1]

# ---- 3. rig weights ------------------------------------------------------------------------------------------------------
jaw = np.clip((mouth_y - V[:, 1]) / (mouth_y - (-1.0)), 0.0, 1.0) ** 0.8
jaw *= smoothstep(-0.05, 0.45, V[:, 2])           # the jaw hinges between the ears: what is behind them stays
jaw *= 1.0 - smoothstep(-1.02, -1.22, V[:, 1])    # the neck never moves
jaw[rings["lips_in"][:10]] = 1.0                  # the lower inner lip leads the jaw
jaw[rings["lips_out"][:10]] = 0.95
brow_y = V[np.concatenate([rings["brow_l"], rings["brow_r"]]), 1].mean()
brow = np.exp(-((V[:, 1] - brow_y) / 0.115) ** 2) * np.clip(V[:, 2] / 0.35, 0.0, 1.0) * np.exp(-(V[:, 0] / 0.42) ** 2)
lips = np.exp(-((V[:, 1] - lips_out_c[1]) / 0.155) ** 2) * np.exp(-(V[:, 0] / 0.30) ** 2) * np.clip(V[:, 2] / 0.40, 0.0, 1.0)

cent = V[F].mean(axis=1)
group = np.ones(len(F))
neck = (cent[:, 1] < -1.10) | ((cent[:, 1] < -0.90) & (cent[:, 2] < 0.15))
cranium = (cent[:, 1] > 0.55) | (cent[:, 2] < -0.45)
group[cranium] = 1.2
group[neck] = 0.0
fade = 1.0 - 0.80 * smoothstep(-1.02, CUT, V[:, 1])

# ---- 4. ambient occlusion from the curvature -------------------------------------------------------------------------------
adj = [set() for _ in range(n_base)]
for a, b, c in F:
    adj[a].update((b, c)); adj[b].update((a, c)); adj[c].update((a, b))
adj = [np.array(sorted(s)) for s in adj]
ring2 = []
for i in range(n_base):
    s = set(adj[i].tolist())
    for j in adj[i]:
        s.update(adj[j].tolist())
    s.discard(i)
    ring2.append(np.array(sorted(s)))
edge_len = np.array([np.linalg.norm(V[adj[i]] - V[i], axis=1).mean() for i in range(n_base)])


def concavity(nb):
    out = np.zeros(n_base)
    for i in range(n_base):
        out[i] = np.dot(V[nb[i]].mean(axis=0) - V[i], N[i])
    return out


c1 = concavity(adj) / edge_len
c2 = concavity(ring2) / (2.2 * edge_len)
occ = np.clip(1.3 * c1 + 2.2 * c2, 0.0, 1.0)
for _ in range(2):
    occ = 0.5 * occ + 0.5 * np.array([occ[adj[i]].mean() for i in range(n_base)])
ao = np.clip(1.0 - 0.85 * occ, 0.45, 1.0)


# ---- 5. hair shell over the scalp ------------------------------------------------------------------------------------------
def hairline(x, z):
    """y above which the head carries hair at (x, z): a forehead line, temples, sideburns and a low nape."""
    front = 0.50 - 0.04 * (1.0 - min(1.0, abs(x) / 0.6) ** 2) + 0.018 * np.sin(x * 19.0) - 0.05 * np.exp(-(x / 0.10) ** 2)
    drop = 1.05 * smoothstep(0.30, -0.65, z)
    side = 0.40 * smoothstep(0.40, 0.58, abs(x)) * smoothstep(0.55, 0.05, z)
    return front - drop - side


ears = (np.abs(cent[:, 0]) > 0.56) & (cent[:, 1] < 0.42) & (cent[:, 1] > -0.55) & (cent[:, 2] > -0.5) & (cent[:, 2] < 0.2)
above = cent[:, 1] > np.array([hairline(x, z) for x, z in zip(cent[:, 0], cent[:, 2])])
hair_f = np.flatnonzero(above & ~ears & (group != 0.0))
used = np.unique(F[hair_f].ravel())
remap = -np.ones(n_base, dtype=np.int64)
remap[used] = np.arange(len(used))
HV, HN = V[used].copy(), N[used].copy()
hl = np.array([hairline(x, z) for x, z in zip(HV[:, 0], HV[:, 2])])
rise = np.clip((HV[:, 1] - hl) / 0.30, 0.0, 1.0)
taper = smoothstep(0.0, 1.0, rise)
crownf = smoothstep(0.30, 0.95, HV[:, 1])
backf = smoothstep(0.1, -0.7, HV[:, 2])
sidef = smoothstep(0.30, 0.55, np.abs(HV[:, 0]))
thick = 0.009 + taper * (0.040 + 0.050 * crownf + 0.030 * backf + 0.028 * sidef)
HV = HV + HN * thick[:, None]
HF = remap[F[hair_f]]

rng = np.random.default_rng(11)
n_regular, strand_len = 700, 14
tri_pts = HV[HF]
areas = np.linalg.norm(np.cross(tri_pts[:, 1] - tri_pts[:, 0], tri_pts[:, 2] - tri_pts[:, 0]), axis=1)
pick = rng.choice(len(HF), size=n_regular, p=areas / areas.sum())
bary = rng.dirichlet([1, 1, 1], size=n_regular)
roots = np.einsum("sk,skd->sd", bary, tri_pts[pick])
centre = np.array([0.0, 0.10, -0.30])


def flow(p):
    radial = p - centre
    radial[1] = 0.0
    radial /= max(np.linalg.norm(radial), 1e-6)
    front = smoothstep(-0.1, 0.5, p[2])
    f = 0.55 * radial + np.array([0.0, -1.0, 0.0]) * 0.95 + np.array([0.85, 0.0, 0.0]) * front  # the fringe swept to one side
    return f / np.linalg.norm(f)


S_pts, S_nrm = [], []
for s in range(n_regular):
    p = roots[s].copy()
    pts, nrm = [], []
    wob = rng.normal(0, 0.10, 3)
    for k in range(strand_len):
        j = int(np.argmin(((HV - p) ** 2).sum(axis=1)))
        pts.append(HV[j].copy()); nrm.append(HN[j].copy())
        d = flow(p) + wob * 0.25
        d = d - np.dot(d, HN[j]) * HN[j]
        L = np.linalg.norm(d)
        if L < 1e-6:
            break
        p = HV[j] + d / L * 0.056
    while len(pts) < strand_len:
        pts.append(pts[-1].copy()); nrm.append(nrm[-1].copy())
    pts = np.array(pts)
    for _ in range(2):
        sm = pts.copy(); sm[1:-1] = 0.25 * pts[:-2] + 0.5 * pts[1:-1] + 0.25 * pts[2:]; pts = sm
    S_pts += list(pts + np.array(nrm) * 0.008); S_nrm += nrm

# fringe locks: thick strands hanging from the hairline over the forehead
skin_ids = np.flatnonzero((V[:, 2] > 0.15) & (V[:, 1] > 0.05) & (V[:, 1] < 0.9))
n_fringe = 0
for x_t in np.linspace(-0.50, 0.50, 56):
    y_t = hairline(x_t, 0.45)
    j = int(skin_ids[np.argmin((V[skin_ids, 0] - x_t) ** 2 + (V[skin_ids, 1] - y_t) ** 2)])
    length = rng.uniform(0.10, 0.20)
    pts, nrm = [], []
    cur = j
    for k in range(strand_len):
        u = min(1.0, k / 6.0)
        pts.append(V[cur] + N[cur] * (0.014 + 0.014 * u)); nrm.append(N[cur].copy())
        if k >= 6:
            continue
        d = np.array([0.30 + rng.normal(0, 0.10), -1.0, 0.0])
        d = d - np.dot(d, N[cur]) * N[cur]
        target = V[cur] + d / max(np.linalg.norm(d), 1e-6) * (length / 6.0)
        cur = int(skin_ids[np.argmin(((V[skin_ids] - target) ** 2).sum(axis=1))])
    S_pts += list(pts); S_nrm += nrm
    n_fringe += 1
n_strands = n_regular + n_fringe
S_pts, S_nrm = np.array(S_pts), np.array(S_nrm)

# ---- 6. circuits, forehead chip and seams -----------------------------------------------------------------------------------
head_vert = np.zeros(n_base, dtype=bool)
head_vert[F[group != 0.0].ravel()] = True  # vertices of the head proper (not the neck)
poolV = np.vstack([V[head_vert], HV])
poolN = np.vstack([N[head_vert], HN])
front_pool = poolV[:, 2] > -0.35
circuit_len = 24
rng2 = np.random.default_rng(23)


def in_domain(x, y):
    if 0.42 < y < 1.0 and abs(x) < 0.62:
        return True
    return 0.56 < abs(x) < 0.72 and -0.45 < y < 0.42


def project(x, y):
    d2 = np.where(front_pool, (poolV[:, 0] - x) ** 2 + (poolV[:, 1] - y) ** 2, 1e9)
    near = np.argpartition(d2, 6)[:6]
    j = near[np.argmax(poolV[near, 2])]
    return poolV[j] + poolN[j] * 0.010, poolN[j]


def finish_path(xy):
    pts, nrm = [], []
    for x, y in xy[:circuit_len]:
        p, n_ = project(x, y)
        pts.append(p); nrm.append(n_)
    pts = np.array(pts)
    for _ in range(2):
        sm = pts.copy(); sm[1:-1] = 0.25 * pts[:-2] + 0.5 * pts[1:-1] + 0.25 * pts[2:]; pts = sm
    while len(pts) < circuit_len:
        pts = np.vstack([pts, pts[-1]]); nrm.append(nrm[-1])
    return pts, np.array(nrm)


C_pts, C_nrm = [], []
DIRS = [(1, 0), (0, 1), (-1, 0), (0, -1), (0.7071, 0.7071), (0.7071, -0.7071), (-0.7071, 0.7071), (-0.7071, -0.7071)]
n_circuit = 0
while n_circuit < 100:
    x, y = rng2.uniform(-0.7, 0.7), rng2.uniform(-0.45, 1.0)
    if not in_domain(x, y):
        continue
    d = np.array(DIRS[int(rng2.integers(0, 4))], dtype=float)
    xy = [(x, y)]
    for seg in range(int(rng2.integers(3, 7))):
        steps = max(1, int(rng2.uniform(0.07, 0.22) / 0.03))
        ok = True
        for _ in range(steps):
            x2, y2 = xy[-1][0] + d[0] * 0.03, xy[-1][1] + d[1] * 0.03
            if not in_domain(x2, y2):
                ok = False
                break
            xy.append((x2, y2))
        if not ok:
            break
        ang = rng2.choice([-90, -45, 45, 90]) * np.pi / 180
        d = np.array([d[0] * np.cos(ang) - d[1] * np.sin(ang), d[0] * np.sin(ang) + d[1] * np.cos(ang)])
    if len(xy) < 5:
        continue
    pts, nrm = finish_path(xy)
    C_pts += list(pts); C_nrm += list(nrm)
    n_circuit += 1


def rounded_rect(cx, cy, w, h, r, n=24):
    pts = []
    corners = [(cx + w / 2 - r, cy + h / 2 - r, 0), (cx - w / 2 + r, cy + h / 2 - r, 90), (cx - w / 2 + r, cy - h / 2 + r, 180), (cx + w / 2 - r, cy - h / 2 + r, 270)]
    per = n // 4
    for ccx, ccy, a0 in corners:
        for k in range(per):
            a = np.radians(a0 + 90.0 * k / (per - 1))
            pts.append((ccx + r * np.cos(a), ccy + r * np.sin(a)))
    pts.append(pts[0])
    return pts[:n + 1]


n_bright = 0
chip_y = 0.72
for xy in (
    rounded_rect(0.0, chip_y, 0.32, 0.26, 0.06),
    [(-0.09, chip_y + 0.08), (-0.09, chip_y - 0.08), (-0.05, chip_y - 0.11), (0.05, chip_y - 0.11), (0.09, chip_y - 0.08), (0.09, chip_y + 0.08)],
    [(0.0, chip_y + 0.13), (0.0, chip_y + 0.21), (0.05, chip_y + 0.26), (0.05, chip_y + 0.30)],
    [(0.0, chip_y - 0.13), (0.0, chip_y - 0.20), (-0.05, chip_y - 0.24)],
):
    dense = []
    for (x0, y0), (x1, y1) in zip(xy[:-1], xy[1:]):
        m = max(1, int(np.hypot(x1 - x0, y1 - y0) / 0.02))
        for k in range(m):
            dense.append((x0 + (x1 - x0) * k / m, y0 + (y1 - y0) * k / m))
    dense.append(xy[-1])
    if len(dense) > circuit_len:
        dense = [dense[i] for i in np.linspace(0, len(dense) - 1, circuit_len).astype(int)]
    pts, nrm = finish_path(dense)
    C_pts += list(pts); C_nrm += list(nrm)
    n_circuit += 1; n_bright += 1


def add_seam(points, normals):
    global n_circuit, n_bright
    for start in range(0, len(points), circuit_len - 1):
        chunk = np.array(points[start:start + circuit_len]); nchunk = list(normals[start:start + circuit_len])
        if len(chunk) < 4:
            continue
        for _ in range(2):
            sm = chunk.copy(); sm[1:-1] = 0.25 * chunk[:-2] + 0.5 * chunk[1:-1] + 0.25 * chunk[2:]; chunk = sm
        while len(chunk) < circuit_len:
            chunk = np.vstack([chunk, chunk[-1]]); nchunk.append(nchunk[-1])
        C_pts.extend(list(chunk)); C_nrm.extend(nchunk)
        n_circuit += 1; n_bright += 1


# the jaw line seen from the front: the outermost points of the face at each height, down one side, across the chin, up the other
head_ids = np.flatnonzero(head_vert & (V[:, 2] > -0.1))
jaw_r, jaw_l = [], []
for y_t in np.linspace(-0.20, -0.86, 20):
    sel = head_ids[np.abs(V[head_ids, 1] - y_t) < 0.018]
    if len(sel):
        jaw_r.append(sel[np.argmax(V[sel, 0])]); jaw_l.append(sel[np.argmin(V[sel, 0])])
chin_pts = []
for x_t in np.linspace(0.30, -0.30, 14):
    sel = head_ids[(np.abs(V[head_ids, 0] - x_t) < 0.03) & (V[head_ids, 1] < -0.80) & (V[head_ids, 2] > 0.2)]
    if len(sel):
        chin_pts.append(sel[np.argmin(V[sel, 1])])
seam = jaw_r + chin_pts + jaw_l[::-1]
add_seam([V[i] + N[i] * 0.012 for i in seam], [N[i] for i in seam])
cap = [project(x_t, hairline(x_t, 0.4) + 0.02) for x_t in np.linspace(-0.58, 0.58, 60)]
add_seam([c[0] for c in cap], [c[1] for c in cap])
C_pts, C_nrm = np.array(C_pts), np.array(C_nrm)

# ---- 6b. the network: nodes spread evenly over the surface (closer together on the features), joined to their neighbours --------
n_scan = len(V)
rng3 = np.random.default_rng(31)
cand = np.flatnonzero(head_vert & (V[:, 1] > CUT + 0.03))
zone = np.ones(n_scan)
ax, ay, az = np.abs(V[:, 0]), V[:, 1], V[:, 2]
zone[(ax > 0.10) & (ax < 0.52) & (ay > -0.08) & (ay < 0.26) & (az > 0.2)] = 0.60           # the eyes and brows
zone[(ax < 0.22) & (ay > -0.48) & (ay < 0.02) & (az > 0.4)] = 0.60                          # the nose
zone[(ax < 0.36) & (ay > -0.78) & (ay < -0.38) & (az > 0.3)] = 0.62                         # the lips
zone[(ax > 0.56) & (ay < 0.42) & (ay > -0.55) & (az > -0.5) & (az < 0.2)] = 0.62           # the ears
zone[N[:, 2] < 0.30] = np.minimum(zone[N[:, 2] < 0.30], 0.70)                                # near the contour
R0 = 0.062
order = rng3.permutation(cand)
accepted = []
cell = 0.06
grid = {}
for i in order:
    r_i = R0 * zone[i]
    gx, gy, gz = (int(np.floor(V[i, 0] / cell)), int(np.floor(V[i, 1] / cell)), int(np.floor(V[i, 2] / cell)))
    ok = True
    for dx in (-2, -1, 0, 1, 2):
        for dy in (-2, -1, 0, 1, 2):
            for dz in (-2, -1, 0, 1, 2):
                for j in grid.get((gx + dx, gy + dy, gz + dz), ()):
                    if np.linalg.norm(V[i] - V[j]) < min(r_i, R0 * zone[j]) * 1.0:
                        ok = False
                        break
                if not ok:
                    break
            if not ok:
                break
        if not ok:
            break
    if ok:
        accepted.append(i)
        grid.setdefault((gx, gy, gz), []).append(i)
net_nodes = np.array(sorted(accepted), dtype=np.int64)
NP = V[net_nodes]
NN = N[net_nodes]
pairs = set()
for a in range(len(net_nodes)):
    d = np.linalg.norm(NP - NP[a], axis=1)
    d[a] = 1e9
    near = np.argsort(d)[:6]
    for b in near:
        if d[b] < 2.0 * R0 * max(zone[net_nodes[a]], zone[net_nodes[b]]) + 0.02 and np.dot(NN[a], NN[b]) > 0.25:
            pairs.add((min(a, b), max(a, b)))
net_edges = np.array(sorted(pairs), dtype=np.int64)
print("network:", len(net_nodes), "nodes,", len(net_edges), "edges")

# ---- 7. assemble ---------------------------------------------------------------------------------------------------------
# smooth landmark rings: each ring point becomes a vertex of its own, laid on the scan surface under (x, y) with the weights
# of the triangle it falls in (snapping the rings to scan vertices made the eyes and lips jagged)
front_faces = np.flatnonzero((np.cross(V[F[:, 1]] - V[F[:, 0]], V[F[:, 2]] - V[F[:, 0]])[:, 2] > 0) & (cent[:, 2] > 0.15))
tri_xy = V[F[front_faces]][:, :, :2]


def bary_on_surface(x, y):
    a, b, c = tri_xy[:, 0], tri_xy[:, 1], tri_xy[:, 2]
    v0, v1 = b - a, c - a
    v2 = np.array([x, y]) - a
    den = v0[:, 0] * v1[:, 1] - v1[:, 0] * v0[:, 1]
    den = np.where(np.abs(den) < 1e-12, 1e-12, den)
    u = (v2[:, 0] * v1[:, 1] - v1[:, 0] * v2[:, 1]) / den
    w = (v0[:, 0] * v2[:, 1] - v2[:, 0] * v0[:, 1]) / den
    t = 1.0 - u - w
    inside = np.flatnonzero((u >= -1e-6) & (w >= -1e-6) & (t >= -1e-6))
    if len(inside) == 0:
        return None
    zs = (V[F[front_faces[inside]]][:, :, 2] * np.stack([t[inside], u[inside], w[inside]], axis=1)).sum(axis=1)
    k = inside[np.argmax(zs)]
    return F[front_faces[k]], np.array([t[k], u[k], w[k]])


ring_first = len(V)
RV, RN, RJ, RB, RL, RA = [], [], [], [], [], []
ring_index = {}
for name in ["eye_l", "eye_r", "brow_l", "brow_r", "lips_out", "lips_in"]:
    ids = []
    for k, (x, y) in enumerate(ring_xy[name]):
        hit = bary_on_surface(x, y)
        if hit is None:  # outside every front triangle: use the nearest vertex
            j = int(rings[name][k])
            tri, wts = np.array([j, j, j]), np.array([1.0, 0.0, 0.0])
        else:
            tri, wts = hit
        pos = (V[tri] * wts[:, None]).sum(axis=0)
        nrm = (N[tri] * wts[:, None]).sum(axis=0)
        nrm /= max(np.linalg.norm(nrm), 1e-9)
        RV.append(pos + nrm * 0.004); RN.append(nrm)
        RJ.append((jaw[tri] * wts).sum()); RB.append((brow[tri] * wts).sum()); RL.append((lips[tri] * wts).sum()); RA.append((ao[tri] * wts).sum())
        ids.append(ring_first + len(RV) - 1)
    ring_index[name] = np.array(ids, dtype=np.int32)
for k in range(10):
    RJ[ring_index["lips_in"][k] - ring_first] = 1.0    # the lower inner lip leads the jaw
    RJ[ring_index["lips_out"][k] - ring_first] = 0.95
RV, RN = np.array(RV), np.array(RN)
V = np.vstack([V, RV]); N = np.vstack([N, RN])
jaw = np.concatenate([jaw, RJ]); brow = np.concatenate([brow, RB]); lips = np.concatenate([lips, RL])
fade = np.concatenate([fade, np.ones(len(RV))]); ao = np.concatenate([ao, RA])
n_base = len(V)
rings = ring_index

# ---- 8. real openings: the mouth is cut along the lip line and given a cavity with teeth, the eyes get holes with eyeballs
#         behind them, and the lids get weights so the app can close them --------------------------------------------------
scan_count = ring_first                       # vertices below this index are the scan's own; the ring vertices follow
paint = np.zeros(len(V), dtype=np.int64)      # ARGB colour painted on a vertex (0 = none)
lid = np.zeros(len(V))                        # y displacement at full closure (positive = down)
lip_mask = np.zeros(len(V))                   # 1 on the lips, 0 elsewhere


def point_in_poly(px, py, poly):
    inside = np.zeros(len(px), dtype=bool)
    x1, y1 = poly[:, 0], poly[:, 1]
    x2, y2 = np.roll(x1, -1), np.roll(y1, -1)
    for a, b, c, d in zip(x1, y1, x2, y2):
        inside ^= ((b > py) != (d > py)) & (px < (c - a) * (py - b) / (d - b + 1e-12) + a)
    return inside


def dist_to_poly(px, py, poly):
    best = np.full(len(px), 1e9)
    for k in range(len(poly)):
        a = poly[k]; b = poly[(k + 1) % len(poly)]
        ab = b - a
        t = np.clip(((px - a[0]) * ab[0] + (py - a[1]) * ab[1]) / max(ab @ ab, 1e-12), 0.0, 1.0)
        best = np.minimum(best, np.hypot(px - (a[0] + t * ab[0]), py - (a[1] + t * ab[1])))
    return best


def surface_z(x, y):
    d2 = (V[:scan_count, 0] - x) ** 2 + (V[:scan_count, 1] - y) ** 2
    near = np.argsort(d2)[:4]
    return float(V[near, 2].max())


added = {"V": [], "N": [], "jaw": [], "paint": []}


def add_vertex(pos, nrm, jaw_w, colour):
    added["V"].append(pos); added["N"].append(nrm); added["jaw"].append(jaw_w); added["paint"].append(colour)
    return len(V) + len(added["V"]) - 1


new_faces = []

# -- the mouth ------------------------------------------------------------------------------------------------------------
li, lo = ring_xy["lips_in"], ring_xy["lips_out"]
hwi = (li[:, 0].max() - li[:, 0].min()) / 2
bottom_chain = li[:11]; top_chain = np.vstack([li[10:], li[:1]])
order_b = np.argsort(bottom_chain[:, 0]); order_t = np.argsort(top_chain[:, 0])
def seam_centre(x):
    yb = np.interp(x, bottom_chain[order_b, 0], bottom_chain[order_b, 1])
    yt = np.interp(x, top_chain[order_t, 0], top_chain[order_t, 1])
    return 0.5 * (yb + yt)


# settle the seam on the scan's own lip crease: the least open (lowest ao) vertex of each column near the ring's centre line
xs_col = FACE_X0 + np.linspace(-hwi, hwi, 46)
ys_col = np.zeros(len(xs_col))
for k, x in enumerate(xs_col):
    yc = seam_centre(x)
    cand = np.flatnonzero((np.abs(V[:scan_count, 0] - x) < 0.014) & (np.abs(V[:scan_count, 1] - yc) < 0.035) & (V[:scan_count, 2] > 0.3))
    cand = np.flatnonzero((np.abs(V[:scan_count, 0] - x) < 0.014) & (V[:scan_count, 1] > yc - 0.05) & (V[:scan_count, 1] < yc + 0.02) & (V[:scan_count, 2] > 0.3))
    ys_col[k] = -0.488 - 0.55 * (x - FACE_X0) ** 2     # the scan's own mouth line (a thin sheet where the lips meet), curving down at the corners
ys_col = np.convolve(np.pad(ys_col, 3, mode="edge"), np.ones(7) / 7, mode="valid")
print("mouth line: mean y", float(ys_col.mean()), "(ring centre", float(np.mean([seam_centre(x) for x in xs_col])), ")")
def yseam(x):
    return np.interp(x, xs_col, ys_col)


mouth_w = lambda x: 1.0 - smoothstep(hwi * 0.9, hwi * 1.35, np.abs(x - FACE_X0))
tol = 0.02
seam_v = np.flatnonzero((np.abs(V[:scan_count, 0] - FACE_X0) < hwi * 0.92) & (np.abs(V[:scan_count, 1] - yseam(V[:scan_count, 0])) < tol) & (V[:scan_count, 2] > 0.3))
dup = {int(i): len(V) + len(added["V"]) + n for n, i in enumerate(seam_v)}
F2 = F.copy()
F0 = F.copy()                                 # the faces before the seam is split: their indices are all the scan's own
fc = V[F].mean(axis=1)
below = (fc[:, 1] < yseam(fc[:, 0])) & (np.abs(fc[:, 0] - FACE_X0) < hwi * 0.95) & (fc[:, 2] > 0.3)
for t in np.flatnonzero(below):
    for c in range(3):
        if int(F2[t, c]) in dup:
            F2[t, c] = dup[int(F2[t, c])]
for i in seam_v:
    added["V"].append(V[i].copy()); added["N"].append(N[i].copy()); added["jaw"].append(0.92 * float(mouth_w(V[i, 0]))); added["paint"].append(0)
for n_, i in enumerate(seam_v):                  # the split edge is laid straight along the lip line (a jagged one shows as zigzags)
    y_ = float(yseam(V[i, 0])); V[i, 1] = y_; added["V"][n_][1] = y_
print("mouth: seam vertices", len(seam_v), "faces below", int(below.sum()))
F = F2
seam_dup = np.array([dup[int(i)] for i in seam_v])

# jaw weights: the lower lip follows the jaw fully, the upper lip not at all, fading to the ordinary weights with distance
dy_seam = V[:, 1] - yseam(V[:, 0])
m = mouth_w(V[:, 0])
front = V[:, 2] > 0.3
lower_zone = front & (dy_seam <= tol) & (dy_seam > -0.20)
jaw[lower_zone] = np.maximum(jaw[lower_zone], 0.92 * m[lower_zone] * (1.0 - smoothstep(0.06, 0.20, -dy_seam[lower_zone])))
upper_zone = front & (dy_seam > tol) & (dy_seam < 0.12)
jaw[upper_zone] = jaw[upper_zone] * (1.0 - m[upper_zone] * (1.0 - smoothstep(0.0, 0.12, dy_seam[upper_zone])))
jaw[seam_v] = jaw[seam_v] * 0.0                   # the seam's upper copy stays with the upper lip
for n_, i in enumerate(seam_dup):
    pass

# the cavity (dark) and the teeth
DARK = 0xFF0B0A10; TEETH = 0xFFECE7DC
cols = FACE_X0 + np.linspace(-hwi * 0.86, hwi * 0.86, 12)
U, L, B = [], [], []
for x in cols:
    ys = float(yseam(x)); zs = surface_z(x, ys); w_ = float(mouth_w(x))
    U.append(add_vertex(np.array([x, ys, zs - 0.004]), np.array([0, 0, 1.0]), 0.0, DARK))
    L.append(add_vertex(np.array([x, ys, zs - 0.004]), np.array([0, 0, 1.0]), 0.92 * w_, DARK))
    B.append(add_vertex(np.array([FACE_X0 + (x - FACE_X0) * 0.9, ys - 0.005, zs - 0.15]), np.array([0, 0, 1.0]), 0.45, DARK))
for k in range(len(cols) - 1):
    new_faces += [(U[k], U[k + 1], B[k + 1]), (U[k], B[k + 1], B[k]), (L[k], B[k], B[k + 1]), (L[k], B[k + 1], L[k + 1])]
tc = [k for k, x in enumerate(cols) if abs(x - FACE_X0) < 0.78 * hwi]
TU, TUb, TL, TLb = [], [], [], []
for k in tc:
    x = cols[k]; ys = float(yseam(x)); zs = surface_z(x, ys); w_ = float(mouth_w(x))
    TU.append(add_vertex(np.array([x, ys, zs - 0.010]), np.array([0, 0, 1.0]), 0.0, TEETH))
    TUb.append(add_vertex(np.array([x, ys - 0.045, zs - 0.045]), np.array([0, 0, 1.0]), 0.0, TEETH))
    TL.append(add_vertex(np.array([x, ys, zs - 0.010]), np.array([0, 0, 1.0]), 0.92 * w_, TEETH))
    TLb.append(add_vertex(np.array([x, ys + 0.026, zs - 0.038]), np.array([0, 0, 1.0]), 0.92 * w_, TEETH))
for k in range(len(tc) - 1):
    new_faces += [(TU[k], TU[k + 1], TUb[k + 1]), (TU[k], TUb[k + 1], TUb[k])]

# -- the eyes -------------------------------------------------------------------------------------------------------------
IRIS_RINGS = [(0, 0xFF05070A), (6, 0xFF05070A), (12, 0xFF16324F), (18, 0xFF3F7CA6), (25, 0xFF2A5B7C), (31, 0xFFDCDAD6), (42, 0xFFEEEBE6),
              (60, 0xFFE9E5DF), (85, 0xFFD9D4CE), (110, 0xFFCFC9C3), (140, 0xFFC4BDB6)]
SEG = 20
eye_info = []
removed = np.zeros(len(F), dtype=bool)
LID_REACH = 0.075
for name in ("eye_l", "eye_r"):
    poly = ring_xy[name]
    ctr = poly.mean(axis=0)
    w_eye = poly[:, 0].max() - poly[:, 0].min()
    hh = (poly[:, 1].max() - poly[:, 1].min()) / 2
    hole = ctr + (poly - ctr) * 1.0
    cf = fc
    corner_in = np.stack([point_in_poly(V[F0[:, c], 0], V[F0[:, c], 1], hole) for c in range(3)], axis=1)
    removed |= ((corner_in.sum(axis=1) >= 2) | point_in_poly(cf[:, 0], cf[:, 1], hole)) & (cf[:, 2] > 0.3)
    # lid weights (on the scan's vertices, and on the ring vertices that are laid on it)
    dist = dist_to_poly(V[:, 0], V[:, 1], hole)
    inside_v = point_in_poly(V[:, 0], V[:, 1], hole)
    dist = np.where(inside_v, 0.0, dist)
    reach = front & (dist < LID_REACH)
    f_ = np.where(reach, (1.0 - dist / LID_REACH) ** 2, 0.0)
    lid += (V[:, 1] - ctr[1]) * f_ * (np.abs(V[:, 0] - ctr[0]) < w_eye * 0.95)
    # the eyeball, behind the hole
    rad = 0.5 * w_eye * 1.0
    zc = surface_z(ctr[0], ctr[1]) - 1.7 * rad         # well behind the lids, so a lid always draws over the eyeball
    centre = np.array([ctr[0], ctr[1], zc])
    first = len(V) + len(added["V"])
    back_c = add_vertex(centre + np.array([0.0, 0.0, -0.1 * rad]), np.array([0, 0, 1.0]), 0.0, 0xFF2B1F1C)
    back_ring = [add_vertex(centre + np.array([np.cos(a_) * 0.60 * w_eye, np.sin(a_) * 0.42 * w_eye, -0.1 * rad]), np.array([0, 0, 1.0]), 0.0, 0xFF2B1F1C)
                 for a_ in np.linspace(0, 2 * np.pi, 16, endpoint=False)]
    for k_ in range(16):
        new_faces.append((back_c, back_ring[k_], back_ring[(k_ + 1) % 16]))
    ids = []
    for theta_deg, colour in IRIS_RINGS:
        th = np.radians(theta_deg)
        row = []
        for sg in range(SEG if theta_deg > 0 else 1):
            ph = 2 * np.pi * sg / SEG
            d = np.array([np.sin(th) * np.cos(ph), np.sin(th) * np.sin(ph), np.cos(th)])
            row.append(add_vertex(centre + d * rad, d, 0.0, colour))
        ids.append(row)
    for r_ in range(len(IRIS_RINGS) - 1):
        a_, b_ = ids[r_], ids[r_ + 1]
        for sg in range(SEG):
            n0, n1 = (sg + 1) % SEG, sg
            if len(a_) == 1:
                new_faces.append((a_[0], b_[n1], b_[n0]))
            else:
                new_faces += [(a_[sg], b_[sg], b_[n0]), (a_[sg], b_[n0], a_[n0])]
    eye_info.append((first, len(V) + len(added["V"]) - first, centre))
F = F[~removed]
group = group[~removed]
print("eyes: faces removed", int(removed.sum()))

# -- the lips' colour mask ---------------------------------------------------------------------------------------------------
lo_centre = lo.mean(axis=0)
# the scan's upper lip is a shelf that reaches well above the mouth line (down to the lower lip's bottom at about -0.66), so the mask
# is the ring's full height, centred on that span
lip_c = np.array([lo_centre[0], float(np.mean(ys_col)) - 0.004])
lo_fit = lip_c + (lo - lo_centre) * np.array([1.0, 0.55])   # the lips span about 0.05 above and 0.055 below the mouth line   # the ring is taller and wider than the scan's own lips
inside_lip = point_in_poly(V[:, 0], V[:, 1], lo_fit) & front
d_lip = dist_to_poly(V[:, 0], V[:, 1], lo_fit)
lip_mask = np.where(inside_lip, 1.0, np.clip(1.0 - d_lip / 0.010, 0.0, 1.0) * front)

# -- append everything ----------------------------------------------------------------------------------------------------
n_new = len(added["V"])
V = np.vstack([V, np.array(added["V"])]); N = np.vstack([N, np.array(added["N"])])
jaw = np.concatenate([jaw, np.array(added["jaw"])])
brow = np.concatenate([brow, np.zeros(n_new)]); lips = np.concatenate([lips, np.zeros(n_new)])
fade = np.concatenate([fade, np.ones(n_new)]); ao = np.concatenate([ao, np.ones(n_new)])
paint = np.concatenate([paint, np.array(added["paint"], dtype=np.int64)])
lid = np.concatenate([lid, np.zeros(n_new)]); lip_mask = np.concatenate([lip_mask, np.zeros(n_new)])
F = np.vstack([F, np.array(new_faces, dtype=np.int64)])
group = np.concatenate([group, np.ones(len(new_faces))])

nh, ns, nc_pts = len(HV), len(S_pts), len(C_pts)
allV = np.vstack([V, HV, S_pts, C_pts])
allN = np.vstack([N, HN, S_nrm, C_nrm])
extra = nh + ns + nc_pts
allJaw = np.concatenate([jaw, np.zeros(extra)])
allBrow = np.concatenate([brow, np.zeros(extra)])
allLips = np.concatenate([lips, np.zeros(extra)])
hair_fade = 0.50 + 0.50 * smoothstep(0.0, 0.9, rise)  # hair roots are darker
allFade = np.concatenate([fade, hair_fade, np.ones(ns + nc_pts)])
allAo = np.concatenate([ao, np.ones(extra)])
allF = np.vstack([F, HF + n_base])
allGroup = np.concatenate([group, np.full(len(HF), 2.0)])
strand_base = n_base + nh

# The app's head file (JHM1): the scanned head and neck only, with the rig weights and the landmark rings. The hair, strands,
# circuits and network nodes computed above belong to the other looks and are not stored; the app spreads its own web over the mesh.
out = bytearray()
out += b"JHM2"
out += struct.pack("<5i", len(V), len(F), 0, scan_count, scan_count)
out += struct.pack("<2f", float(V[:, 1].max()), float(V[:, 1].min()))
out += lip_centre.astype("<f4").tobytes()
for arr in (V, N):
    out += arr.astype("<f4").tobytes()
for arr in (jaw, brow, lips, fade):
    out += arr.astype("<f4").tobytes()
out += np.where(group > 0.5, 1.0, 0.0).astype("<f4").tobytes()
out += F.astype("<i4").tobytes()
names = ["eye_l", "eye_r", "brow_l", "brow_r", "lips_out", "lips_in"]
out += struct.pack("<i", len(names))
for k in names:
    idx = rings[k].astype("<i4")
    out += struct.pack("<i", len(idx)) + idx.tobytes()
out += (paint & 0xFFFFFFFF).astype("<u4").tobytes()
out += lid.astype("<f4").tobytes()
out += lip_mask.astype("<f4").tobytes()
out += struct.pack("<i", len(eye_info))
for first, count, centre in eye_info:
    out += struct.pack("<2i3f", first, count, *[float(c) for c in centre])

if os.environ.get("JHM_DEBUG"):
    np.savez(os.environ["JHM_DEBUG"], V=V, F=F, paint=paint, eye_l=ring_xy["eye_l"], eye_r=ring_xy["eye_r"], lips_out=ring_xy["lips_out"],
             lips_in=ring_xy["lips_in"], brow_l=ring_xy["brow_l"], brow_r=ring_xy["brow_r"], xs_col=xs_col, ys_col=ys_col,
             eyes=np.array([c for _, _, c in eye_info]))
dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "avatar")
open(os.path.join(dest, "head_mesh.bin"), "wb").write(bytes(out))
print("verts", len(V), "faces", len(F), "bytes", len(out))
