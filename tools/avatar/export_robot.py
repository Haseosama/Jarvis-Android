"""Builds the robot that stands on the table in augmented reality (app/src/main/assets/avatar/robot_mesh.bin) from "Chrome Mini Robot – 3D
Model" by PurplePoint (https://sketchfab.com/3d-models/chrome-mini-robot-3d-model-fc76081a321f4ac1acb6b60e046a6a94), CC BY 4.0,
downloaded from Sketchfab as glTF.

Usage: python export_robot.py <folder with scene.gltf, scene.bin and textures> ../../app/src/main/assets/avatar/robot_mesh.bin
Needs numpy, Pillow and fast-simplification (quadric edge collapse, pip install fast-simplification).

The model is one textured surface of 40 000 triangles with no skeleton, 2 units tall (y from -1 at the soles to +1 at the top of the
head), looking along +z. It is welded, its texture baked into a colour per vertex (the mean of the texture over the triangles round it,
so the painted lines and the eyes survive), then simplified once per polygon level. Each vertex gets which part of the robot moves it
(the body, the head, an arm, a leg), its side, and how much it follows that part rather than the body (soft near the joints, so the
surface stretches a little there instead of tearing), and how much it glows (the eyes and the green trim, from the texture's colour).

File (little-endian): "JRB1", int32 levels; the joints, 3 float32 each: the neck, the left shoulder, the right shoulder, the left hip,
the right hip (x > 0 is the robot's left); then per level: int32 vertices V, int32 triangles T, V x 3 float32 positions, V x 3 int8
normals (x127), V x 3 uint8 colour (RGB), V uint8 glow (x255), V uint8 eye (x255), V uint8 part (0 body, 1 head, 2 arm, 3 leg), V int8
side (-1, 0, 1), V uint8 weight (x255), T x 3 uint16 vertex indices.
"""
import struct, sys
import numpy as np
import fast_simplification as fs
from PIL import Image
from gltf_scene import Scene

SRC, OUT = sys.argv[1], sys.argv[2]

# the joints, in the model's units (read off its front and side views)
NECK = (0.0, 0.08, -0.02)
SHOULDER = (0.62, -0.08, -0.04)
HIP = (0.33, -0.64, -0.02)
# Eco, Léger, Standard, Haute définition (Ultra is Haute définition): the whole model at the top
LEVELS = [10_000, 16_000, 24_000, 40_000]
BODY, HEAD, ARM, LEG = range(4)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def normals(P, F):
    n = np.cross(P[F[:, 1]] - P[F[:, 0]], P[F[:, 2]] - P[F[:, 0]])
    N = np.zeros_like(P)
    for j in range(3): np.add.at(N, F[:, j], n)
    return N / np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-12)


def compact(P, F):
    used = np.unique(F)
    remap = -np.ones(len(P), dtype=np.int64); remap[used] = np.arange(len(used))
    return P[used], remap[F]


scene = Scene(SRC)
prim = scene.primitives()[0]
P0, F0, UV = prim["P"], prim["F"], prim["UV"]
tex = np.asarray(Image.open(scene.material(prim["material"])["tex"]).convert("RGB")).astype(np.float64) / 255.0
th, tw = tex.shape[:2]

# the texture's colour over each triangle: a few points spread over it, each in its own UVs (so the texture's seams do not matter)
BARY = np.array([[1, 1, 1], [4, 1, 1], [1, 4, 1], [1, 1, 4], [2, 2, 1], [2, 1, 2], [1, 2, 2]], dtype=np.float64)
BARY /= BARY.sum(1, keepdims=True)
uv = np.einsum("kj,tjc->tkc", BARY, UV[F0])                         # (T, 7, 2)
px = np.clip((uv[..., 0] % 1.0) * tw, 0, tw - 1).astype(np.int64)
py = np.clip((uv[..., 1] % 1.0) * th, 0, th - 1).astype(np.int64)  # glTF: v down from the image's top
tri_colour = tex[py, px].mean(1)                                    # (T, 3)

# welded by position: the seams' duplicate vertices made one, each vertex the mean colour of its triangles weighted by their area
_, inv = np.unique(np.round(P0 / 1e-5).astype(np.int64), axis=0, return_inverse=True)
inv = inv.reshape(-1)
P = np.zeros((inv.max() + 1, 3)); P[inv] = P0
F = inv[F0]
keep = (F[:, 0] != F[:, 1]) & (F[:, 1] != F[:, 2]) & (F[:, 0] != F[:, 2])
F, tri_colour = F[keep], tri_colour[keep]
area = 0.5 * np.linalg.norm(np.cross(P[F[:, 1]] - P[F[:, 0]], P[F[:, 2]] - P[F[:, 0]]), axis=1)
C = np.zeros((len(P), 3)); wsum = np.zeros(len(P))
for j in range(3):
    np.add.at(C, F[:, j], tri_colour * area[:, None]); np.add.at(wsum, F[:, j], area)
C /= np.maximum(wsum, 1e-12)[:, None]
print("model:", len(P), "vertices,", len(F), "triangles")


def glow_of(c):
    """How much a colour is the robot's own light: the eyes' teal and the trim's green, bright and saturated."""
    r, g, b = c[:, 0], c[:, 1], c[:, 2]
    sat = np.max(c, 1) - np.min(c, 1)
    greenish = g - np.maximum(r, 0.6 * b)
    return smoothstep(0.10, 0.30, greenish) * smoothstep(0.25, 0.55, g) * smoothstep(0.12, 0.30, sat)


def rig(Q):
    """Which part moves each point, its side, and how much it follows that part rather than the body."""
    x, y, z = Q[:, 0], Q[:, 1], Q[:, 2]
    ax = np.abs(x)
    side = np.where(x >= 0, 1, -1)
    part = np.full(len(Q), BODY); w = np.zeros(len(Q)); s = np.zeros(len(Q), dtype=np.int64)
    # the head above the neck's ring
    head = smoothstep(0.04, 0.14, y)
    # the arms: out beyond the body's sides, from the shoulder's ball down to the fingers (the hands hang beside the feet, further out)
    arm = smoothstep(0.50, 0.56, ax) * (1 - smoothstep(0.02, 0.10, y)) * np.where(y < -0.76, smoothstep(0.57, 0.61, ax), 1.0)
    # the legs: under the body, between the arms
    leg = (1 - smoothstep(-0.74, -0.60, y)) * (1 - arm) * (ax < 0.60)
    for p, wt in ((HEAD, head), (ARM, arm), (LEG, leg)):
        m = wt > np.maximum(w, 0.0)
        part[m] = p; w[m] = wt[m]
    s[(part == ARM) | (part == LEG)] = side[(part == ARM) | (part == LEG)]
    return part, s, w


def eyes(Q, glow):
    """How much each point is an eye's light (the two discs on the face), for the blinks."""
    x, y, z = Q[:, 0], Q[:, 1], Q[:, 2]
    d = np.sqrt((np.abs(x) - 0.30) ** 2 + (y - 0.41) ** 2)
    return glow * (1 - smoothstep(0.13, 0.17, d)) * (z > 0.1)


def nearest(src, dst):
    """For each point of dst, the index of the nearest point of src (a grid search)."""
    cell = 0.05
    keys = np.floor(src / cell).astype(np.int64)
    grid = {}
    for i, k in enumerate(map(tuple, keys)): grid.setdefault(k, []).append(i)
    out = np.zeros(len(dst), dtype=np.int64)
    for j, p in enumerate(dst):
        k = np.floor(p / cell).astype(np.int64)
        r = 1
        while True:
            cand = [i for dx in range(-r, r + 1) for dy in range(-r, r + 1) for dz in range(-r, r + 1)
                    for i in grid.get((k[0] + dx, k[1] + dy, k[2] + dz), [])]
            if cand: break
            r += 1
        cand = np.array(cand)
        out[j] = cand[np.argmin(np.linalg.norm(src[cand] - p, axis=1))]
    return out


out = bytearray(b"JRB1") + struct.pack("<i", len(LEVELS))
for j in (NECK, SHOULDER, (-SHOULDER[0], SHOULDER[1], SHOULDER[2]), HIP, (-HIP[0], HIP[1], HIP[2])):
    out += struct.pack("<3f", *j)
for T in LEVELS:
    if T >= len(F):
        pl, fl = P.copy(), F.copy()
        cl = C
    else:
        pl, fl = fs.simplify(P.astype(np.float32), F.astype(np.int32), target_reduction=1 - T / len(F))
        pl, fl = compact(pl.astype(np.float64), fl.astype(np.int64))
        # each vertex takes the colour round its nearest original vertices (the mean of the three nearest would blur the lines)
        cl = C[nearest(P, pl)]
    nl = normals(pl, fl)
    glow = glow_of(cl)
    part, side, w = rig(pl)
    eye = eyes(pl, glow)
    print(f"level {T}: {len(pl)} vertices, {len(fl)} triangles; head {np.sum(part == HEAD)}, arms {np.sum(part == ARM)},"
          f" legs {np.sum(part == LEG)}, glowing {np.sum(glow > 0.5)}, eyes {np.sum(eye > 0.5)}")
    assert len(pl) < 65536
    out += struct.pack("<ii", len(pl), len(fl))
    out += pl.astype("<f4").tobytes()
    out += np.round(nl * 127).astype(np.int8).tobytes()
    out += np.round(np.clip(cl, 0, 1) * 255).astype(np.uint8).tobytes()
    out += np.round(glow * 255).astype(np.uint8).tobytes()
    out += np.round(eye * 255).astype(np.uint8).tobytes()
    out += part.astype(np.uint8).tobytes()
    out += side.astype(np.int8).tobytes()
    out += np.round(w * 255).astype(np.uint8).tobytes()
    out += fl.astype("<u2").tobytes()
open(OUT, "wb").write(out)
print("wrote", OUT, len(out), "bytes")
