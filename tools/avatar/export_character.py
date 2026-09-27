"""Builds a textured character for the avatar (format JCH1) from a glTF scene downloaded from Sketchfab.

Usage: python export_character.py <config.json> <folder with scene.gltf> [--measure] [--out <assets folder>]
Needs numpy, scipy and Pillow. The configs are in tools/avatar/characters/; see README "Textured characters".

What is built, in the units of the other heads (crown y +1, chin -1, the nose tip at z 0.68):
  * the bust: the head and what is above the config's cut (a little of the shoulders), the arms beyond |x| = cut_x left out;
  * simplified when it is heavy, by clustering on a grid (finer on the face), per UV island so the texture never smears across a seam,
    one shared position per cell so the islands stay stitched;
  * one texture atlas: the part of each material's texture the bust uses, packed together (with the material's colour factor);
  * per vertex: normal, atlas uv, head weight (how much it turns with the head: the head and the hair, not the shoulders), jaw weight
    (the chin and the lower lip drop with the mouth), and whether it is lit (unlit anime materials keep their painted shading);
  * meta.json: the label, the credit, where the eyes and the mouth are (the app draws the lids and the open mouth over the texture).
Written to <assets>/avatar/characters/<id>/ (mesh.bin, atlas.webp, meta.json). The public ones go to app/src/main/assets, the others
(config "public": false) to app/src/debug/assets, which git ignores: they are only in a local debug build.
"""
import json, os, struct, sys
import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gltf_scene import Scene
import raster

Image.MAX_IMAGE_PIXELS = None
ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
NOSE_Z = 0.6796


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def load_texture(path, factor, alpha_mode, cache={}):
    key = (path, tuple(factor), alpha_mode)
    if key not in cache:
        if path and os.path.exists(path):
            im = Image.open(path).convert("RGBA")
            a = np.asarray(im).astype(np.float32) / 255.0
        else:
            a = np.ones((4, 4, 4), np.float32)
        a = a * np.array(factor, np.float32)
        if alpha_mode == "OPAQUE":
            a[..., 3] = 1.0
        cache[key] = a
    return cache[key]


# ---- 1. the scene, turned and normalised -------------------------------------------------------------------------------------
cfg = json.load(open(sys.argv[1], encoding="utf-8"))
SRC = sys.argv[2]
MEASURE = "--measure" in sys.argv
sc = Scene(SRC)
prims = sc.primitives()


def wanted(k, p):
    mat = sc.material(p["material"])["name"]
    for e in cfg.get("exclude", []):
        if (isinstance(e, int) and e == k) or (isinstance(e, str) and (e in p["name"] or e in mat)):
            return False
    return True


prims = [p for k, p in enumerate(prims) if wanted(k, p)]
yaw = np.radians(cfg.get("yaw", 0.0))
R = np.array([[np.cos(yaw), 0, np.sin(yaw)], [0, 1, 0], [-np.sin(yaw), 0, np.cos(yaw)]])
for p in prims:
    p["P"] = p["P"] @ R.T

if "eye_y" in cfg:
    # the face framed as the other heads': the chin at y -1, the eyes at -0.05 (anime heads, whose eyes sit low in a big skull, come out
    # with a higher crown), the nose tip at z 0.68 (the frontmost point between the eyes and the chin, near the middle)
    s = 0.95 / (cfg["eye_y"] - cfg["chin"])
    allP = np.vstack([p["P"] for p in prims])
    near = (allP[:, 1] > cfg["chin"]) & (allP[:, 1] < cfg["eye_y"]) & (np.abs(allP[:, 0] - cfg["centre_x"]) < 0.5 * (cfg["eye_y"] - cfg["chin"]))
    nose_z = cfg.get("nose_z", float(allP[near, 2].max()))
    for p in prims:
        P = p["P"]
        p["P"] = np.c_[(P[:, 0] - cfg["centre_x"]) * s, (P[:, 1] - cfg["chin"]) * s - 1.0, (P[:, 2] - nose_z) * s + NOSE_Z]
    norm = True
else:
    norm = False

# ---- 2. the bust: triangles above the cut, the arms left out ---------------------------------------------------------------------
cut = cfg.get("cut", -1.9) if norm else -1e9
cut_x = cfg.get("cut_x", 1.9) if norm else 1e9
box = cfg.get("raw_box") if not norm else None
V_l, UV_l, F_l, M_l, I_l = [], [], [], [], []
mats = []
mat_index = {}
off = 0
import scipy.sparse as sps, scipy.sparse.csgraph as csg
for p in prims:
    P, F = p["P"], p["F"]
    keep = (P[F][:, :, 1] > cut).all(1) & (np.abs(P[F][:, :, 0]) < cut_x).all(1)
    if box is not None:
        c = P[F].mean(1)
        keep &= (c[:, 0] > box[0]) & (c[:, 0] < box[1]) & (c[:, 1] > box[2]) & (c[:, 1] < box[3])
    if not keep.any():
        continue
    F = F[keep]
    used = np.unique(F)
    remap = -np.ones(len(P), np.int64); remap[used] = np.arange(len(used))
    F = remap[F]
    m = sc.material(p["material"])
    key = (m["tex"], m["factor"], m["alpha"], m["name"])
    if key not in mat_index:
        mat_index[key] = len(mats); mats.append(m)
    # the UV islands: triangles joined by shared vertices (the exporter splits vertices along the seams)
    n = len(used)
    A = sps.coo_matrix((np.ones(3 * len(F)), (np.r_[F[:, 0], F[:, 1], F[:, 2]], np.r_[F[:, 1], F[:, 2], F[:, 0]])), shape=(n, n))
    _, isl = csg.connected_components(A, directed=False)
    V_l.append(P[used]); UV_l.append(p["UV"][used]); F_l.append(F + off); M_l.append(np.full(len(F), mat_index[key])); I_l.append(isl + (max((i.max() for i in I_l), default=-1) + 1))
    off += n
V = np.vstack(V_l); UV = np.vstack(UV_l); F = np.vstack(F_l); FM = np.concatenate(M_l); ISL = np.concatenate(I_l)
VM = np.zeros(len(V), np.int64); VM[F[:, 0]] = FM; VM[F[:, 1]] = FM; VM[F[:, 2]] = FM
print("bust", len(V), "vertices", len(F), "triangles,", len(mats), "materials")

textures = [load_texture(m["tex"], m["factor"], m["alpha"]) for m in mats]


def light_of(V, F):
    n = np.zeros_like(V)
    fn = np.cross(V[F[:, 1]] - V[F[:, 0]], V[F[:, 2]] - V[F[:, 0]])
    for k in range(3):
        np.add.at(n, F[:, k], fn)
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
    L = np.array([-0.45, 0.5, 0.75]); L /= np.linalg.norm(L)
    return n, 0.55 + 0.45 * np.abs(n @ L)


if MEASURE:
    # renders to read the features from: the whole bust and the face, from the front, with a grid in the current units
    name = cfg["id"]
    tex_small = []
    for t in textures:
        im = Image.fromarray((t * 255).astype(np.uint8)); im.thumbnail((2048, 2048)); tex_small.append(np.asarray(im).astype(np.float32) / 255)
    _, lt = light_of(V, F)
    lt[:] = 1.0 if cfg.get("unlit_all") else lt
    views = cfg.get("measure_views") or ([[-1.9, 1.9, -2.0, 1.25, 0.25], [-0.75, 0.75, -1.05, 0.55, 0.05]] if norm else [box + [0.02]])
    for k, (x0, x1, y0, y1, step) in enumerate(views):
        Wd = 1100; sx = Wd / (x1 - x0); Hd = int((y1 - y0) * sx)
        to_px = lambda x, y: ((x - x0) * sx, (y1 - y) * sx)
        im = raster.render(V, F, UV, FM, tex_small, lt, Wd, Hd, to_px)
        raster.grid(im, to_px, (x0, x1), (y0, y1), step).save(os.path.join(os.environ.get("TMP_OUT", "."), "measure_%s_%d.png" % (name, k)))
        # the profile, the face to the right
        Pz = np.c_[V[:, 2], V[:, 1], -V[:, 0]]
        zx0, zx1 = (V[:, 2].min(), V[:, 2].max())
        zs = Wd / max(zx1 - zx0, 1e-6) * 0.5
        sx2 = min(sx, zs * 2)
        to_px2 = lambda x, y: ((x - zx0) * sx2, (y1 - y) * sx2)
        im2 = raster.render(Pz, F, UV, FM, tex_small, lt, int((zx1 - zx0) * sx2) + 2, int((y1 - y0) * sx2), to_px2)
        raster.grid(im2, to_px2, (zx0, zx1), (y0, y1), step).save(os.path.join(os.environ.get("TMP_OUT", "."), "measure_%s_%d_side.png" % (name, k)))
    sys.exit(0)

# ---- 3. simplification by clustering, per UV island ---------------------------------------------------------------------------
target = cfg.get("max_tris", 40000)
keep_raw = np.zeros(len(F), bool)
for kname in cfg.get("keep", []):
    for mi, m in enumerate(mats):
        if kname in m["name"]:
            keep_raw |= FM == mi
if len(F) > target:
    fc, bc = cfg.get("cells", [0.014, 0.045])
    fb = cfg.get("face_box", [-0.75, 0.75, -1.15, 1.05])     # x0 x1 y0 y1 of the finer grid

    def cluster(fc, bc):
        on_face = (V[:, 0] > fb[0]) & (V[:, 0] < fb[1]) & (V[:, 1] > fb[2]) & (V[:, 1] < fb[3])
        cell = np.where(on_face, fc, bc)
        g = np.floor((V - V.min(0)) / cell[:, None]).astype(np.int64)
        ck = (g[:, 0] * 1_000_003 + g[:, 1]) * 1_000_003 + g[:, 2]
        ck = np.where(on_face, -ck - 1, ck)
        kept_v = np.zeros(len(V), bool); kept_v[F[keep_raw].ravel()] = True
        ck = np.where(kept_v, 10 ** 17 + np.arange(len(V)), ck)          # the kept vertices are cells of their own
        _, cell_id = np.unique(ck, return_inverse=True)
        return cell_id.reshape(-1)

    fc0, bc0 = fc, bc
    for attempt in range(12):
        cell_id = cluster(fc, bc)
        cl_key = cell_id.astype(np.int64) * (ISL.max() + 1) + ISL
        _, cl = np.unique(cl_key, return_inverse=True); cl = cl.reshape(-1)
        nf = cl[F]
        cf = cell_id[F]
        good = (cf[:, 0] != cf[:, 1]) & (cf[:, 1] != cf[:, 2]) & (cf[:, 0] != cf[:, 2])
        n_after = len(np.unique(np.sort(nf[good], axis=1), axis=0))
        print("cells %.4f / %.4f -> %d triangles" % (fc, bc, n_after))
        if n_after <= target:
            break
        fc *= 1.12; bc *= 1.12
    ncl = cl.max() + 1; ncell = cell_id.max() + 1
    # one position per cell (the mean of its vertices, all islands together), one uv per cluster (the mean of its island's)
    cnt = np.bincount(cell_id, minlength=ncell).astype(float)
    cpos = np.stack([np.bincount(cell_id, V[:, k], ncell) for k in range(3)], 1) / cnt[:, None]
    ccount = np.bincount(cl, minlength=ncl).astype(float)
    cuv = np.stack([np.bincount(cl, UV[:, k], ncl) for k in range(2)], 1) / ccount[:, None]
    cl_cell = np.zeros(ncl, np.int64); cl_cell[cl] = cell_id
    cl_mat = np.zeros(ncl, np.int64); cl_mat[cl] = VM
    nf = nf[good]; FM2 = FM[good]
    _, first = np.unique(np.sort(nf, axis=1), axis=0, return_index=True)
    first = np.sort(first)
    F = nf[first]; FM = FM2[first]
    used = np.unique(F); remap = -np.ones(ncl, np.int64); remap[used] = np.arange(len(used))
    V = cpos[cl_cell[used]]; UV = cuv[used]; VM = cl_mat[used]; F = remap[F]
    print("simplified to", len(V), "vertices", len(F), "triangles")

N, lit_light = light_of(V, F)

# ---- 4. the atlas ----------------------------------------------------------------------------------------------------------------
tiles = []
for mi, m in enumerate(mats):
    vs = np.unique(F[FM == mi])
    if len(vs) == 0:
        tiles.append(None); continue
    t = textures[mi]; th, tw = t.shape[:2]
    if m["tex"] is None:
        tiles.append(dict(mi=mi, u0=0.0, u1=1.0, v0=0.0, v1=1.0, w=8, h=8, plain=True)); continue
    u0, u1 = UV[vs, 0].min(), UV[vs, 0].max(); v0, v1 = UV[vs, 1].min(), UV[vs, 1].max()
    tiles.append(dict(mi=mi, u0=u0, u1=u1, v0=v0, v1=v1, w=max(8, (u1 - u0) * tw), h=max(8, (v1 - v0) * th), plain=False))
S = cfg.get("atlas", 2048)
PAD = 4


def pack(scale):
    x = y = row = 0
    place = {}
    for t in sorted([t for t in tiles if t], key=lambda t: -t["h"] * (1 if t["plain"] else scale)):
        w = int(np.ceil(t["w"] * (1 if t["plain"] else scale))) + 2 * PAD
        h = int(np.ceil(t["h"] * (1 if t["plain"] else scale))) + 2 * PAD
        if w > S:
            return None
        if x + w > S:
            x, y, row = 0, y + row, 0
        if y + h > S:
            return None
        place[t["mi"]] = (x + PAD, y + PAD, w - 2 * PAD, h - 2 * PAD)
        x += w; row = max(row, h)
    return place


lo_s, hi_s = 0.01, 1.0
place = pack(hi_s)
if place is None:
    for _ in range(30):
        mid_s = (lo_s + hi_s) / 2
        if pack(mid_s) is None: hi_s = mid_s
        else: lo_s = mid_s
    place = pack(lo_s)
    print("atlas: textures scaled by %.3f" % lo_s)
atlas = np.zeros((S, S, 4), np.float32)
AUV = np.zeros((len(V), 2))
for t in tiles:
    if not t:
        continue
    x, y, w, h = place[t["mi"]]
    tex = textures[t["mi"]]; th, tw = tex.shape[:2]
    if t["plain"]:
        atlas[y - PAD:y + h + PAD, x - PAD:x + w + PAD] = tex[0, 0]
    else:
        # sample the used part of the texture (wrapping) into the tile, and the padding around it with its edge
        gx = t["u0"] + (np.arange(-PAD, w + PAD) + 0.5) / w * (t["u1"] - t["u0"])
        gy = t["v0"] + (np.arange(-PAD, h + PAD) + 0.5) / h * (t["v1"] - t["v0"])
        gx = np.clip(gx, t["u0"], t["u1"]); gy = np.clip(gy, t["v0"], t["v1"])
        ix = (np.mod(gx, 1.0) * tw).astype(int).clip(0, tw - 1); iy = (np.mod(gy, 1.0) * th).astype(int).clip(0, th - 1)
        big = (th * (t["v1"] - t["v0"]) > 1.5 * h)
        if big:
            # shrinking a lot: a box filter first, so the tile is not aliased
            src = Image.fromarray((tex * 255).astype(np.uint8))
            cx0, cx1 = int(t["u0"] * tw), int(np.ceil(t["u1"] * tw)); cy0, cy1 = int(t["v0"] * th), int(np.ceil(t["v1"] * th))
            if 0 <= cx0 and cx1 <= tw and 0 <= cy0 and cy1 <= th:
                crop = src.crop((cx0, cy0, max(cx1, cx0 + 1), max(cy1, cy0 + 1))).resize((w, h), Image.LANCZOS)
                a = np.asarray(crop).astype(np.float32) / 255
                atlas[y:y + h, x:x + w] = a
                atlas[y - PAD:y, x:x + w] = a[:1]; atlas[y + h:y + h + PAD, x:x + w] = a[-1:]
                atlas[y - PAD:y + h + PAD, x - PAD:x] = atlas[y - PAD:y + h + PAD, x:x + 1]
                atlas[y - PAD:y + h + PAD, x + w:x + w + PAD] = atlas[y - PAD:y + h + PAD, x + w - 1:x + w]
            else:
                big = False
        if not big:
            atlas[y - PAD:y + h + PAD, x - PAD:x + w + PAD] = tex[iy][:, ix]
    sel = VM == t["mi"]
    du = max(t["u1"] - t["u0"], 1e-9); dv = max(t["v1"] - t["v0"], 1e-9)
    AUV[sel, 0] = (x + (UV[sel, 0] - t["u0"]) / du * w) / S
    AUV[sel, 1] = (y + (UV[sel, 1] - t["v0"]) / dv * h) / S
    if t["plain"]:
        AUV[sel] = [(x + w / 2) / S, (y + h / 2) / S]
used_h = max(p[1] + p[3] for p in place.values()) + PAD
Hs = int(2 ** np.ceil(np.log2(max(used_h, 16))))
atlas = atlas[:Hs]
AUV[:, 1] *= S / Hs

# ---- 5. the rig ----------------------------------------------------------------------------------------------------------------
x, y, z = V[:, 0], V[:, 1], V[:, 2]
n0, n1 = cfg.get("neck", [-1.30, -1.0])
headW = 0.12 + 0.88 * smoothstep(n0, n1, y)
for kname in cfg.get("head_parts", []):
    for mi, m in enumerate(mats):
        if kname in m["name"]:
            headW[VM == mi] = 1.0
mcx, mcy, mhw, mcurve = cfg["mouth"][:4]
mslope = cfg["mouth"][4] if len(cfg["mouth"]) > 4 else 0.0          # a head turned or tilted: the mouth line leans


def seam_y(xx):
    return mcy + mslope * (xx - mcx) + mcurve * (xx - mcx) ** 2


seam = seam_y(x)
dy = y - seam
mw = 1.0 - smoothstep(mhw * 0.9, mhw * 1.35, np.abs(x - mcx))
front = z > cfg.get("front_z", 0.25)
jaw = np.clip((mcy - y) / (mcy - (-1.0)), 0.0, 1.0) ** 0.8
jaw *= smoothstep(-0.05, 0.45, z) * (1.0 - smoothstep(-1.02, -1.22, y))
lower = front & (dy <= 0.0) & (dy > -0.22)
jaw[lower] = np.maximum(jaw[lower], 0.92 * mw[lower] * (1.0 - smoothstep(0.08, 0.22, -dy[lower])))
upper = front & (dy > 0.0)
jaw[upper] = 0.0
jaw[headW < 0.9] *= smoothstep(0.12, 0.9, headW[headW < 0.9])
unlit = np.zeros(len(V), bool)
for mi, m in enumerate(mats):
    if cfg.get("unlit_all") or any(k in m["name"] for k in cfg.get("unlit", [])):
        unlit |= VM == mi

# where the features are on the surface (the frontmost hit), and the colour of the skin over the eyes, for the lids
Fc = V[F]


def hit(px, py):
    a, b, c = Fc[:, 0], Fc[:, 1], Fc[:, 2]
    den = (b[:, 1] - c[:, 1]) * (a[:, 0] - c[:, 0]) + (c[:, 0] - b[:, 0]) * (a[:, 1] - c[:, 1])
    ok = np.abs(den) > 1e-12
    l0 = np.where(ok, ((b[:, 1] - c[:, 1]) * (px - c[:, 0]) + (c[:, 0] - b[:, 0]) * (py - c[:, 1])) / np.where(ok, den, 1), -1)
    l1 = np.where(ok, ((c[:, 1] - a[:, 1]) * (px - c[:, 0]) + (a[:, 0] - c[:, 0]) * (py - c[:, 1])) / np.where(ok, den, 1), -1)
    l2 = 1 - l0 - l1
    ins = (l0 >= 0) & (l1 >= 0) & (l2 >= 0)
    if not ins.any():
        return None
    zz = np.where(ins, l0 * a[:, 2] + l1 * b[:, 2] + l2 * c[:, 2], -1e9)
    t = int(np.argmax(zz))
    return zz[t], t, (l0[t], l1[t], l2[t])


def colour_at(px, py):
    h = hit(px, py)
    if h is None:
        return 0xFFC8A088
    _, t, (l0, l1, l2) = h
    u, v = l0 * AUV[F[t, 0]] + l1 * AUV[F[t, 1]] + l2 * AUV[F[t, 2]]
    c = atlas[int(np.clip(v * atlas.shape[0], 0, atlas.shape[0] - 1)), int(np.clip(u * S, 0, S - 1))]
    return 0xFF000000 | (int(c[0] * 255) << 16) | (int(c[1] * 255) << 8) | int(c[2] * 255)


eyes = []
for ex, ey, ehw, ehh, tilt in cfg["eyes"]:
    h = hit(ex, ey)
    ez = h[0] if h else 0.3
    lid = cfg.get("lid_colour")
    lid = int(lid, 16) if isinstance(lid, str) else colour_at(ex, ey + ehh * cfg.get("lid_sample", 1.9))
    eyes.append(dict(x=ex, y=ey, z=float(ez), hw=ehw, hh=ehh, tilt=tilt, lid=lid))
mouth_pts = []
for mx in np.linspace(mcx - mhw, mcx + mhw, 13):
    my = seam_y(mx)
    h = hit(mx, my)
    mouth_pts.append([float(mx), float(my), float(h[0] if h else 0.5)])

# ---- 6. out ---------------------------------------------------------------------------------------------------------------------
dest = os.path.join(ROOT, "app", "src", "main" if cfg.get("public") else "debug", "assets", "avatar", "characters", cfg["id"])
if "--out" in sys.argv:
    dest = os.path.join(sys.argv[sys.argv.index("--out") + 1], cfg["id"])
os.makedirs(dest, exist_ok=True)
nV, nF = len(V), len(F)
buf = bytearray(b"JCH1") + struct.pack("<II", nV, nF)
for arr in (V, N, AUV):
    buf += np.asarray(arr, np.float32).tobytes()
buf += headW.astype(np.float32).tobytes() + jaw.astype(np.float32).tobytes()
flags = unlit.astype(np.uint8).tobytes()
buf += flags + b"\x00" * ((4 - len(flags) % 4) % 4)
buf += F.astype(np.uint32).tobytes()
open(os.path.join(dest, "mesh.bin"), "wb").write(bytes(buf))
Image.fromarray((atlas.clip(0, 1) * 255).astype(np.uint8), "RGBA").save(os.path.join(dest, "atlas.webp"), "WEBP", quality=cfg.get("quality", 88), method=6)
meta = dict(label=cfg["label"], order=cfg.get("order", 50), credit=cfg["credit"], public=bool(cfg.get("public")), scale=cfg.get("scale", 0.86),
            cut=cut, top=float(cfg.get("fit_top", V[:, 1].max())), pivot=cfg.get("pivot", [0.0, -1.0, -0.15]), jaw_pivot=cfg.get("jaw_pivot", [0.0, 0.06, -0.34]),
            eyes=eyes, mouth=dict(points=mouth_pts, inner=int(cfg.get("mouth_colour", "FF2A1014"), 16), teeth=cfg.get("teeth", True),
                                  lip=cfg.get("lip_line")),
            lash=int(cfg.get("lash_colour", "FF1A1210"), 16), ambient=cfg.get("ambient", 0.55))
json.dump(meta, open(os.path.join(dest, "meta.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("written", dest, "vertices", nV, "triangles", nF, "atlas", S, "x", atlas.shape[0],
      "bytes", os.path.getsize(os.path.join(dest, "mesh.bin")), os.path.getsize(os.path.join(dest, "atlas.webp")))
if os.environ.get("JCH_DEBUG"):
    np.savez(os.environ["JCH_DEBUG"], V=V, F=F, AUV=AUV, headW=headW, jaw=jaw, unlit=unlit, N=N)
    Image.fromarray((atlas.clip(0, 1) * 255).astype(np.uint8), "RGBA").save(os.environ["JCH_DEBUG"] + ".png")
