"""Hair for the avatar's head, built as geometry on the scanned head, from the scan's own faces and new strips laid on them:

  * the hair: a thin dark cap over the scalp (so no skin shows between the locks), and about 600 locks, each a curved, tapering strip
    that leaves the scalp along the flow of the cut (up and back-and-right from the front, back and down at the sides), waves a little
    and ends in a point. Every vertex carries a colour and a normal tilted to the side, so a lock shades like a round tuft.

The colour of a vertex is an ARGB int whose alpha byte says how much of it there is over the skin (254 = all of it, less = a fade into
the skin); 255 is kept for the paints that are not hair (the mouth, the eyeballs).

Coordinates: the face's midline is at x = x0, the crown at y = +1, the chin at y = -1, +z out of the face.
"""
import numpy as np

# How a head is dressed. The defaults are the original face's hair; other faces override some of them (see export_head.py).
DEFAULT_STYLE = dict(
    front=0.49, m=0.03, left_temple=0.022, temple=-0.03, nape=-0.10, burn_y=-0.10, ears_bare=True,
    len_top=(0.30, 0.12), len_side=(0.09, 0.04), len_front=0.10,
    lift=1.0, lift_side_damp=0.6, wave=1.0, wave_side_damp=0.7, width=1.0, kappa=1.15,
    body=(0x48, 0x31, 0x21), root=(0x20, 0x15, 0x0E), gold=(0x8E, 0x6C, 0x48), cap=(0x36, 0x25, 0x19),
    flow_front=(0.55, 0.60, -0.20), flow_top=(0.75, 0.10, -0.55), flow_side=(0.05, -0.30, -0.95),
    locks=520, edge_locks=330, width_side=0.0, side_boost=0.0,
)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def unit(v):
    return v / np.maximum(np.linalg.norm(v, axis=-1, keepdims=True), 1e-9)


def argb(alpha, rgb):
    """Alpha (0..254) and float rgb (0..255, shape (n, 3)) to ARGB ints."""
    c = np.clip(np.round(rgb), 0, 255).astype(np.int64)
    a = np.clip(np.round(alpha), 1, 254).astype(np.int64)
    return (a << 24) | (c[:, 0] << 16) | (c[:, 1] << 8) | c[:, 2]


def clip_shell(P, N, F, d):
    """The faces of the scan on the positive side of the field d (one value per scan vertex), cut exactly where d crosses zero.

    Returns positions, normals, the field, the scan vertex each point comes from, and the triangles (indices into those arrays)."""
    pts_p, pts_n, pts_d, parent = list(P), list(N), list(d), list(range(len(P)))
    cut = {}

    def cut_point(i, o):
        key = (i, o)
        if key not in cut:
            t = d[i] / (d[i] - d[o])
            pos = P[i] + (P[o] - P[i]) * t
            nrm = unit(N[i] + (N[o] - N[i]) * t)
            pts_p.append(pos); pts_n.append(nrm); pts_d.append(0.0); parent.append(i)
            cut[key] = len(pts_p) - 1
        return cut[key]

    tris = []
    for a, b, c in F:
        ins = [d[a] >= 0.0, d[b] >= 0.0, d[c] >= 0.0]
        k = sum(ins)
        v = (a, b, c)
        if k == 3:
            tris.append((a, b, c))
        elif k == 1:
            i0 = ins.index(True)
            p0, o1, o2 = v[i0], v[(i0 + 1) % 3], v[(i0 + 2) % 3]
            tris.append((p0, cut_point(p0, o1), cut_point(p0, o2)))
        elif k == 2:
            o0i = ins.index(False)
            o0, i1, i2 = v[o0i], v[(o0i + 1) % 3], v[(o0i + 2) % 3]
            q1, q2 = cut_point(i1, o0), cut_point(i2, o0)
            tris += [(i1, i2, q2), (i1, q2, q1)]
    tris = np.array(tris, dtype=np.int64)
    used = np.unique(tris.ravel())
    remap = -np.ones(len(pts_p), dtype=np.int64)
    remap[used] = np.arange(len(used))
    return (np.array(pts_p)[used], np.array(pts_n)[used], np.array(pts_d)[used], np.array(parent)[used], remap[tris])


def sample_triangles(P, F, n, rng, weight=None):
    """n points spread over the triangles in proportion to their area (times weight per triangle): triangle, and two barycentric weights."""
    a, b, c = P[F[:, 0]], P[F[:, 1]], P[F[:, 2]]
    area = 0.5 * np.linalg.norm(np.cross(b - a, c - a), axis=1)
    if weight is not None:
        area = area * weight
    cum = np.cumsum(area)
    idx = np.minimum(np.searchsorted(cum, rng.random(n) * cum[-1]), len(F) - 1)
    u, w = rng.random(n), rng.random(n)
    flip = u + w > 1.0
    u[flip], w[flip] = 1.0 - u[flip], 1.0 - w[flip]
    return idx, u, w


# ── the hair ────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────


def hair_field(P, x0, st=DEFAULT_STYLE):
    """Positive on the scalp where hair grows: above a hairline that is high and level at the front (a slight M), comes down in front
    of the ears as sideburns, runs above the ears at the sides and low at the nape; the ears themselves are left bare."""
    hx, hy, hz = P[:, 0] - x0, P[:, 1], P[:, 2]
    front = st["front"] + st["m"] * np.cos(np.pi * hx / 0.42) + st["left_temple"] * smoothstep(0.05, 0.40, -hx)
    front = front + 0.011 * np.sin(23.0 * hx + 1.3) + 0.007 * np.sin(41.0 * hx + 0.4) + 0.004 * np.sin(67.0 * hx + 2.1)   # not a ruled line
    front = front + (st["burn_y"] - front) * smoothstep(0.44, 0.62, np.abs(hx))          # the sideburns come down in front of the ears
    hl = st["nape"] + (st["temple"] - st["nape"]) * smoothstep(-0.40, -0.05, hz)                       # the nape, then above the ears
    hl = hl + (front - hl) * smoothstep(0.02, 0.36, hz)
    d = hy - hl
    ear = ear_distance(P, x0)
    return np.minimum(d, 0.2 * (ear - 1.0)) if st["ears_bare"] else d


def ear_distance(P, x0):
    """1.0 on the edge of the region kept bare around each ear, smaller inside it, larger away from it."""
    hx, hy, hz = P[:, 0] - x0, P[:, 1], P[:, 2]
    return np.sqrt(((np.abs(hx) - 0.72) / 0.12) ** 2 + ((hy - 0.09) / 0.26) ** 2 + ((hz + 0.14) / 0.24) ** 2)


def flow_direction(p, n, x0, rng, scatter=0.20, st=DEFAULT_STYLE):
    """The direction the hair lies in at a point of the scalp: tangent to the surface. From the front it rises and sweeps back and to the
    right, over the top it runs back and to the right, at the sides it goes back and down."""
    hx, hy, hz = p[:, 0] - x0, p[:, 1], p[:, 2]
    w_front = smoothstep(0.10, 0.45, hz) * smoothstep(0.30, 0.55, hy)
    w_side = 1.0 - smoothstep(0.10, 0.45, hy)
    f_front = np.array(st["flow_front"])
    f_top = np.array(st["flow_top"])
    f_side = np.array(st["flow_side"])
    f = (f_top[None, :] * (1 - w_front[:, None]) + f_front[None, :] * w_front[:, None]) * (1 - w_side[:, None]) + f_side[None, :] * w_side[:, None]
    f = f - n * np.sum(f * n, axis=1, keepdims=True)                                # along the surface
    f = unit(f)
    # a little scatter between locks: turn each one about the normal
    ang = (rng.random(len(p)) - 0.5) * scatter
    ca, sa = np.cos(ang)[:, None], np.sin(ang)[:, None]
    return unit(f * ca + np.cross(n, f) * sa)


def build_hair(P, N, F, x0, rng, st=DEFAULT_STYLE, samples=6):
    locks, edge_locks = st["locks"], st["edge_locks"]
    d = hair_field(P, x0, st)
    Cp, Cn, Cd, Cparent, Cf = clip_shell(P, N, F, d)
    hx, hy, hz = Cp[:, 0] - x0, Cp[:, 1], Cp[:, 2]
    # the cap: a thin layer over the scalp, about the colour of the locks; over the sides it thins into the skin
    thick = 0.010 + 0.016 * smoothstep(0.20, 0.60, hy)
    cap_p = Cp + Cn * (0.004 + smoothstep(0.0, 0.05, Cd) * thick)[:, None]
    sideness = 1.0 - smoothstep(0.05, 0.35, hz)
    cover = (1.0 - sideness) + sideness * (0.95 + 0.05 * smoothstep(0.0, 0.32, hy))
    cover = np.maximum(cover, smoothstep(0.34, 0.50, np.abs(hx)) * smoothstep(0.05, 0.22, hy))   # the sideburns are full
    cap_rgb = np.tile(np.array(st["cap"], dtype=float), (len(Cp), 1))
    cover = cover * (0.30 + 0.70 * smoothstep(0.0, 0.035, Cd))                       # the edge of the cap melts into the skin
    cap_paint = argb(254.0 * cover, cap_rgb)
    cap_normals = Cn
    tri_min = np.array([Cd[f].min() for f in Cf])
    tri_max = np.array([Cd[f].max() for f in Cf])
    tri_c = cap_p[Cf].mean(axis=1)

    body = np.array(st["body"], dtype=float)
    rootc = np.array(st["root"], dtype=float)
    goldc = np.array(st["gold"], dtype=float)
    ts = np.linspace(0.0, 1.0, samples)
    kappa = st["kappa"]                                        # the scalp curves away: the lock follows it

    def make_locks(n, tri_w, len_mul, width_mul, lift_mul, scatter=0.20):
        """n locks rooted on the cap triangles in proportion to tri_w. Returns vertices, normals, colours and triangles (local indices)."""
        idx, u, w = sample_triangles(cap_p, Cf, n, rng, tri_w)
        tri = Cf[idx]
        s0 = 1.0 - u - w
        root = s0[:, None] * cap_p[tri[:, 0]] + u[:, None] * cap_p[tri[:, 1]] + w[:, None] * cap_p[tri[:, 2]]
        nrm = unit(s0[:, None] * Cn[tri[:, 0]] + u[:, None] * Cn[tri[:, 1]] + w[:, None] * Cn[tri[:, 2]])
        flow = flow_direction(root, nrm, x0, rng, scatter, st)
        rx, ry, rz = root[:, 0] - x0, root[:, 1], root[:, 2]
        front = smoothstep(0.10, 0.45, rz) * smoothstep(0.30, 0.55, ry)
        side = 1.0 - smoothstep(0.10, 0.45, ry)
        length = ((st["len_top"][0] + st["len_top"][1] * rng.random(n)) * (1 - side) + (st["len_side"][0] + st["len_side"][1] * rng.random(n)) * side + st["len_front"] * front) * len_mul
        if st["ears_bare"]:
            # a lock rooted near an ear is kept short, so that it does not hang into it
            length = length * (0.35 + 0.65 * smoothstep(1.0, 2.2, ear_distance(root, x0)))
        lift = (0.13 + 0.16 * front + 0.04 * rng.random(n)) * (1 - st["lift_side_damp"] * side) * lift_mul * st["lift"]
        wave_amp = (0.10 + 0.03 * rng.random(n)) * (1 - st["wave_side_damp"] * side) * st["wave"]
        wave_freq = 1.35 + 0.15 * rng.random(n)
        phase = 9.0 * (rx * 0.6 + rz * 0.8) + 0.5 * rng.random(n)         # neighbouring locks wave together, like combed hair
        width = (0.060 + 0.030 * rng.random(n)) * (1 - 0.35 * side) * width_mul * st["width"] * (1.0 + st["width_side"] * side)
        tone = 0.70 + 0.42 * rng.random(n)
        gold = rng.random(n) ** 1.8
        roll = (rng.random(n) - 0.5) * 1.3
        verts, norms, colours, faces = [], [], [], []
        base = 0
        for k in range(n):
            n_k, f_k = nrm[k], flow[k]
            side_k = unit(np.cross(f_k, n_k))
            centre = []
            for t in ts:
                dist = length[k] * t
                p_ = (root[k] + n_k * (0.006 + lift[k] * length[k] * np.sin(np.pi * min(t * 0.95, 1.0)))
                      + f_k * dist - n_k * 0.5 * kappa * dist * dist
                      + side_k * wave_amp[k] * length[k] * np.sin(2 * np.pi * wave_freq[k] * t + phase[k]) * (0.3 + 0.7 * t))
                centre.append(p_)
            centre = np.array(centre)
            for s_i, t in enumerate(ts):
                i0, i1 = max(s_i - 1, 0), min(s_i + 1, samples - 1)
                dv = unit(centre[i1] - centre[i0])
                perp = unit(np.cross(dv, n_k))
                ang = roll[k] * (0.25 + 0.75 * t)
                n_r = unit(n_k * np.cos(ang) - perp * np.sin(ang))
                perp = unit(perp * np.cos(ang) + n_k * np.sin(ang))
                half = 0.5 * width[k] * (1.0 - t) ** 0.6 + 0.0012          # a long, pointed taper
                c = rootc + (body - rootc) * smoothstep(0.0, 0.45, t)
                c = c + (goldc - c) * (gold[k] * smoothstep(0.30, 1.0, t) * 0.55)
                c = c * tone[k]
                for sign in (-1.0, 0.0, 1.0):
                    verts.append(centre[s_i] + perp * half * sign + (n_r * 0.95 * half if sign == 0.0 else 0.0))
                    norms.append(unit(n_r * 0.8 + perp * 0.75 * sign))
                    colours.append(c * (1.35 if sign == 0.0 else 0.72))       # the highlight runs along the middle of the lock
            for s_i in range(samples - 1):
                l0, c0, r0 = base + 3 * s_i, base + 3 * s_i + 1, base + 3 * s_i + 2
                l1, c1, r1 = base + 3 * s_i + 3, base + 3 * s_i + 4, base + 3 * s_i + 5
                faces += [(l0, c0, c1), (l0, c1, l1), (c0, r0, r1), (c0, r1, c1)]
            base += 3 * samples
        return np.array(verts), np.array(norms), np.array(colours), np.array(faces, dtype=np.int64)

    # the main locks: rooted a little above the hairline, more of them at the front and on top
    w_main = (tri_min > 0.035).astype(float) * (0.6 + 1.2 * smoothstep(0.30, 0.60, tri_c[:, 1]) * smoothstep(0.0, 0.40, tri_c[:, 2]))
    w_main = w_main * (1.0 + st["side_boost"] * (1.0 - smoothstep(0.10, 0.45, tri_c[:, 1])))                     # long hair: more locks at the sides and the back
    if st["ears_bare"]:
        near_ear = smoothstep(1.0, 1.4, ear_distance(tri_c, x0))                 # no lock is rooted close to an ear
        w_main = w_main * near_ear
    mv, mn, mc, mf = make_locks(locks, w_main, 1.0, 1.0, 1.0)
    # the edge locks: short ones rooted exactly on the hairline, rising over the strip of cap above it, so the edge is made of hair
    w_edge = ((tri_max > 0.0) & (tri_min < 0.03)).astype(float) * smoothstep(0.0, 0.25, tri_c[:, 2])
    if st["ears_bare"]:
        w_edge = w_edge * near_ear
    ev, en, ec, ef = make_locks(edge_locks, w_edge, 0.55, 0.70, 0.60, scatter=0.80)
    lock_p = np.vstack([mv, ev]); lock_n = np.vstack([mn, en])
    lock_paint = argb(np.full(len(lock_p), 254.0), np.vstack([mc, ec]))
    lock_f = np.vstack([mf, ef + len(mv)])
    return dict(cap_p=cap_p, cap_n=cap_normals, cap_paint=cap_paint, cap_parent=Cparent, cap_f=Cf,
                lock_p=lock_p, lock_n=lock_n, lock_paint=lock_paint, lock_f=lock_f, lock_count=locks + edge_locks, lock_rows=samples)


def build(P, N, F, x0, seed=7, style=None):
    rng = np.random.default_rng(seed)
    return build_hair(P, N, F, x0, rng, {**DEFAULT_STYLE, **(style or {})})
