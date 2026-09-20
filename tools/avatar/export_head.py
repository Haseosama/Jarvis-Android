"""Head asset v2: Mark-LIV's head, one level of curved subdivision (smoother), plus a hair shell and hair strands.

Layout of the output arrays (all in the same head-local coordinates as Mark-LIV's builder):
  vertices  = subdivided head + neck | hair shell | strand points
  faces     = head/neck triangles (group 1 head, 0 neck) | hair triangles (group 2)
"""
import struct, sys, os
# Usage: python export_head.py <path to a clone of FatihMakes/Mark-LIV> [preview]
# Needs numpy (and matplotlib for the preview). Mark-LIV is CC BY-NC 4.0: see app/src/main/assets/avatar/NOTICE.txt.
MARK_LIV = sys.argv[1]
sys.path.insert(0, os.path.join(MARK_LIV, "core"))
import numpy as np
import avatar_mesh as am

am._SKULL_RINGS = 7
am._NECK_SEGS = 14
am._NECK_RINGS = 9

mesh = am.build_head()
V = mesh["verts"].astype(np.float64)
N = mesh["normals"].astype(np.float64)
F = mesh["faces"].astype(np.int64)
jaw, brow, lips, fade = (mesh[k].astype(np.float64) for k in ("jaw", "brow", "lips", "fade"))
group = mesh["face_group"].astype(np.float64)  # 1 head, 0 neck
n_face, n_head = mesh["n_face"], mesh["n_head"]
nv0 = len(V)
region = np.zeros(nv0, dtype=np.int64)
region[n_face:n_head] = 1
region[n_head:] = 2

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
        # keep the correction gentle so a poorly oriented normal cannot throw a vertex far off
        lin = (p0 + p1) / 2.0
        d = m - lin
        L = np.linalg.norm(d)
        cap = 0.25 * np.linalg.norm(p1 - p0)
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
    V2 = np.vstack([V, np.array(newV)])
    N2 = np.vstack([N, np.array(newN)])
    attrs2 = [np.concatenate([arr, np.array(newAttr[k])]) for k, arr in enumerate(attrs)]
    region2 = np.concatenate([region, np.array(newRegion)])
    return V2, N2, np.array(nf), attrs2, region2, np.array(ng)

# 1.0 face mask, 1.2 cranium sweep (no crease lines there), 0 neck
face_kind = group.copy()
for t, tri in enumerate(F):
    if group[t] > 0.5 and (region[tri] == 1).any():
        face_kind[t] = 1.2
group = face_kind
V, N, F, (jaw, brow, lips, fade), region, group = subdivide(V, N, F, [jaw, brow, lips, fade], region, group)
n_base = len(V)

# ---- hair ------------------------------------------------------------------------------------------------------
face_top = V[:n_face, 1].max()

def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)

def hairline(x, z):
    """y above which the head carries hair, for a point at (x, z)."""
    # centre a little lower, temples recessed, a slight wave so it does not read as a headband
    front = face_top - 0.05 - 0.05 * (1.0 - min(1.0, abs(x) / 0.72) ** 2) + 0.022 * np.sin(x * 23.0)
    # the line drops behind the temples and down to the nape
    drop = 1.05 * smoothstep(0.30, -0.65, z)
    # sideburns: hair comes lower at the sides than the forehead does
    side = 0.40 * smoothstep(0.42, 0.72, abs(x)) * smoothstep(0.55, 0.0, z)
    return front - drop - side

cent = V[F].mean(axis=1)
reg_f = region[F]
has_cranium = (reg_f == 1).any(axis=1)
has_neck = (reg_f == 2).any(axis=1)
cand = has_cranium & ~has_neck
above = cent[:, 1] > np.array([hairline(x, z) for x, z in zip(cent[:, 0], cent[:, 2])])
# drop the faces that are still part of the face mask (they cover the forehead skin)
hair_f = np.flatnonzero(cand & above)

used = np.unique(F[hair_f].ravel())
remap = -np.ones(len(V), dtype=np.int64)
remap[used] = np.arange(len(used))
HV = V[used].copy()
HN = N[used].copy()

# thickness: zero at the hairline, growing into the volume of a haircut; fuller on top and at the back
hl = np.array([hairline(x, z) for x, z in zip(HV[:, 0], HV[:, 2])])
rise = np.clip((HV[:, 1] - hl) / 0.32, 0.0, 1.0)
taper = smoothstep(0.0, 1.0, rise)
crown = smoothstep(0.35, 0.95, HV[:, 1])
back = smoothstep(0.1, -0.7, HV[:, 2])
side_bulge = smoothstep(0.30, 0.70, np.abs(HV[:, 0]))
thick = 0.014 + taper * (0.045 + 0.055 * crown + 0.032 * back + 0.030 * side_bulge)
HV = HV + HN * thick[:, None]
HF = remap[F[hair_f]]

# ---- strands: flowing lines on the hair shell, from the crown whorl towards the hairline -------------------------
rng = np.random.default_rng(7)
whorl = np.array([0.06, 0.98, -0.42])
n_strands, strand_len = 120, 22
S_pts, S_nrm = [], []
hair_cent = HV[HF].mean(axis=1)
for s in range(n_strands):
    ang = 2 * np.pi * (s + rng.uniform(-0.3, 0.3)) / n_strands
    # a target on the hairline, picked by direction from the whorl
    d = np.array([np.sin(ang), -0.15, np.cos(ang)])
    # walk down the surface: start at the whorl, go outwards while following the shell height
    pts, nrm = [], []
    for k in range(strand_len):
        u = k / (strand_len - 1)
        # candidate point: whorl pushed along d, then snapped to the nearest shell vertex
        swirl = 0.35 * u * np.sin(ang * 2.0 + 1.0)
        dd = np.array([np.sin(ang + swirl), -0.15, np.cos(ang + swirl)])
        target = whorl + dd * (0.10 + 1.20 * u ** 0.9) + np.array([0, -0.55 * u ** 1.5, 0])
        j = np.argmin(((HV - target) ** 2).sum(axis=1))
        pts.append(HV[j])
        nrm.append(HN[j])
    pts = np.array(pts)
    nrm = np.array(nrm)
    for _ in range(3):  # moving average removes the zig-zag of snapping to mesh vertices
        sm = pts.copy()
        sm[1:-1] = 0.25 * pts[:-2] + 0.5 * pts[1:-1] + 0.25 * pts[2:]
        pts = sm
    # keep the strand on the outside of the shell: push it out along the normal by the height the smoothing lost
    pts = pts + nrm * 0.012
    S_pts += list(pts)
    S_nrm += list(nrm)
S_pts = np.array(S_pts)
S_nrm = np.array(S_nrm)

# ---- assemble --------------------------------------------------------------------------------------------------
nh = len(HV)
ns = len(S_pts)
allV = np.vstack([V, HV, S_pts])
allN = np.vstack([N, HN, S_nrm])
zeros = lambda n: np.zeros(n)
allJaw = np.concatenate([jaw, zeros(nh + ns)])
allBrow = np.concatenate([brow, zeros(nh + ns)])
allLips = np.concatenate([lips, zeros(nh + ns)])
hair_fade = 0.50 + 0.50 * smoothstep(0.0, 0.9, rise)
allFade = np.concatenate([fade, hair_fade, np.ones(ns)])
allF = np.vstack([F, HF + n_base])
allGroup = np.concatenate([group, np.full(len(HF), 2.0)])
strand_base = n_base + nh

# wireframe edges are derived on the device now (creases and silhouette); the legacy list is left empty
edges = np.zeros((0, 2), dtype=np.int64)

out = bytearray()
out += b"JHM2"
out += struct.pack("<7i", len(allV), len(allF), len(edges), n_base, n_face, n_strands, strand_len)
out += struct.pack("<3i", strand_base, nh, len(hair_f))
out += struct.pack("<2f", float(allV[:n_base, 1].max()), float(allV[:n_base, 1].min()))
out += mesh["lip_centre"].astype("<f4").tobytes()
for arr in (allV, allN):
    out += arr.astype("<f4").tobytes()
for arr in (allJaw, allBrow, allLips, allFade):
    out += arr.astype("<f4").tobytes()
out += allGroup.astype("<f4").tobytes()
out += allF.astype("<i4").tobytes()
names = ["eye_l", "eye_r", "brow_l", "brow_r", "lips_out", "lips_in"]
out += struct.pack("<i", len(names))
for k in names:
    idx = mesh["landmarks"][k].astype("<i4")
    out += struct.pack("<i", len(idx)) + idx.tobytes()

dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "assets", "avatar")
open(os.path.join(dest, "head_mesh.bin"), "wb").write(bytes(out))
print("verts", len(allV), "(base", n_base, "hair", nh, "strand pts", ns, ") faces", len(allF), "hair faces", len(hair_f), "bytes", len(out))

# ---- preview ---------------------------------------------------------------------------------------------------
if len(sys.argv) > 2:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib.collections import PolyCollection
    fig, axes = plt.subplots(1, 3, figsize=(15, 6))
    views = [("front", 0, 1, 2), ("side", 2, 1, 0), ("back", 0, 1, 2)]
    for ax, (name, ix, iy, iz) in zip(axes, views):
        for grp, col, alpha in ((1.0, "#2aa", 0.6), (2.0, "#a52", 0.55)):
            sel = np.flatnonzero(allGroup == grp)
            tri = allV[allF[sel]]
            sign = -1 if name == "back" else 1
            depth = tri[:, :, iz].mean(axis=1) * (-sign if name != "side" else 1)
            order = np.argsort(depth)
            polys = [np.c_[tri[i, :, ix] * (sign if name != "side" else 1), tri[i, :, iy]] for i in order]
            ax.add_collection(PolyCollection(polys, facecolor=col, edgecolor="k", linewidth=0.15, alpha=alpha))
        sp = allV[strand_base:strand_base + ns].reshape(n_strands, strand_len, 3)
        for line in sp:
            ax.plot(line[:, ix] * (-1 if name == "back" else 1), line[:, iy], color="w", lw=0.5)
        ax.set_xlim(-1.3, 1.3); ax.set_ylim(-1.4, 1.2); ax.set_aspect("equal"); ax.set_title(name)
        ax.set_facecolor("#111")
    plt.savefig("hair_preview.png", dpi=80)
    print("preview saved")
