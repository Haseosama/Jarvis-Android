"""Fits a hairstyle made on another head (prepare_hair.py) onto one of the avatar's heads.

Both heads are already framed alike (crown +1, chin -1, nose tip z 0.68), but their skulls differ: a narrower temple, a higher crown, a
flatter back. So the hair is moved radially, direction by direction, from the centre of the skull: every point keeps its distance ABOVE
the scalp (its height over the source head along that direction) and is set at that height over the target head. A strand that lay on
the source scalp lies on the target's; a lock that stood 2 cm out still stands 2 cm out. The skull's radius in each direction is read on
a grid of directions (the outermost surface of the head there), holes filled from their neighbours and smoothed.

Then the hair is coloured (a colour of its own per strand, darker at the roots, lighter at the tips, some strands grey if asked) and given
normals that lean outwards, so the light falls on it as on a head of hair rather than on the sides of thin cards (the strands are single
sheets, drawn from both sides: faceGroup 2.5 in the app).
"""
import numpy as np
import scipy.sparse as sps
import scipy.sparse.csgraph as csg

AZ, EL = 96, 48                     # the grid of directions: around the head, and from below to above


def _dirs(P, c):
    d = P - c
    r = np.linalg.norm(d, axis=1)
    u = d / np.maximum(r, 1e-9)[:, None]
    az = (np.arctan2(u[:, 0], u[:, 2]) + np.pi) / (2 * np.pi) * AZ          # 0..AZ, wrapping
    el = (np.arcsin(np.clip(u[:, 1], -1, 1)) + np.pi / 2) / np.pi * (EL - 1)   # 0..EL-1
    return r, az, el


def radius_map(V, F, c, keep_below=-1.25):
    """The skull's outermost radius from c, on the grid of directions (the triangles sampled densely, not just their corners)."""
    T = V[F]
    keep = T.mean(1)[:, 1] > keep_below
    T = T[keep]
    w = np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1], [1/3, 1/3, 1/3], [.5, .5, 0], [0, .5, .5], [.5, 0, .5]])
    P = np.einsum("sk,tkd->tsd", w, T).reshape(-1, 3)
    r, az, el = _dirs(P, c)
    ia = np.floor(az).astype(int) % AZ; ie = np.clip(np.round(el).astype(int), 0, EL - 1)
    R = np.full((EL, AZ), np.nan)
    flat = ie * AZ + ia
    best = np.full(EL * AZ, -np.inf)
    np.maximum.at(best, flat, r)
    R = np.where(np.isfinite(best), best, np.nan).reshape(EL, AZ)
    # holes (straight below, through the neck) from their neighbours
    for _ in range(60):
        m = np.isnan(R)
        if not m.any():
            break
        Rp = np.pad(R, ((1, 1), (0, 0)), mode="edge")
        nb = np.stack([np.roll(R, 1, 1), np.roll(R, -1, 1), Rp[:-2], Rp[2:]])
        fill = np.nanmean(nb, axis=0)
        R = np.where(m, fill, R)
    R = np.where(np.isnan(R), np.nanmean(R), R)
    for _ in range(2):
        Rp = np.pad(R, ((1, 1), (0, 0)), mode="edge")
        R = (2 * R + np.roll(R, 1, 1) + np.roll(R, -1, 1) + Rp[:-2] + Rp[2:]) / 6
    return R


def _sample(R, az, el):
    a0 = np.floor(az).astype(int); t = az - a0
    e0 = np.clip(np.floor(el).astype(int), 0, EL - 1); e1 = np.clip(e0 + 1, 0, EL - 1); s = np.clip(el - e0, 0, 1)
    a0 %= AZ; a1 = (a0 + 1) % AZ
    return (R[e0, a0] * (1 - t) + R[e0, a1] * t) * (1 - s) + (R[e1, a0] * (1 - t) + R[e1, a1] * t) * s


def centre(V):
    """The skull's centre: the middle of the vault (above the ears), a little behind the face."""
    top = V[V[:, 1] > -0.1]
    return np.array([0.0, 0.5 * (top[:, 1].max() + top[:, 1].min()), 0.5 * (top[:, 2].max() + top[:, 2].min())])


def fit(src_head_v, src_head_f, hair_v, target_v, target_f, lift=0.012):
    """The hair's points moved from the source head onto the target head (see the module's docstring)."""
    cs, ct = centre(src_head_v), centre(target_v)
    Rs = radius_map(src_head_v, src_head_f, cs)
    Rt = radius_map(target_v, target_f, ct)
    r, az, el = _dirs(hair_v, cs)
    u = (hair_v - cs) / np.maximum(r, 1e-9)[:, None]
    height = r - _sample(Rs, az, el)                            # above the source scalp (negative: inside it)
    rt = _sample(Rt, az, el)
    # a strand is never inside the target head: at least `lift` over its scalp, as it was over the source's
    new_r = rt + np.maximum(height, lift)
    return ct + u * new_r[:, None]


def strands(F, n):
    """The strand (connected piece) of each vertex."""
    A = sps.coo_matrix((np.ones(3 * len(F)), (np.r_[F[:, 0], F[:, 1], F[:, 2]], np.r_[F[:, 1], F[:, 2], F[:, 0]])), shape=(n, n))
    return csg.connected_components(A, directed=False)[1]


def colour_and_normals(P, F, target_v, target_f, body, root, tip, grey=0.0, grey_rgb=(142, 139, 134), seed=5):
    """ARGB paint per vertex (alpha 254: all hair) and normals leaning outwards."""
    rng = np.random.default_rng(seed)
    ct = centre(target_v)
    Rt = radius_map(target_v, target_f, ct)
    r, az, el = _dirs(P, ct)
    height = np.clip((r - _sample(Rt, az, el)) / 0.10, 0, 1)                 # 0 on the scalp, 1 ten centimetres out
    lab = strands(F, len(P))
    nlab = lab.max() + 1
    tone = rng.uniform(0.86, 1.10, nlab)[lab]                                  # each strand its own shade
    is_grey = (rng.random(nlab) < grey)[lab]
    # along a strand: how far this point is from the strand's closest point to the scalp
    body, root, tip, grey_rgb = (np.array(c, float) for c in (body, root, tip, grey_rgb))
    base = root + (body - root) * (0.45 + 0.55 * np.clip(height * 8.0, 0, 1))[:, None]      # darker only right at the roots
    base = base + (tip - base) * np.clip((height - 0.45) * 1.2, 0, 1)[:, None] * 0.35    # a touch lighter at the tips
    base = np.where(is_grey[:, None], grey_rgb * (0.85 + 0.15 * height[:, None]), base)
    rgb = np.clip(base * tone[:, None], 0, 255).round().astype(np.int64)
    paint = (254 << 24) | (rgb[:, 0] << 16) | (rgb[:, 1] << 8) | rgb[:, 2]
    # normals: the surface's, turned outwards, blended with the direction out of the head
    n = np.zeros_like(P)
    fn = np.cross(P[F[:, 1]] - P[F[:, 0]], P[F[:, 2]] - P[F[:, 0]])
    for k in range(3):
        np.add.at(n, F[:, k], fn)
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
    out = (P - ct) / np.maximum(r, 1e-9)[:, None]
    n = np.where((np.sum(n * out, axis=1) < 0)[:, None], -n, n)
    N = n * 0.45 + out * 0.55
    N /= np.maximum(np.linalg.norm(N, axis=1, keepdims=True), 1e-12)
    return paint.astype(np.int64), N
