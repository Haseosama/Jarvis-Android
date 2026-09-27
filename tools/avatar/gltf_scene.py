"""Reads a glTF 2.0 scene (scene.gltf + scene.bin + textures, as Sketchfab exports them) into plain numpy arrays.

Each primitive comes out in world space, in its rest pose: a static mesh through its node's world matrix, a skinned one through its joints
(the sum over its weights of joint world matrix x inverse bind matrix), which puts every part of a rigged character where it stands.
Used by prepare_sculpt.py's callers and export_character.py. Needs numpy.
"""
import json, os
import numpy as np

COMP = {5120: np.int8, 5121: np.uint8, 5122: np.int16, 5123: np.uint16, 5125: np.uint32, 5126: np.float32}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}


def _quat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def _local(node):
    if "matrix" in node:
        return np.array(node["matrix"], dtype=np.float64).reshape(4, 4).T
    m = np.eye(4)
    if "scale" in node:
        m = np.diag(list(node["scale"]) + [1.0]) @ m
    if "rotation" in node:
        r = np.eye(4); r[:3, :3] = _quat(node["rotation"]); m = r @ m
    if "translation" in node:
        t = np.eye(4); t[:3, 3] = node["translation"]; m = t @ m
    return m


class Scene:
    def __init__(self, folder):
        self.folder = folder
        self.j = json.load(open(os.path.join(folder, "scene.gltf"), encoding="utf-8"))
        self.bin = open(os.path.join(folder, self.j["buffers"][0].get("uri", "scene.bin")), "rb").read()
        self.world = {}
        for s in self.j["scenes"][self.j.get("scene", 0)]["nodes"]:
            self._walk(s, np.eye(4))

    def _walk(self, i, parent):
        w = parent @ _local(self.j["nodes"][i]); self.world[i] = w
        for c in self.j["nodes"][i].get("children", []):
            self._walk(c, w)

    def acc(self, i):
        a = self.j["accessors"][i]
        n = NCOMP[a["type"]]
        if "bufferView" not in a:
            return np.zeros((a["count"], n) if n > 1 else a["count"])
        bv = self.j["bufferViews"][a["bufferView"]]
        dt = np.dtype(COMP[a["componentType"]])
        start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
        stride = bv.get("byteStride", 0)
        if stride and stride != dt.itemsize * n:
            raw = np.frombuffer(self.bin, dtype=np.uint8, count=stride * (a["count"] - 1) + dt.itemsize * n, offset=start)
            arr = np.lib.stride_tricks.as_strided(raw, shape=(a["count"], dt.itemsize * n), strides=(stride, 1)).copy()
            arr = arr.view(dt).reshape(a["count"], n)
        else:
            arr = np.frombuffer(self.bin, dtype=dt, count=a["count"] * n, offset=start).reshape(a["count"], n)
        if a.get("normalized"):
            arr = arr.astype(np.float64) / np.iinfo(dt).max
        return arr if n > 1 else arr.reshape(-1)

    def _image(self, texinfo):
        t = self.j["textures"][texinfo["index"]]
        src = t.get("source")
        if src is None:
            src = t.get("extensions", {}).get("KHR_texture_webp", {}).get("source")
        return None if src is None else os.path.join(self.folder, self.j["images"][src]["uri"].replace("%20", " "))

    def material(self, mi):
        """A material's colour: dict(tex (file or None), factor RGBA, alpha ("OPAQUE" | "MASK" | "BLEND"), cutoff, double (sided), name,
        uv (offset, scale, rotation) of KHR_texture_transform, texcoord set, emissive_tex, emissive factor). The specular-glossiness
        extension's diffuse texture stands for the base colour."""
        out = dict(tex=None, factor=(1.0, 1.0, 1.0, 1.0), alpha="OPAQUE", cutoff=0.5, double=False, name="default",
                   uv=((0.0, 0.0), (1.0, 1.0), 0.0), texcoord=0, emissive_tex=None, emissive=(0.0, 0.0, 0.0))
        if mi is None:
            return out
        m = self.j["materials"][mi]
        pbr = m.get("pbrMetallicRoughness", {})
        sg = m.get("extensions", {}).get("KHR_materials_pbrSpecularGlossiness")
        info, factor = pbr.get("baseColorTexture"), pbr.get("baseColorFactor", (1.0, 1.0, 1.0, 1.0))
        if sg is not None and info is None:
            info, factor = sg.get("diffuseTexture"), sg.get("diffuseFactor", (1.0, 1.0, 1.0, 1.0))
        out.update(factor=tuple(factor), alpha=m.get("alphaMode", "OPAQUE"), cutoff=m.get("alphaCutoff", 0.5),
                   double=m.get("doubleSided", False), name=m.get("name", "m%d" % mi), emissive=tuple(m.get("emissiveFactor", (0.0, 0.0, 0.0))))
        if info is not None:
            out["tex"] = self._image(info)
            out["texcoord"] = info.get("texCoord", 0)
            tt = info.get("extensions", {}).get("KHR_texture_transform")
            if tt:
                out["uv"] = (tuple(tt.get("offset", (0.0, 0.0))), tuple(tt.get("scale", (1.0, 1.0))), tt.get("rotation", 0.0))
        if "emissiveTexture" in m:
            out["emissive_tex"] = self._image(m["emissiveTexture"])
        return out

    def primitives(self):
        """Every drawn primitive: dict(node, mesh, name, P (n,3) world, UV (n,2), F (m,3), material)."""
        out = []
        for ni, node in enumerate(self.j["nodes"]):
            if "mesh" not in node:
                continue
            mesh = self.j["meshes"][node["mesh"]]
            for pi, p in enumerate(mesh["primitives"]):
                if p.get("mode", 4) != 4:
                    continue
                P = self.acc(p["attributes"]["POSITION"]).astype(np.float64)
                if "skin" in node and "JOINTS_0" in p["attributes"]:
                    skin = self.j["skins"][node["skin"]]
                    ibm = self.acc(skin["inverseBindMatrices"]).reshape(-1, 4, 4).transpose(0, 2, 1) if "inverseBindMatrices" in skin \
                        else np.tile(np.eye(4), (len(skin["joints"]), 1, 1))
                    mats = np.array([self.world[jn] @ ibm[k] for k, jn in enumerate(skin["joints"])])
                    J = self.acc(p["attributes"]["JOINTS_0"]).astype(np.int64)
                    W = self.acc(p["attributes"]["WEIGHTS_0"]).astype(np.float64)
                    W = W / np.maximum(W.sum(1, keepdims=True), 1e-9)
                    Ph = np.c_[P, np.ones(len(P))]
                    acc_ = np.zeros((len(P), 4))
                    for k in range(J.shape[1]):
                        acc_ += W[:, k:k + 1] * np.einsum("nij,nj->ni", mats[J[:, k]], Ph)
                    P = acc_[:, :3]
                else:
                    P = (np.c_[P, np.ones(len(P))] @ self.world[ni].T)[:, :3]
                mat = self.material(p.get("material"))
                key = "TEXCOORD_%d" % mat["texcoord"]
                UV = self.acc(p["attributes"][key]).astype(np.float64) if key in p["attributes"] else np.zeros((len(P), 2))
                (ox, oy), (sx, sy), rot = mat["uv"]
                if (ox, oy, sx, sy, rot) != (0.0, 0.0, 1.0, 1.0, 0.0):
                    c, s = np.cos(rot), np.sin(rot)          # KHR_texture_transform: translation x rotation x scale
                    u, v = UV[:, 0] * sx, UV[:, 1] * sy
                    UV = np.c_[ox + c * u + s * v, oy - s * u + c * v]
                F = self.acc(p["indices"]).astype(np.int64).reshape(-1, 3) if "indices" in p else np.arange(len(P)).reshape(-1, 3)
                out.append(dict(node=ni, mesh=node["mesh"], prim=pi, name=node.get("name", "") + "/" + mesh.get("name", ""), P=P, UV=UV, F=F,
                                material=p.get("material")))
        return out
