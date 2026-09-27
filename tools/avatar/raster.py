"""A small software rasteriser with a depth buffer and textures, to look at a character before it goes into the app (and to measure
where its features are). Slow but plain: numpy per triangle. Needs numpy and Pillow."""
import numpy as np
from PIL import Image, ImageDraw


def render(P, F, UV, tex_of_face, textures, light, W, H, to_px, bg=(20, 26, 30), alpha_cut=0.35):
    """P (n,3) positions (x right, y up, z towards the viewer), F (m,3), UV (n,2) in [0,1] of the face's texture, tex_of_face (m,) index into
    textures (list of float arrays h x w x 4, 0..1, or an RGBA tuple for a plain colour), light (n,) per-vertex factor, to_px (x, y) -> pixel."""
    img = np.zeros((H, W, 3)); img[:] = np.array(bg) / 255.0
    zb = np.full((H, W), -1e9)
    X, Y = to_px(P[:, 0], P[:, 1])
    for t in np.argsort(P[F].mean(1)[:, 2]):
        a, b, c = F[t]
        xs = np.array([X[a], X[b], X[c]]); ys = np.array([Y[a], Y[b], Y[c]])
        x0, x1 = int(max(0, np.floor(xs.min()))), int(min(W - 1, np.ceil(xs.max())))
        y0, y1 = int(max(0, np.floor(ys.min()))), int(min(H - 1, np.ceil(ys.max())))
        if x1 < x0 or y1 < y0:
            continue
        den = (ys[1] - ys[2]) * (xs[0] - xs[2]) + (xs[2] - xs[1]) * (ys[0] - ys[2])
        if abs(den) < 1e-12:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + 0.5, np.arange(y0, y1 + 1) + 0.5)
        l0 = ((ys[1] - ys[2]) * (gx - xs[2]) + (xs[2] - xs[1]) * (gy - ys[2])) / den
        l1 = ((ys[2] - ys[0]) * (gx - xs[2]) + (xs[0] - xs[2]) * (gy - ys[2])) / den
        l2 = 1 - l0 - l1
        inside = (l0 >= -1e-6) & (l1 >= -1e-6) & (l2 >= -1e-6)
        if not inside.any():
            continue
        z = l0 * P[a, 2] + l1 * P[b, 2] + l2 * P[c, 2]
        sub = zb[y0:y1 + 1, x0:x1 + 1]
        ok = inside & (z > sub)
        if not ok.any():
            continue
        tx = textures[tex_of_face[t]]
        lt = l0 * light[a] + l1 * light[b] + l2 * light[c]
        if isinstance(tx, np.ndarray):
            u = l0 * UV[a, 0] + l1 * UV[b, 0] + l2 * UV[c, 0]
            v = l0 * UV[a, 1] + l1 * UV[b, 1] + l2 * UV[c, 1]
            th, tw = tx.shape[:2]
            px = (np.mod(u, 1.0) * tw).astype(int).clip(0, tw - 1); py = (np.mod(v, 1.0) * th).astype(int).clip(0, th - 1)
            col = tx[py, px]
        else:
            col = np.broadcast_to(np.array(tx, float), gx.shape + (4,))
        ok &= col[..., 3] > alpha_cut
        sub[ok] = z[ok]
        img[y0:y1 + 1, x0:x1 + 1][ok] = col[ok][:, :3] * lt[ok][:, None]
    return Image.fromarray((img.clip(0, 1) * 255).astype(np.uint8))


def grid(im, to_px, xr, yr, step, label=True):
    dr = ImageDraw.Draw(im)
    for g in np.arange(np.ceil(yr[0] / step) * step, yr[1], step):
        _, yy = to_px(np.array([0.0]), np.array([g]))
        dr.line([(0, yy[0]), (im.width, yy[0])], fill=(160, 80, 0))
        if label: dr.text((2, yy[0] - 11), "%.2f" % g, fill=(255, 190, 90))
    for g in np.arange(np.ceil(xr[0] / step) * step, xr[1], step):
        xx, _ = to_px(np.array([g]), np.array([0.0]))
        dr.line([(xx[0], 0), (xx[0], im.height)], fill=(0, 80, 160))
        if label: dr.text((xx[0] + 2, 2), "%.2f" % g, fill=(120, 190, 255))
    return im
