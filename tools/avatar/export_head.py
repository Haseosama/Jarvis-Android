"""Builds the avatar's head asset (app/src/main/assets/avatar/head_mesh.bin, format JHM3).

Usage: python export_head.py <path to a clone of FatihMakes/Mark-LIV> [preview]
Needs numpy (and matplotlib for the preview). Mark-LIV is CC BY-NC 4.0: see app/src/main/assets/avatar/NOTICE.txt.

What is built:
  * Mark-LIV's head (real face geometry + cranium + neck), subdivided once with a curved (PN-style) midpoint rule;
  * per-vertex ambient occlusion from the curvature (eye sockets, nostrils, under the lip and the nose darken);
  * a hair shell over the cranium and the upper forehead, and roughly 700 individual strands that follow a flow field
    (radial from the crown, pulled by gravity, the fringe swept to one side).
Layout of the vertices: head and neck | hair shell | strand points. Faces: group 0 neck, 1 face mask,
1.2 cranium sweep, 2 hair.
"""
import struct, sys, os
import numpy as np

MARK_LIV = sys.argv[1]
sys.path.insert(0, os.path.join(MARK_LIV, "core"))
import avatar_mesh as am

am._SKULL_RINGS = 7
am._NECK_SEGS = 14
am._NECK_RINGS = 9

mesh = am.build_head()
V = mesh["verts"].astype(np.float64)
N = mesh["normals"].astype(np.float64)
F = mesh["faces"].astype(np.int64)
jaw, brow, lips, fade = (mesh[k].astype(np.float64) for k in ("jaw", "brow", "lips", "fade"))
MASK_FACES = len(F) - 0  # placeholder, replaced below

group = mesh["face_group"].astype(np.float64)  # 1 head, 0 neck
n_face, n_head = mesh["n_face"], mesh["n_head"]
region = np.zeros(len(V), dtype=np.int64)
region[n_face:n_head] = 1
region[n_head:] = 2
F0 = F.copy()
loop0 = am._boundary_loop(F0[:898])  # the border of the face mask: it becomes a glowing seam


def smoothstep0(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def sculpt(V, region):
    """Reshape the human mask into a sleeker android face. The neck is left alone."""
    V = V.copy()
    x, y, z = V[:, 0].copy(), V[:, 1].copy(), V[:, 2].copy()
    m = region < 2
    t = smoothstep0(0.0, -1.0, y)                       # 0 at eye level, 1 at the chin
    nx = x * (1.0 - 0.23 * t - 0.08 * t ** 4)           # a narrower, more pointed jaw
    ny = np.where(y < -0.35, -0.35 + (y + 0.35) * 1.10, y)  # a longer chin
    cheek = np.exp(-((y + 0.10) / 0.15) ** 2) * np.exp(-((np.abs(x) - 0.40) / 0.15) ** 2)
    nz = z + 0.055 * cheek                              # high cheekbones
    nx = nx * (1.0 + 0.04 * cheek)
    nose = np.exp(-(x / 0.16) ** 2) * np.exp(-((y + 0.10) / 0.22) ** 2) * (z > 0.35)
    nx = nx * (1.0 - 0.16 * nose)                       # a slimmer nose
    V[m, 0], V[m, 1], V[m, 2] = nx[m], ny[m], nz[m]
    return V


def vertex_normals(V, F, ref):
    n = np.zeros_like(V)
    a, b, c = V[F[:, 0]], V[F[:, 1]], V[F[:, 2]]
    fn = np.cross(b - a, c - a)
    for k in range(3):
        np.add.at(n, F[:, k], fn)
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-9)
    flip = (n * ref).sum(1) < 0
    n[flip] *= -1.0
    return n


N0 = mesh["normals"].astype(np.float64)
V = sculpt(V, region)
N = vertex_normals(V, F, N0)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


# ---- one level of curved (PN-style) subdivision; original vertices keep their index and position -------------------
def subdivide(V, N, F, attrs, region, group):
    edge_mid = {}
    newV, newN = [], []
    newAttr = [[] for _ in attrs]
    newRegion = []
    base = len(V)

    def mid(a, b):
        key = (a, b) if a < b else (b, a)
        if key in edge_mid:
            return edge_mid[key]
        p0, p1, n0, n1 = V[a], V[b], N[a], N[b]
        w01 = np.dot(p1 - p0, n0)
        w10 = np.dot(p0 - p1, n1)
        b012 = (2 * p0 + p1 - w01 * n0) / 3.0
        b021 = (2 * p1 + p0 - w10 * n1) / 3.0
        m = (p0 + 3 * b012 + 3 * b021 + p1) / 8.0
        lin = (p0 + p1) / 2.0
        d = m - lin
        L = np.linalg.norm(d)
        cap = 0.25 * np.linalg.norm(p1 - p0)  # a poorly oriented normal cannot throw a vertex far off
        if L > cap:
            m = lin + d * (cap / L)
        nn = n0 + n1
        nn = nn / max(np.linalg.norm(nn), 1e-9)
        idx = base + len(newV)
        newV.append(m); newN.append(nn)
        for k, arr in enumerate(attrs):
            newAttr[k].append((arr[a] + arr[b]) / 2.0)
        ra, rb = region[a], region[b]
        newRegion.append(2 if 2 in (ra, rb) else (1 if 1 in (ra, rb) else 0))
        edge_mid[key] = idx
        return idx

    nf, ng = [], []
    for t, (a, b, c) in enumerate(F):
        ab, bc, ca = mid(a, b), mid(b, c), mid(c, a)
        nf += [[a, ab, ca], [ab, b, bc], [ca, bc, c], [ab, bc, ca]]
        ng += [group[t]] * 4
    return (np.vstack([V, np.array(newV)]), np.vstack([N, np.array(newN)]), np.array(nf),
            [np.concatenate([arr, np.array(newAttr[k])]) for k, arr in enumerate(attrs)],
            np.concatenate([region, np.array(newRegion)]), np.array(ng))


# 1.0 face mask, 1.2 cranium sweep (no crease lines there), 0 neck
kind = group.copy()
for t, tri in enumerate(F):
    if group[t] > 0.5 and (region[tri] == 1).any():
        kind[t] = 1.2
V, N, F, (jaw, brow, lips, fade), region, group = subdivide(V, N, F, [jaw, brow, lips, fade], region, kind)
n_base = len(V)

# ---- ambient occlusion from curvature ---------------------------------------------------------------------------
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


def concavity(rings):
    out = np.zeros(n_base)
    for i in range(n_base):
        L = V[rings[i]].mean(axis=0) - V[i]
        out[i] = np.dot(L, N[i])
    return out


c1 = concavity(adj) / edge_len
c2 = concavity(ring2) / (2.2 * edge_len)
occ = np.clip(1.3 * c1 + 2.2 * c2, 0.0, 1.0)
for _ in range(2):
    occ = 0.5 * occ + 0.5 * np.array([occ[adj[i]].mean() for i in range(n_base)])
ao = np.clip(1.0 - 0.85 * occ, 0.45, 1.0)
ao[region == 2] = 1.0

# ---- hair -------------------------------------------------------------------------------------------------------
face_top = V[:n_face, 1].max()


def hairline(x, z):
    """y above which the head carries hair at (x, z): a forehead line with recessed temples, a widow's peak, sideburns and a low nape."""
    front = face_top - 0.13 - 0.045 * (1.0 - min(1.0, abs(x) / 0.70) ** 2) + 0.020 * np.sin(x * 19.0) - 0.05 * np.exp(-(x / 0.10) ** 2)
    drop = 1.05 * smoothstep(0.30, -0.65, z)
    side = 0.62 * smoothstep(0.40, 0.68, abs(x)) * smoothstep(0.60, 0.05, z)
    return front - drop - side


cent = V[F].mean(axis=1)
above = cent[:, 1] > np.array([hairline(x, z) for x, z in zip(cent[:, 0], cent[:, 2])])
not_neck = (region[F] != 2).all(axis=1)
hair_f = np.flatnonzero(above & not_neck)

used = np.unique(F[hair_f].ravel())
remap = -np.ones(len(V), dtype=np.int64)
remap[used] = np.arange(len(used))
HV = V[used].copy()
HN = N[used].copy()
hl = np.array([hairline(x, z) for x, z in zip(HV[:, 0], HV[:, 2])])
rise = np.clip((HV[:, 1] - hl) / 0.30, 0.0, 1.0)
taper = smoothstep(0.0, 1.0, rise)
crown = smoothstep(0.30, 0.95, HV[:, 1])
back = smoothstep(0.1, -0.7, HV[:, 2])
side_bulge = smoothstep(0.30, 0.70, np.abs(HV[:, 0]))
thick = 0.009 + taper * (0.040 + 0.050 * crown + 0.030 * back + 0.028 * side_bulge)
HV = HV + HN * thick[:, None]
HF = remap[F[hair_f]]

# ---- strands: roots scattered over the shell, each following the flow field --------------------------------------
rng = np.random.default_rng(11)
n_regular, strand_len = 700, 14
tri_pts = HV[HF]
areas = np.linalg.norm(np.cross(tri_pts[:, 1] - tri_pts[:, 0], tri_pts[:, 2] - tri_pts[:, 0]), axis=1)
pick = rng.choice(len(HF), size=n_regular, p=areas / areas.sum())
bary = rng.dirichlet([1, 1, 1], size=n_regular)
roots = np.einsum("sk,skd->sd", bary, tri_pts[pick])
centre = np.array([0.0, 0.10, -0.30])


def flow(p, part_side):
    radial = p - centre
    radial[1] = 0.0
    radial /= max(np.linalg.norm(radial), 1e-6)
    front = smoothstep(-0.1, 0.5, p[2])
    # the fringe is swept to one side from a side part; the back falls straight down
    sweep = np.array([0.85 * part_side, 0.0, 0.0]) * front
    f = 0.55 * radial + np.array([0.0, -1.0, 0.0]) * 0.95 + sweep
    return f / np.linalg.norm(f)


S_pts, S_nrm = [], []
for s in range(n_regular):
    p = roots[s].copy()
    pts, nrm = [], []
    wob = rng.normal(0, 0.10, 3)
    for k in range(strand_len):
        j = int(np.argmin(((HV - p) ** 2).sum(axis=1)))
        pts.append(HV[j].copy()); nrm.append(HN[j].copy())
        d = flow(p, 1.0) + wob * 0.25
        d = d - np.dot(d, HN[j]) * HN[j]  # keep to the surface
        L = np.linalg.norm(d)
        if L < 1e-6:
            break
        p = HV[j] + d / L * 0.056
    while len(pts) < strand_len:
        pts.append(pts[-1].copy()); nrm.append(nrm[-1].copy())
    pts = np.array(pts)
    for _ in range(2):  # moving average removes the zig-zag of snapping to mesh vertices
        sm = pts.copy()
        sm[1:-1] = 0.25 * pts[:-2] + 0.5 * pts[1:-1] + 0.25 * pts[2:]
        pts = sm
    pts = pts + np.array(nrm) * 0.008
    S_pts += list(pts); S_nrm += nrm
# ---- fringe locks: thick strands hanging from the hairline over the forehead, hiding the ragged edge of the shell ----
skin_ids = np.flatnonzero((region == 0) & (V[:, 2] > 0.15) & (V[:, 1] > face_top - 0.55))
n_fringe = 0
for x_t in np.linspace(-0.62, 0.62, 56):
    y_t = hairline(x_t, 0.45)
    d2 = (V[skin_ids, 0] - x_t) ** 2 + (V[skin_ids, 1] - y_t) ** 2
    j = int(skin_ids[np.argmin(d2)])
    length = rng.uniform(0.10, 0.21)
    pts, nrm = [], []
    cur = j
    for k in range(strand_len):
        u = min(1.0, k / 6.0)
        pts.append(V[cur] + N[cur] * (0.014 + 0.014 * u)); nrm.append(N[cur].copy())
        if k >= 6:
            continue
        d = np.array([0.30 + rng.normal(0, 0.10), -1.0, 0.0])  # gravity, swept a little to the right
        d = d - np.dot(d, N[cur]) * N[cur]
        L = np.linalg.norm(d)
        target = V[cur] + d / max(L, 1e-6) * (length / 6.0)
        cur = int(skin_ids[np.argmin(((V[skin_ids] - target) ** 2).sum(axis=1))])
    pts = np.array(pts)
    S_pts += list(pts); S_nrm += nrm
    n_fringe += 1
n_strands = n_regular + n_fringe
S_pts = np.array(S_pts)
S_nrm = np.array(S_nrm)

# ---- circuits: glowing PCB-like traces over the cranial shell, the temples and the cheeks, plus a forehead chip --------
poolV = np.vstack([V[region != 2], HV])
poolN = np.vstack([N[region != 2], HN])
front_pool = poolV[:, 2] > -0.35
circuit_len = 24
rng2 = np.random.default_rng(23)


def in_domain(x, y):
    if 0.40 < y < 1.02 and abs(x) < 0.80:
        return True  # the top of the head
    return 0.62 < abs(x) < 0.90 and -0.60 < y < 0.42  # temples and cheeks beside the eyes


def project(x, y):
    d2 = (poolV[:, 0] - x) ** 2 + (poolV[:, 1] - y) ** 2
    d2 = np.where(front_pool, d2, 1e9)
    near = np.argpartition(d2, 6)[:6]
    j = near[np.argmax(poolV[near, 2])]  # the outermost surface among the closest
    return poolV[j] + poolN[j] * 0.010, poolN[j]


def finish_path(xy):
    pts, nrm = [], []
    for x, y in xy[:circuit_len]:
        p, n_ = project(x, y)
        pts.append(p); nrm.append(n_)
    pts = np.array(pts)
    for _ in range(2):
        sm = pts.copy()
        sm[1:-1] = 0.25 * pts[:-2] + 0.5 * pts[1:-1] + 0.25 * pts[2:]
        pts = sm
    while len(pts) < circuit_len:
        pts = np.vstack([pts, pts[-1]]); nrm.append(nrm[-1])
    return pts, np.array(nrm)


C_pts, C_nrm = [], []
DIRS = [(1, 0), (0, 1), (-1, 0), (0, -1), (0.7071, 0.7071), (0.7071, -0.7071), (-0.7071, 0.7071), (-0.7071, -0.7071)]
n_circuit = 0
while n_circuit < 100:
    x, y = rng2.uniform(-0.85, 0.85), rng2.uniform(-0.6, 1.0)
    if not in_domain(x, y):
        continue
    d = np.array(DIRS[int(rng2.integers(0, 4))], dtype=float)
    xy = [(x, y)]
    for seg in range(int(rng2.integers(3, 7))):
        length = rng2.uniform(0.07, 0.22)
        steps = max(1, int(length / 0.03))
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


# the forehead chip: an outer rounded square, an inner "U", and two feeder lines
n_bright = 0
chip_y = 0.66
for xy in (
    rounded_rect(0.0, chip_y, 0.36, 0.30, 0.07),
    [(-0.10, chip_y + 0.09), (-0.10, chip_y - 0.09), (-0.06, chip_y - 0.12), (0.06, chip_y - 0.12), (0.10, chip_y - 0.09), (0.10, chip_y + 0.09)],
    [(0.0, chip_y + 0.15), (0.0, chip_y + 0.24), (0.06, chip_y + 0.30), (0.06, chip_y + 0.34)],
    [(0.0, chip_y - 0.15), (0.0, chip_y - 0.22), (-0.05, chip_y - 0.27)],
):
    dense = []
    for (x0, y0), (x1, y1) in zip(xy[:-1], xy[1:]):
        m = max(1, int(np.hypot(x1 - x0, y1 - y0) / 0.02))
        for k in range(m):
            dense.append((x0 + (x1 - x0) * k / m, y0 + (y1 - y0) * k / m))
    dense.append(xy[-1])
    if len(dense) > circuit_len:  # keep the shape: sample evenly
        idx = np.linspace(0, len(dense) - 1, circuit_len).astype(int)
        dense = [dense[i] for i in idx]
    pts, nrm = finish_path(dense)
    C_pts += list(pts); C_nrm += list(nrm)
    n_circuit += 1
    n_bright += 1
# glowing seams: the border of the face plate (jaw, cheeks, temples) and the lower edge of the cranial shell
def add_seam(points, normals):
    global n_circuit, n_bright
    for start in range(0, len(points), circuit_len - 1):
        chunk = points[start:start + circuit_len]
        nchunk = normals[start:start + circuit_len]
        if len(chunk) < 4:
            continue
        chunk = np.array(chunk)
        for _ in range(2):
            sm = chunk.copy()
            sm[1:-1] = 0.25 * chunk[:-2] + 0.5 * chunk[1:-1] + 0.25 * chunk[2:]
            chunk = sm
        nchunk = list(nchunk)
        while len(chunk) < circuit_len:
            chunk = np.vstack([chunk, chunk[-1]]); nchunk.append(nchunk[-1])
        C_pts.extend(list(chunk)); C_nrm.extend(nchunk)
        n_circuit += 1
        n_bright += 1


loop_pts = [V[i] + N[i] * 0.012 for i in loop0] + [V[loop0[0]] + N[loop0[0]] * 0.012]
loop_nrm = [N[i] for i in loop0] + [N[loop0[0]]]
add_seam(loop_pts, loop_nrm)
cap_pts, cap_nrm = [], []
for x_t in np.linspace(-0.78, 0.78, 66):
    p_, n_ = project(x_t, hairline(x_t, 0.4) + 0.02)
    cap_pts.append(p_); cap_nrm.append(n_)
add_seam(cap_pts, cap_nrm)
C_pts = np.array(C_pts)
C_nrm = np.array(C_nrm)

# ---- assemble ---------------------------------------------------------------------------------------------------
nh, ns = len(HV), len(S_pts)
allV = np.vstack([V, HV, S_pts, C_pts])
allN = np.vstack([N, HN, S_nrm, C_nrm])
zeros = lambda n: np.zeros(n)
nc_pts = len(C_pts)
allJaw = np.concatenate([jaw, zeros(nh + ns + nc_pts)])
allBrow = np.concatenate([brow, zeros(nh + ns + nc_pts)])
allLips = np.concatenate([lips, zeros(nh + ns + nc_pts)])
hair_fade = 0.50 + 0.50 * smoothstep(0.0, 0.9, rise)  # hair roots are darker
allFade = np.concatenate([fade, hair_fade, np.ones(ns + nc_pts)])
allAo = np.concatenate([ao, np.ones(nh + ns + nc_pts)])
allF = np.vstack([F, HF + n_base])
allGroup = np.concatenate([group, np.full(len(HF), 2.0)])
strand_base = n_base + nh

out = bytearray()
out += b"JHM3"
out += struct.pack("<7i", len(allV), len(allF), 0, n_base, n_face, n_strands, strand_len)
out += struct.pack("<3i", strand_base, nh, len(hair_f))
out += struct.pack("<2f", float(allV[:n_base, 1].max()), float(allV[:n_base, 1].min()))
out += mesh["lip_centre"].astype("<f4").tobytes()
for arr in (allV, allN):
    out += arr.astype("<f4").tobytes()
for arr in (allJaw, allBrow, allLips, allFade, allAo):
    out += arr.astype("<f4").tobytes()
out += allGroup.astype("<f4").tobytes()
out += allF.astype("<i4").tobytes()
names = ["eye_l", "eye_r", "brow_l", "brow_r", "lips_out", "lips_in"]
out += struct.pack("<i", len(names))
for k in names:
    idx = mesh["landmarks"][k].astype("<i4")
    out += struct.pack("<i", len(idx)) + idx.tobytes()
out += struct.pack("<i", n_fringe)  # the last n_fringe strands are fringe locks (drawn thicker)
out += struct.pack("<3i", n_circuit, circuit_len, n_bright)  # circuit traces follow the strand points; the last n_bright form the forehead chip

dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "avatar")
open(os.path.join(dest, "head_mesh.bin"), "wb").write(bytes(out))
print("verts", len(allV), "(base", n_base, "hair", nh, "strand pts", ns, ") faces", len(allF), "hair faces", len(hair_f),
      "bytes", len(out), "ao range", float(ao.min()), float(ao.max()))

if len(sys.argv) > 2:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    fig, axes = plt.subplots(1, 2, figsize=(11, 6))
    sel = np.arange(len(F))
    col = ao
    for ax, (ix, iy, name) in zip(axes, [(0, 1, "front"), (2, 1, "side")]):
        tri = V[F]
        depth = tri[:, :, 2 if name == "front" else 0].mean(axis=1)
        order = np.argsort(depth if name == "front" else -depth)
        from matplotlib.collections import PolyCollection
        polys = [tri[i][:, [ix, iy]] for i in order]
        fc = [str(float(col[F[i]].mean())) for i in order]
        ax.add_collection(PolyCollection(polys, facecolor=fc, edgecolor="none"))
        sp = allV[strand_base:].reshape(n_strands, strand_len, 3)
        for line in sp[::6]:
            ax.plot(line[:, ix], line[:, iy], color="#a52", lw=0.4)
        ax.set_xlim(-1.3, 1.3); ax.set_ylim(-1.4, 1.2); ax.set_aspect("equal"); ax.set_title(name)
    plt.savefig("hair_preview.png", dpi=80)
    print("preview saved")
