"""Takes one hairstyle out of a Sketchfab hairstyle collection (several heads side by side, each wearing one style) and writes it, with
the head it was made on, in the units of the avatar's heads (crown y +1, chin -1, nose tip z 0.68). export_head.py then fits it onto
Classique, Léa or Marc (style option "hair_mesh", see its HAIR_STYLES).

Usage: python prepare_hair.py <collection> <folder with scene.gltf> <style number> <out.npz> [--preview out.png]
       python prepare_hair.py <collection> <folder> all <out folder>     (every style of the collection, and a contact sheet)
Collections (both by Vincent Page, CC BY 4.0, from Sketchfab):
  men09   "FREE Male Fashion Hair collection 01 lowpoly"  https://sketchfab.com/3d-models/free-male-fashion-hair-collection-01-lowpoly-2735370f6f764078a18f5d80651050a2
          14 styles: two rows of seven heads.
  women09 "12 Real-time woman Hairstyles collection 09"   https://sketchfab.com/3d-models/12-real-time-woman-hairstyles-collection-09-a2464050ef6f447dbff9ecde1fdb36ca
          12 styles: two rows of six figures.
Needs numpy, scipy (and Pillow for the previews).

How: the scene is welded across its chunks and cut in connected pieces; the heads stand on a grid, so each piece belongs to the head
nearest to it. The largest piece near a head is the head itself (a body, for the women), the others are its hair. The head is framed as
the avatar's are: its crown and nose tip are read from the mesh, its chin is a fixed height under the crown, measured once for each
collection (every head of a collection is the same base mesh, moved).
"""
import os, sys
import numpy as np
import scipy.sparse as sps
import scipy.sparse.csgraph as csg

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gltf_scene import Scene

NOSE_Z = 0.6796
COLLECTIONS = {
    # x of the first column, spacing, columns; the second row is told apart by z; crown to chin (measured on a profile)
    "men09": dict(x0=-1.19, dx=0.2, cols=7, back=lambda z: z > 0.25, chin_below_crown=0.196, min_y=1.2),
    "women09": dict(x0=-1.5, dx=0.3, cols=6, back=lambda z: z < -0.15, chin_below_crown=0.208, min_y=1.2),
}


def load(folder):
    sc = Scene(folder)
    prims = sc.primitives()
    V = np.vstack([p["P"] for p in prims])
    Fs, off = [], 0
    for p in prims:
        Fs.append(p["F"] + off); off += len(p["P"])
    F = np.vstack(Fs)
    _, idx, inv = np.unique(np.round(V / 1e-5).astype(np.int64), axis=0, return_index=True, return_inverse=True)
    V = V[idx]; F = inv.reshape(-1)[F]
    F = F[(F[:, 0] != F[:, 1]) & (F[:, 1] != F[:, 2]) & (F[:, 0] != F[:, 2])]
    n = len(V)
    A = sps.coo_matrix((np.ones(3 * len(F)), (np.r_[F[:, 0], F[:, 1], F[:, 2]], np.r_[F[:, 1], F[:, 2], F[:, 0]])), shape=(n, n))
    _, lab = csg.connected_components(A, directed=False)
    return V, F, lab[F[:, 0]]


def cells(V, F, flab, col):
    """The style number of each triangle: the head (grid cell) its piece belongs to, by the piece's centre."""
    cen = V[F].mean(1)
    npieces = flab.max() + 1
    cnt = np.bincount(flab, minlength=npieces).astype(float)
    pc = np.stack([np.bincount(flab, cen[:, k], npieces) for k in range(3)], 1) / np.maximum(cnt, 1)[:, None]
    c = np.clip(np.round((pc[:, 0] - col["x0"]) / col["dx"]), 0, col["cols"] - 1).astype(int)
    c = c + col["cols"] * col["back"](pc[:, 2]).astype(int)
    return c[flab], cen


def style(V, F, flab, col, number):
    cell, cen = cells(V, F, flab, col)
    sel = (cell == number) & (cen[:, 1] > col["min_y"])
    if not sel.any():
        raise SystemExit("no style %d in this collection" % number)
    labs, cnts = np.unique(flab[sel], return_counts=True)
    head_piece = labs[np.argmax(cnts)]
    head_f = F[sel & (flab == head_piece)]
    hair_f = F[sel & (flab != head_piece)]
    hp = V[np.unique(head_f)]
    crown = hp[:, 1].max()
    xc = hp[hp[:, 1] > crown - 0.15, 0].mean()
    # the nose tip: the frontmost point of the face between the eyes and the mouth, near the middle
    band = hp[(hp[:, 1] < crown - 0.10) & (hp[:, 1] > crown - 0.17) & (np.abs(hp[:, 0] - xc) < 0.03)]
    nose_z = band[:, 2].max()
    chin = crown - col["chin_below_crown"]
    s = 2.0 / (crown - chin)

    def norm(P):
        return np.c_[(P[:, 0] - xc) * s, (P[:, 1] - (crown + chin) / 2) * s, P[:, 2] * s - (nose_z * s - NOSE_Z)]

    def compact(faces):
        used = np.unique(faces)
        remap = -np.ones(len(V), np.int64); remap[used] = np.arange(len(used))
        return norm(V[used]), remap[faces]

    HV, HF = compact(head_f)
    keep = HV[HF].mean(1)[:, 1] > -1.35                     # the head and the neck, not the body under it
    HF = HF[keep]
    # the head's eyeballs, brows and lashes are pieces of their own too: whatever lies wholly round the eyes is not hair (a fringe
    # coming down over the brows starts higher up, on the head)
    hair_pieces = np.unique(flab[sel & (flab != head_piece)])
    tri_n = norm(V[F[sel & (flab != head_piece)].ravel()]).reshape(-1, 3, 3)
    tri_piece = flab[sel & (flab != head_piece)]
    face_parts = set()
    for p in hair_pieces:
        P = tri_n[tri_piece == p].reshape(-1, 3)
        small = (P.max(0) - P.min(0)).max() < 0.5
        if small and P[:, 1].max() < 0.35 and P[:, 1].min() > -0.45 and P[:, 2].min() > 0.0 and np.abs(P[:, 0]).max() < 0.7:
            face_parts.add(p)
    hair_f = hair_f[~np.isin(flab[sel & (flab != head_piece)], list(face_parts))]
    RV, RF = compact(hair_f)
    return HV, HF, RV, RF


def preview(HV, HF, RV, RF, path, label=""):
    import raster
    from PIL import Image, ImageDraw
    V = np.vstack([HV, RV]); F = np.vstack([HF, RF + len(HV)])
    kind = np.r_[np.zeros(len(HF), int), np.ones(len(RF), int)]
    ims = []
    for turn in (0, 1):
        P = V if turn == 0 else np.c_[V[:, 2], V[:, 1], -V[:, 0]]
        n = np.zeros_like(P); fn = np.cross(P[F[:, 1]] - P[F[:, 0]], P[F[:, 2]] - P[F[:, 0]])
        for k in range(3):
            np.add.at(n, F[:, k], fn)
        n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
        L = np.array([-0.4, 0.6, 0.7]); L /= np.linalg.norm(L)
        W = 300; s = W / 3.2
        ims.append(raster.render(P, F, np.zeros((len(P), 2)), kind, [(0.8, 0.66, 0.58, 1), (0.22, 0.16, 0.12, 1)],
                                 0.35 + 0.65 * np.abs(n @ L), W, W, lambda x, y: (W / 2 + x * s, W / 2 - (y - 0.1) * s)))
    im = Image.new("RGB", (600, 300)); im.paste(ims[0], (0, 0)); im.paste(ims[1], (300, 0))
    ImageDraw.Draw(im).text((6, 6), label, fill=(255, 255, 0))
    im.save(path)
    return im


if __name__ == "__main__":
    coll, folder, which, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    col = COLLECTIONS[coll]
    V, F, flab = load(folder)
    numbers = range(col["cols"] * 2) if which == "all" else [int(which)]
    sheets = []
    for number in numbers:
        HV, HF, RV, RF = style(V, F, flab, col, number)
        path = os.path.join(out, "%s_%02d.npz" % (coll, number)) if which == "all" else out
        os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
        np.savez(path, head_v=HV.astype(np.float32), head_f=HF.astype(np.int32), hair_v=RV.astype(np.float32), hair_f=RF.astype(np.int32))
        print("style", number, "hair", len(RV), "vertices", len(RF), "triangles ->", path)
        if which == "all" or "--preview" in sys.argv:
            sheets.append(preview(HV, HF, RV, RF, path.replace(".npz", ".png"), "%s %d: %d tris" % (coll, number, len(RF))))
    if which == "all":
        from PIL import Image
        cols = 4
        sheet = Image.new("RGB", (600 * cols, 300 * ((len(sheets) + cols - 1) // cols)))
        for i, im in enumerate(sheets):
            sheet.paste(im, ((i % cols) * 600, (i // cols) * 300))
        sheet.save(os.path.join(out, "%s_sheet.png" % coll))
