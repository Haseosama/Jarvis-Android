"""Hair for the avatar's head, built as geometry on the scanned head, from the scan's own faces and new strips laid on them:

  * the hair: a thin dark cap over the scalp (so no skin shows between the locks), and about 600 locks, each a curved, tapering strip
    that leaves the scalp along the flow of the cut (up and back-and-right from the front, back and down at the sides), waves a little
    and ends in a point. Every vertex carries a colour and a normal tilted to the side, so a lock shades like a round tuft.

The colour of a vertex is an ARGB int whose alpha byte says how much of it there is over the skin (254 = all of it, less = a fade into
the skin); 255 is kept for the paints that are not hair (the mouth, the eyeballs).

A style may add, on top of [DEFAULT_STYLE]: longer, hanging locks (gravity, flow_down, tip_in), more rows per lock (rows, the curves
then read smooth instead of faceted), a soft hairline where the cap melts into the skin over a band (margin) instead of a cut line,
a parting the top flows away from (part), a curtain fringe over the forehead (fringe), a taper of shorter locks down the sides
(side_trim) and salt-and-pepper grey (grey). Every one of them defaults to the old behaviour, so the original face is unchanged.

Coordinates: the face's midline is at x = x0, the crown at y = +1, the chin at y = -1, +z out of the face.
"""
import numpy as np

# How a head is dressed. The defaults are the original face's hair; other faces override some of them (see export_head.py).
DEFAULT_STYLE = dict(
    front=0.49, m=0.03, left_temple=0.022, temple=-0.03, nape=-0.10, burn_y=-0.10, ears_bare=True,
    len_top=(0.24, 0.22), len_side=(0.08, 0.10), len_front=0.10,
    lift=1.15, lift_side_damp=0.5, wave=1.35, wave_side_damp=0.55, width=1.0, kappa=1.15,
    body=(0x48, 0x31, 0x21), root=(0x20, 0x15, 0x0E), gold=(0x8E, 0x6C, 0x48), cap=(0x36, 0x25, 0x19),
    flow_front=(0.55, 0.60, -0.20), flow_top=(0.75, 0.10, -0.55), flow_side=(0.05, -0.30, -0.95),
    locks=520, edge_locks=330, width_side=0.0, side_boost=0.0,
    rows=6, gravity=0.0, flow_down=0.0, tip_in=0.0, taper=0.6, tip_w=0.0012, margin=0.0,
    part=None, fringe=0, fringe_len=(0.22, 0.10), side_trim=0.0, grey=0.0, grey_rgb=(0x8E, 0x8B, 0x86), fade_pow=0.85,
    tone_lo=0.70, tone_hi=1.12, face_frame=0.0, cap_streaks=0.0, len_floor=0.60, len_span=0.62,
    burn_e0=0.44, burn_e1=0.62, crown_w=0.0,
    ear=((0.72, 0.07, -0.14), (0.11, 0.22, 0.22)), ridge=0.95,
    cut_y=None, cut_front=0.0, cut_min=0.08, hug=0.0, hug_gap=0.04, hug_all=False, hug_surface=False, roll=1.3, scatter=0.55, bun=None,
    nape_z=(-0.40, -0.05),
)

DOWN = np.array([0.0, -1.0, 0.0])


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
    front = front + (st["burn_y"] - front) * smoothstep(st["burn_e0"], st["burn_e1"], np.abs(hx))   # the sideburns come down in front of the ears
    hl = st["nape"] + (st["temple"] - st["nape"]) * smoothstep(st["nape_z"][0], st["nape_z"][1], hz)   # the nape, then above the ears
    hl = hl + (front - hl) * smoothstep(0.02, 0.36, hz)
    d = hy - hl
    ear = ear_distance(P, x0, st)
    return np.minimum(d, 0.2 * (ear - 1.0)) if st["ears_bare"] else d


def ear_distance(P, x0, st=DEFAULT_STYLE):
    """1.0 on the edge of the region kept bare around each ear, smaller inside it, larger away from it."""
    hx, hy, hz = P[:, 0] - x0, P[:, 1], P[:, 2]
    (ex, ey, ez), (rx, ry, rz) = st["ear"]
    return np.sqrt(((np.abs(hx) - ex) / rx) ** 2 + ((hy - ey) / ry) ** 2 + ((hz - ez) / rz) ** 2)


def flow_direction(p, n, x0, rng, scatter=0.20, st=DEFAULT_STYLE):
    """The direction the hair lies in at a point of the scalp: tangent to the surface. From the front it rises and sweeps back and to the
    right, over the top it runs back and to the right, at the sides it goes back and down. With a parting, the crown flows away from it."""
    hx, hy, hz = p[:, 0] - x0, p[:, 1], p[:, 2]
    w_front = smoothstep(0.10, 0.45, hz) * smoothstep(0.30, 0.55, hy)
    w_side = 1.0 - smoothstep(0.10, 0.45, hy)
    f_front = np.array(st["flow_front"])
    f_top = np.array(st["flow_top"])
    f_side = np.array(st["flow_side"])
    f = (f_top[None, :] * (1 - w_front[:, None]) + f_front[None, :] * w_front[:, None]) * (1 - w_side[:, None]) + f_side[None, :] * w_side[:, None]
    if st.get("part") is not None:
        # a parting: on the crown the hair falls away from the line, each side towards its own temple
        w_part = smoothstep(0.30, 0.62, hy) * smoothstep(-0.25, 0.25, hz)
        away = np.sign(hx - st["part"])[:, None] * np.array([1.0, 0.10, 0.15])[None, :]
        f = f * (1.0 - 0.9 * w_part[:, None]) + away * w_part[:, None]
    f = f - n * np.sum(f * n, axis=1, keepdims=True)                                # along the surface
    f = unit(f)
    # a little scatter between locks: turn each one about the normal
    ang = (rng.random(len(p)) - 0.5) * scatter
    ca, sa = np.cos(ang)[:, None], np.sin(ang)[:, None]
    return unit(f * ca + np.cross(n, f) * sa)


def build_hair(P, N, F, x0, rng, st=DEFAULT_STYLE, samples=None, rng_extra=None):
    samples = samples or st["rows"]
    if rng_extra is None:
        rng_extra = np.random.default_rng(91)
    locks, edge_locks = st["locks"], st["edge_locks"]
    margin = st["margin"]
    d = hair_field(P, x0, st)
    # with a margin, the cap reaches a little below the hairline and its alpha fades out there: a trimmed edge, not a cut line
    Cp, Cn, Cd, Cparent, Cf = clip_shell(P, N, F, d + margin)
    hx, hy, hz = Cp[:, 0] - x0, Cp[:, 1], Cp[:, 2]
    # the cap: a thin layer over the scalp, about the colour of the locks; over the sides it thins into the skin
    thick = 0.006 + 0.010 * smoothstep(0.20, 0.60, hy)
    if margin > 0.0:
        thick = thick * (0.25 + 0.75 * smoothstep(0.0, margin, Cd))          # the cap lies flat where it fades out
    cap_p = Cp + Cn * (0.004 + smoothstep(0.0, 0.05, Cd) * thick)[:, None]
    sideness = 1.0 - smoothstep(0.05, 0.35, hz)
    cover = (1.0 - sideness) + sideness * (0.95 + 0.05 * smoothstep(0.0, 0.32, hy))
    cover = np.maximum(cover, smoothstep(0.34, 0.50, np.abs(hx)) * smoothstep(0.05, 0.22, hy))   # the sideburns are full
    cap_rgb = np.tile(np.array(st["cap"], dtype=float), (len(Cp), 1))
    if st["cap_streaks"] > 0.0:
        th = np.arctan2(hz, hx)
        band = 0.5 + 0.5 * np.sin(th * st["cap_streaks"] + 2.0 * hy + 1.7 * np.sin(th * 7.0))
        cap_rgb = cap_rgb * (1.0 + (0.42 * band - 0.21) * smoothstep(1.05, 0.55, hy))[:, None]
    if margin > 0.0:
        cover = cover * smoothstep(0.0, margin * 0.92, Cd) ** st["fade_pow"]   # the edge melts into the skin over the whole band
    else:
        cover = cover * (0.62 + 0.38 * smoothstep(0.0, 0.035, Cd))           # the edge of the cap melts into the skin
    cap_paint = argb(254.0 * cover, cap_rgb)
    cap_normals = Cn
    tri_min = np.array([Cd[f].min() for f in Cf])
    tri_max = np.array([Cd[f].max() for f in Cf])
    tri_c = cap_p[Cf].mean(axis=1)

    body = np.array(st["body"], dtype=float)
    rootc = np.array(st["root"], dtype=float)
    goldc = np.array(st["gold"], dtype=float)
    greyc = np.array(st["grey_rgb"], dtype=float)
    ts = np.linspace(0.0, 1.0, samples)
    kappa = st["kappa"]                                        # the scalp curves away: the lock follows it
    grav, fdown, tip_in = st["gravity"], st["flow_down"], st["tip_in"]
    cut_y, cut_front, cut_min = st["cut_y"], st["cut_front"], st["cut_min"]
    hug, hug_gap, hug_all, hug_surface = st["hug"], st["hug_gap"], st["hug_all"], st["hug_surface"]
    hanging = grav != 0.0 or fdown != 0.0 or tip_in != 0.0
    ell_c, ell_r = np.zeros(3), np.ones(3)
    if hug > 0.0 or st["bun"] is not None:
        # the skull as an axis-aligned ellipsoid fitted to the scalp (least squares on a x² + b y² + c z² + d x + e y + f z = 1)
        top = Cp[Cp[:, 1] > 0.05]                          # the vault only: the nape and the sideburns would stretch it
        A = np.column_stack([top[:, 0] ** 2, top[:, 1] ** 2, top[:, 2] ** 2, top[:, 0], top[:, 1], top[:, 2]])
        co = np.linalg.lstsq(A, np.ones(len(top)), rcond=None)[0]
        ell_c = -co[3:] / (2.0 * co[:3])
        g = 1.0 + np.sum(co[3:] ** 2 / (4.0 * co[:3]))
        ell_r = np.sqrt(g / co[:3])

    def make_locks(n, tri_w, len_mul, width_mul, lift_mul, scatter=0.55, fringe=False, rng_l=None):
        """n locks rooted on the cap triangles in proportion to tri_w. Returns vertices, normals, colours and triangles (local indices)."""
        rl = rng_l or rng
        idx, u, w = sample_triangles(cap_p, Cf, n, rl, tri_w)
        tri = Cf[idx]
        s0 = 1.0 - u - w
        root = s0[:, None] * cap_p[tri[:, 0]] + u[:, None] * cap_p[tri[:, 1]] + w[:, None] * cap_p[tri[:, 2]]
        nrm = unit(s0[:, None] * Cn[tri[:, 0]] + u[:, None] * Cn[tri[:, 1]] + w[:, None] * Cn[tri[:, 2]])
        flow = flow_direction(root, nrm, x0, rl, scatter, st)
        if fringe:
            # a curtain fringe: from the parting, across the forehead and down past the temples, standing a little off it
            side = np.sign(root[:, 0] - x0 - (st["part"] or 0.0))[:, None]
            f = side * np.array([1.0, -0.05, 0.12])[None, :] + np.array([0.0, -0.62, 0.32])[None, :]
            flow = unit(f - nrm * np.sum(f * nrm, axis=1, keepdims=True))
        rx, ry, rz = root[:, 0] - x0, root[:, 1], root[:, 2]
        front = smoothstep(0.10, 0.45, rz) * smoothstep(0.30, 0.55, ry)
        side = 1.0 - smoothstep(0.10, 0.45, ry)
        if fringe:
            length = (st["fringe_len"][0] + st["fringe_len"][1] * rl.random(n)) * (0.75 + 0.5 * np.abs(rx) / 0.5)
        else:
            length = ((st["len_top"][0] + st["len_top"][1] * rl.random(n)) * (1 - side) + (st["len_side"][0] + st["len_side"][1] * rl.random(n)) * side + st["len_front"] * front) * len_mul
        if st["ears_bare"]:
            # a lock rooted near an ear is kept short, so that it does not hang into it
            length = length * (0.35 + 0.65 * smoothstep(1.0, 2.2, ear_distance(root, x0, st)))
        if st["face_frame"] > 0.0:
            # the locks in front of the ears are kept short: they frame the face instead of hanging across it
            length = length * (1.0 - st["face_frame"] * smoothstep(0.15, 0.45, rz) * smoothstep(0.30, 0.55, np.abs(rx)))
        if st["side_trim"] > 0.0:
            # trimmed sides: the lower a lock is rooted on the side of the head, the shorter it is (a taper, not a shelf)
            length = length * (1.0 - st["side_trim"] * side * smoothstep(0.35, -0.15, ry))
        length = length * (st["len_floor"] + st["len_span"] * rl.random(n) ** 1.3)   # uneven lengths: the outline is not a smooth line
        lift = (0.13 + 0.16 * front + 0.04 * rl.random(n)) * (1 - st["lift_side_damp"] * side) * lift_mul * st["lift"]
        if fringe:
            lift = lift + 0.06                                                     # the fringe stands off the forehead
        wave_amp = (0.10 + 0.03 * rl.random(n)) * (1 - st["wave_side_damp"] * side) * st["wave"]
        wave_freq = 1.35 + 0.15 * rl.random(n)
        phase = 9.0 * (rx * 0.6 + rz * 0.8) + 0.5 * rl.random(n)         # neighbouring locks wave together, like combed hair
        width = (0.060 + 0.030 * rl.random(n)) * (1 - 0.35 * side) * width_mul * st["width"] * (1.0 + st["width_side"] * side)
        tone = st["tone_lo"] + (st["tone_hi"] - st["tone_lo"]) * rl.random(n)
        gold = rl.random(n) ** 1.8
        grey = (rng_extra.random(n) < st["grey"] * (0.35 + 0.65 * (1.0 - smoothstep(0.05, 0.45, ry)))) * (0.55 + 0.45 * rng_extra.random(n))
        roll = (rl.random(n) - 0.5) * st["roll"]
        verts, norms, colours, faces = [], [], [], []
        base = 0
        step_len = None
        for k in range(n):
            n_k, f_k = nrm[k], flow[k]
            side_k = unit(np.cross(f_k, n_k))
            centre = []
            if not hanging or fringe:
                for t in ts:
                    dist = length[k] * t
                    p_ = (root[k] + n_k * (0.006 + lift[k] * length[k] * np.sin(np.pi * min(t * 0.95, 1.0)))
                          + f_k * dist - n_k * 0.5 * kappa * dist * dist
                          + side_k * wave_amp[k] * length[k] * np.sin(2 * np.pi * wave_freq[k] * t + phase[k]) * (0.3 + 0.7 * t))
                    centre.append(p_)
            else:
                # hanging hair: the direction is integrated row by row, so it can turn towards the ground and curl in at the tip
                inward = np.array([(x0 - root[k][0]) * smoothstep(0.15, 0.45, abs(root[k][0] - x0)), 0.0, -root[k][2] * 0.6])
                inward = inward / max(np.linalg.norm(inward), 1e-9)

                def hang(len_k):
                    step = len_k / (samples - 1)
                    pos = root[k].copy()
                    pts = []
                    dir_ = f_k.copy()
                    for s_i, t in enumerate(ts):
                        if s_i > 0:
                            w_d = fdown * smoothstep(0.50, 0.05, pos[1])
                            f_row = unit(dir_ * (1.0 - w_d) + DOWN * w_d)
                            prev = pos
                            pos = pos + f_row * step
                            if hug_surface:
                                # slicked hair: kept at a set height over the real scalp (the nearest point of the cap, along its normal)
                                j_ = int(np.argmin(np.sum((cap_p - pos) ** 2, axis=1)))
                                h_ = float(np.dot(pos - cap_p[j_], Cn[j_]))
                                lo_ = hug_gap * (0.6 + 0.4 * t)
                                pos = pos + Cn[j_] * (np.clip(h_, lo_, lo_ + hug) - h_)
                                dir_ = unit(pos - prev)
                            elif hug > 0.0:
                                # the lock lies on the skull: kept within a thin layer over it down to the widest part of the head,
                                # then free to fall; it carries on in the direction it was bent to
                                q = (pos - ell_c) / ell_r
                                e = np.linalg.norm(q)
                                lo = 1.0 + hug_gap * (0.4 + 0.6 * t)
                                free = 0.0 if hug_all else 9.0 * (1.0 - smoothstep(ell_c[1] - 0.15, ell_c[1] + 0.25, pos[1]))
                                hi = lo + hug * (1.0 if hug_all else smoothstep(ell_c[1] - 0.15, ell_c[1] + 0.25, pos[1])) + free
                                if e < lo or e > hi:
                                    pos = ell_c + q / e * np.clip(e, lo, hi) * ell_r
                                dir_ = unit(pos - prev)
                        dist = len_k * t
                        p_ = (pos + n_k * (0.006 + lift[k] * len_k * np.sin(np.pi * min(t * 0.95, 1.0)))
                              - n_k * 0.5 * kappa * dist * dist
                              + side_k * wave_amp[k] * len_k * np.sin(2 * np.pi * wave_freq[k] * t + phase[k]) * (0.3 + 0.7 * t))
                        p_ = p_ + DOWN * (grav * len_k * t * t)
                        p_ = p_ + inward * (tip_in * len_k * smoothstep(0.45, 1.0, t))
                        pts.append(p_)
                    return pts

                centre = hang(length[k])
                if cut_y is not None:
                    # a cut line: the lock is shortened so that its tip ends on it (a bob), a little lower at the front if asked
                    def below(p_):
                        return p_[1] - (cut_y - cut_front * smoothstep(-0.25, 0.35, p_[2]))
                    ds = [below(p_) for p_ in centre]
                    if ds[-1] < 0.0:
                        j = next(i for i, v in enumerate(ds) if v < 0.0)
                        frac = ts[j - 1] + (ts[j] - ts[j - 1]) * ds[j - 1] / max(ds[j - 1] - ds[j], 1e-9) if j > 0 else 0.0
                        centre = hang(length[k] * max(frac, cut_min))
            centre = np.array(centre)
            for s_i, t in enumerate(ts):
                i0, i1 = max(s_i - 1, 0), min(s_i + 1, samples - 1)
                dv = unit(centre[i1] - centre[i0])
                perp = unit(np.cross(dv, n_k))
                ang = roll[k] * (0.25 + 0.75 * t)
                n_r = unit(n_k * np.cos(ang) - perp * np.sin(ang))
                perp = unit(perp * np.cos(ang) + n_k * np.sin(ang))
                half = 0.5 * width[k] * (1.0 - t) ** st["taper"] + st["tip_w"]    # a tapering tip, blunt or pointed by style
                c = rootc + (body - rootc) * smoothstep(0.0, 0.45, t)
                c = c + (goldc - c) * (gold[k] * smoothstep(0.30, 1.0, t) * 0.55)
                c = c * tone[k]
                if grey[k] > 0.0:
                    c = c * (1.0 - grey[k]) + greyc * grey[k] * (0.45 + 0.55 * t)   # salt and pepper, lighter towards the tip
                for sign in (-1.0, 0.0, 1.0):
                    verts.append(centre[s_i] + perp * half * sign + (n_r * st["ridge"] * half if sign == 0.0 else 0.0))
                    norms.append(unit(n_r * 0.8 + perp * 0.75 * sign))
                    colours.append(c * (1.35 if sign == 0.0 else 0.72))       # the highlight runs along the middle of the lock
            for s_i in range(samples - 1):
                l0, c0, r0 = base + 3 * s_i, base + 3 * s_i + 1, base + 3 * s_i + 2
                l1, c1, r1 = base + 3 * s_i + 3, base + 3 * s_i + 4, base + 3 * s_i + 5
                faces += [(l0, c0, c1), (l0, c1, l1), (c0, r0, r1), (c0, r1, c1)]
            base += 3 * samples
        return np.array(verts), np.array(norms), np.array(colours), np.array(faces, dtype=np.int64)

    # the main locks: rooted a little above the hairline, more of them at the front and on top
    w_main = (tri_min > margin + 0.035).astype(float) * (0.6 + 1.2 * smoothstep(0.30, 0.60, tri_c[:, 1])
                                                        * np.maximum(smoothstep(0.0, 0.40, tri_c[:, 2]), st["crown_w"] * smoothstep(0.45, 0.80, tri_c[:, 1])))
    w_main = w_main * (1.0 + st["side_boost"] * (1.0 - smoothstep(0.10, 0.45, tri_c[:, 1])))                     # long hair: more locks at the sides and the back
    if st["ears_bare"]:
        near_ear = smoothstep(1.0, 1.4, ear_distance(tri_c, x0, st))                 # no lock is rooted close to an ear
        w_main = w_main * near_ear
    mv, mn, mc, mf = make_locks(locks, w_main, 1.0, 1.0, 1.0, scatter=st["scatter"])
    # the edge locks: short ones rooted exactly on the hairline, rising over the strip of cap above it, so the edge is made of hair
    w_edge = ((tri_max > margin) & (tri_min < margin + 0.03)).astype(float) * smoothstep(0.0, 0.25, tri_c[:, 2])
    if st["ears_bare"]:
        w_edge = w_edge * near_ear
    ev, en, ec, ef = make_locks(edge_locks, w_edge, 0.55, 0.70, 0.60, scatter=0.80)
    lock_p, lock_n, lock_paint_l, lock_f = [mv, ev], [mn, en], [mc, ec], [mf, ef + len(mv)]
    n_locks = locks + edge_locks
    # the fringe: locks rooted on the front hairline that fall over the forehead, from the parting outwards
    if st["fringe"] > 0:
        w_f = ((tri_max > margin) & (tri_min < margin + 0.06)).astype(float) * smoothstep(0.28, 0.46, tri_c[:, 2])
        fv, fn_, fc_, ff = make_locks(st["fringe"], w_f, 1.0, 0.85, 0.7, scatter=0.30, fringe=True, rng_l=rng_extra)
        lock_p.append(fv); lock_n.append(fn_); lock_paint_l.append(fc_); lock_f.append(ff + n_locks * 3 * samples)
        n_locks += st["fringe"]
    # a bun: locks wound round a ball at the back of the head, where the slicked-back hair is gathered
    if st["bun"] is not None:
        bun_y, bun_r, bun_n = st["bun"]

        # the back of the skull at that height: along -z from the ellipsoid's centre
        q = np.array([0.0, (bun_y - ell_c[1]) / ell_r[1], 0.0])
        zb = ell_c[2] - ell_r[2] * np.sqrt(max(1.0 - q[1] ** 2, 0.05))
        centre = np.array([x0, bun_y, zb - bun_r * 0.55])
        axis = unit(np.array([0.0, 0.15, -1.0]))
        e1 = unit(np.cross(axis, np.array([0.0, 1.0, 0.0])))
        e2 = np.cross(axis, e1)
        bv, bn, bc, bf = [], [], [], []
        base = 0
        rng_b = np.random.default_rng(1234)
        for k in range(bun_n):
            lat = -1.1 + 2.2 * (k + rng_b.random()) / bun_n          # from the side against the head to the far side, around the axis
            a0 = rng_b.random() * 2 * np.pi
            span = 2.6 + 0.8 * rng_b.random()
            w_k = 0.10 * bun_r * (0.8 + 0.4 * rng_b.random())
            tone = st["tone_lo"] + (st["tone_hi"] - st["tone_lo"]) * rng_b.random()
            for s_i, t in enumerate(ts):
                a = a0 + span * t
                ring = np.cos(lat * 0.95)
                dirv = unit(np.cos(a) * ring * e1 + np.sin(a) * ring * e2 + np.sin(lat * 0.95) * axis)
                p_ = centre + dirv * bun_r
                tang = unit(-np.sin(a) * e1 + np.cos(a) * e2)
                perp = unit(np.cross(tang, dirv))
                c = (body + (goldc - body) * 0.25 * np.sin(np.pi * t)) * tone
                for sign in (-1.0, 0.0, 1.0):
                    bv.append(p_ + perp * w_k * sign + dirv * (0.6 * w_k if sign == 0.0 else 0.0))
                    bn.append(unit(dirv * 0.8 + perp * 0.6 * sign))
                    bc.append(c * (1.3 if sign == 0.0 else 0.75))
            for s_i in range(samples - 1):
                l0, c0, r0 = base + 3 * s_i, base + 3 * s_i + 1, base + 3 * s_i + 2
                l1, c1, r1 = base + 3 * s_i + 3, base + 3 * s_i + 4, base + 3 * s_i + 5
                bf += [(l0, c0, c1), (l0, c1, l1), (c0, r0, r1), (c0, r1, c1)]
            base += 3 * samples
        lock_p.append(np.array(bv)); lock_n.append(np.array(bn)); lock_paint_l.append(np.array(bc))
        lock_f.append(np.array(bf, dtype=np.int64) + n_locks * 3 * samples)
        n_locks += bun_n
    lock_p = np.vstack(lock_p); lock_n = np.vstack(lock_n)
    lock_paint = argb(np.full(len(lock_p), 254.0), np.vstack(lock_paint_l))
    lock_f = np.vstack(lock_f)
    return dict(cap_p=cap_p, cap_n=cap_normals, cap_paint=cap_paint, cap_parent=Cparent, cap_f=Cf,
                lock_p=lock_p, lock_n=lock_n, lock_paint=lock_paint, lock_f=lock_f, lock_count=n_locks, lock_rows=samples)


def build(P, N, F, x0, seed=7, style=None):
    rng = np.random.default_rng(seed)
    st = {**DEFAULT_STYLE, **(style or {})}
    return build_hair(P, N, F, x0, rng, st, rng_extra=np.random.default_rng(seed * 31 + 5))
