"""Prepares the head Léa is built from: "Female Head Sculpt" by Aconear (CC BY 4.0,
https://sketchfab.com/3d-models/female-head-sculpt-ae24c33594a046519014fdc78758a8ec), downloaded from Sketchfab as glTF.

Usage: python prepare_sculpt.py <folder with scene.gltf and scene.bin> <out FemaleHeadSculpt.glb>
then:  JHM_FACE=lea python export_head.py <Mark-LIV> <out FemaleHeadSculpt.glb>
Needs numpy.

The sculpt is one surface of 1.36 million triangles in 42 pieces (the three small props of the file are left out), looking along +x.
It is welded, turned to look along +z like the scan, and simplified to about 22 000 triangles by vertex clustering with quadric error
metrics (Lindstrom 2000): the vertices of each cell of a grid merge into one, placed where the planes of their faces meet best, with a
finer grid on the face (eyes, nose, mouth) than on the skull, which the hair covers. Each new triangle is turned the way the original
surface faced. The simplified head is written as a minimal GLB (positions and indices), which export_head.py reads like the scan.
"""
import json, struct, sys
import numpy as np

SRC, OUT = sys.argv[1], sys.argv[2]
CELL, FACE_CELL = 0.06, 0.017            # the grid on the skull and on the face, in the sculpt's own units (about 2.8 tall with the neck)

j = json.load(open(SRC + "/scene.gltf"))
bin_ = open(SRC + "/scene.bin", "rb").read()


def acc(i):
    a = j["accessors"][i]; bv = j["bufferViews"][a["bufferView"]]
    start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    comp = {5126: np.float32, 5125: np.uint32, 5123: np.uint16}[a["componentType"]]
    n = {"SCALAR": 1, "VEC2": 2, "VEC3": 3}[a["type"]]
    arr = np.frombuffer(bin_, dtype=comp, count=a["count"] * n, offset=start)
    return arr.reshape(-1, n) if n > 1 else arr


def local(node):
    if "matrix" in node:
        return np.array(node["matrix"], dtype=np.float64).reshape(4, 4).T
    m = np.eye(4)
    if "scale" in node:
        m = np.diag(list(node["scale"]) + [1.0]) @ m
    if "translation" in node:
        t = np.eye(4); t[:3, 3] = node["translation"]; m = t @ m
    return m


world = {}


def walk(i, parent):
    w = parent @ local(j["nodes"][i]); world[i] = w
    for c in j["nodes"][i].get("children", []):
        walk(c, w)


for s in j["scenes"][j.get("scene", 0)]["nodes"]:
    walk(s, np.eye(4))

# ---- the surface, welded -----------------------------------------------------------------------------------------------------
Vs, Fs, off = [], [], 0
for ni, node in enumerate(j["nodes"]):
    if "mesh" not in node or node["mesh"] > 41:        # 42..44: a sphere and two planes, props of the file, not the head
        continue
    p = j["meshes"][node["mesh"]]["primitives"][0]
    P = acc(p["attributes"]["POSITION"]).astype(np.float64)
    P = (np.c_[P, np.ones(len(P))] @ world[ni].T)[:, :3]
    Vs.append(P); Fs.append(acc(p["indices"]).reshape(-1, 3).astype(np.int64) + off); off += len(P)
V = np.vstack(Vs); F = np.vstack(Fs)
_, idx, inv = np.unique(np.round(V / 1e-5).astype(np.int64), axis=0, return_index=True, return_inverse=True)
V = V[idx]; F = inv.reshape(-1)[F]
F = F[(F[:, 0] != F[:, 1]) & (F[:, 1] != F[:, 2]) & (F[:, 0] != F[:, 2])]
V = np.c_[-V[:, 2], V[:, 1], V[:, 0]]                  # it looks along +x: turned to look along +z
print("sculpt", len(V), "vertices", len(F), "triangles")

# ---- quadric clustering ------------------------------------------------------------------------------------------------------
lo = V.min(0)
on_face = (V[:, 2] > V[:, 2].max() - 0.42) & (V[:, 1] > 1.40) & (V[:, 1] < 2.85)
g = np.floor((V - lo) / np.where(on_face[:, None], FACE_CELL, CELL)).astype(np.int64)
key = g[:, 0] * 1_000_003 ** 2 + g[:, 1] * 1_000_003 + g[:, 2]
key = np.where(on_face, -key - 1, key)                  # the two grids never share a cell
_, cl = np.unique(key, return_inverse=True); cl = cl.reshape(-1); n = cl.max() + 1
a, b, c = V[F[:, 0]], V[F[:, 1]], V[F[:, 2]]
nrm = np.cross(b - a, c - a); area = np.linalg.norm(nrm, axis=1); nrm = nrm / np.maximum(area[:, None], 1e-15)
pl = np.c_[nrm, -np.sum(nrm * a, axis=1)]
Q = np.einsum("fi,fj->fij", pl, pl) * area[:, None, None]
Qc = np.zeros((n, 4, 4)); cn = np.zeros((n, 3))
for k in range(3):
    np.add.at(Qc, cl[F[:, k]], Q)
    np.add.at(cn, cl[F[:, k]], nrm * area[:, None])
mean = np.zeros((n, 3)); cnt = np.zeros(n)
np.add.at(mean, cl, V); np.add.at(cnt, cl, 1); mean /= cnt[:, None]
A = Qc[:, :3, :3] + np.eye(3)[None] * 1e-9
rhs = -Qc[:, :3, 3] - np.einsum("nij,nj->ni", A, mean)     # solved around the mean, a little damped where the planes are all alike
delta = np.linalg.solve(A + np.eye(3)[None] * 1e-6 * np.trace(A, axis1=1, axis2=2)[:, None, None], rhs[..., None])[..., 0]
size = np.full(n, CELL); size[np.unique(cl[on_face])] = FACE_CELL
pos = mean + np.where((np.linalg.norm(delta, axis=1) > 0.9 * size)[:, None], 0.0, delta)   # the mean when the best point strays
nf = cl[F]
nf = nf[(nf[:, 0] != nf[:, 1]) & (nf[:, 1] != nf[:, 2]) & (nf[:, 0] != nf[:, 2])]
tn = np.cross(pos[nf[:, 1]] - pos[nf[:, 0]], pos[nf[:, 2]] - pos[nf[:, 0]])
flip = np.sum(tn * (cn[nf[:, 0]] + cn[nf[:, 1]] + cn[nf[:, 2]]), axis=1) < 0
nf[flip] = nf[flip][:, [0, 2, 1]]
_, first = np.unique(np.sort(nf, axis=1), axis=0, return_index=True)
nf = nf[np.sort(first)]
used = np.unique(nf); remap = -np.ones(n, dtype=np.int64); remap[used] = np.arange(len(used))
V, F = pos[used].astype(np.float32), remap[nf].astype(np.uint32)
print("simplified", len(V), "vertices", len(F), "triangles")

# ---- a minimal GLB -----------------------------------------------------------------------------------------------------------
pad = lambda b_: b_ + b"\x00" * ((4 - len(b_) % 4) % 4)
pos_b, idx_b = pad(V.tobytes()), pad(F.tobytes())
gltf = {"asset": {"version": "2.0"}, "scene": 0, "scenes": [{"nodes": [0]}], "nodes": [{"mesh": 0}],
        "meshes": [{"primitives": [{"attributes": {"POSITION": 0}, "indices": 1}]}],
        "buffers": [{"byteLength": len(pos_b) + len(idx_b)}],
        "bufferViews": [{"buffer": 0, "byteOffset": 0, "byteLength": len(pos_b)}, {"buffer": 0, "byteOffset": len(pos_b), "byteLength": len(idx_b)}],
        "accessors": [{"bufferView": 0, "componentType": 5126, "count": len(V), "type": "VEC3", "min": V.min(0).tolist(), "max": V.max(0).tolist()},
                      {"bufferView": 1, "componentType": 5125, "count": int(F.size), "type": "SCALAR"}]}
js = json.dumps(gltf).encode(); js += b" " * ((4 - len(js) % 4) % 4)
body = pos_b + idx_b
open(OUT, "wb").write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(body)) + struct.pack("<I4s", len(js), b"JSON") + js
                      + struct.pack("<I4s", len(body), b"BIN\x00") + body)
print("written", OUT)
