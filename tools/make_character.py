#!/usr/bin/env python3
"""
make_character.py - turn a photo of a person into the Knocker's 3D model.

    python tools/make_character.py my_photo.png
    python tools/make_character.py my_photo.jpg --remove-bg        (needs: pip install rembg onnxruntime)

Input : a photo. Best is a full-body, front-facing photo with a transparent background
        (PNG). With --remove-bg the background is cut out automatically.
Output: (written straight into the mod's resources)
    assets/myknocker/textures/entity/knocker.png      the photo atlas (front + back + edge colour)
    assets/myknocker/geo/knocker.geo.json             3D model: the silhouette extruded into blocks
    assets/myknocker/animations/knocker.animation.json idle / walk / run / attack
    assets/myknocker/textures/gui/jumpscare.png       256x256 face used by the jumpscare
    src/main/java/com/myknocker/KnockerSize.java      hitbox that matches the model

How the model works: the cut-out figure is sliced into a grid; every row of the silhouette
becomes a few thin boxes. The FRONT of every box shows your photo, so the face is fully visible.
Boxes in the middle of the body are thicker than at the edges, so it looks rounded, not flat.
The back shows a darkened, blurred mirror image (nobody needs to see the back of your head).

Useful options:
    --height 2.4        character height in blocks
    --depth 7           maximum body thickness in model units (16 units = 1 block)
    --head 0.17         share of the image height that is the head (full body ~0.15-0.18;
                        for a portrait/bust photo use 0.45)
    --rows 96           vertical detail of the model (more rows = more boxes)
    --tex-height 768    texture height in pixels (face sharpness)
"""
import argparse
import json
import math
import os
import sys

import numpy as np
from PIL import Image, ImageEnhance, ImageFilter

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "src", "main", "resources", "assets", "myknocker")
JAVA_SIZE = os.path.join(ROOT, "src", "main", "java", "com", "myknocker", "KnockerSize.java")


# --------------------------------------------------------------------------- image prep

def load_cutout(path, remove_bg):
    img = Image.open(path).convert("RGBA")
    alpha = np.array(img)[:, :, 3]
    has_transparency = (alpha < 250).mean() > 0.02
    if remove_bg or not has_transparency:
        try:
            from rembg import remove
        except ImportError:
            if remove_bg:
                sys.exit("rembg is not installed: pip install rembg onnxruntime")
            sys.exit("The image has no transparent background. Either cut the background out first "
                     "(PNG with transparency), or run again with --remove-bg "
                     "(pip install rembg onnxruntime).")
        img = remove(img).convert("RGBA")
    return img


def crop_to_figure(img, pad=0.02):
    a = np.array(img)[:, :, 3]
    ys, xs = np.where(a > 40)
    if len(xs) == 0:
        sys.exit("The image is empty after background removal.")
    x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    px = int((x1 - x0) * pad)
    py = int((y1 - y0) * pad)
    box = (max(0, x0 - px), max(0, y0 - py), min(img.width, x1 + px), min(img.height, y1 + py))
    return img.crop(box)


def clean_edges(img):
    """Hard alpha + 1px erosion + colour bleed so there is no halo around the figure."""
    arr = np.array(img).astype(np.uint8)
    alpha = Image.fromarray(arr[:, :, 3]).point(lambda v: 255 if v > 127 else 0)
    alpha = alpha.filter(ImageFilter.MinFilter(3))
    a = np.array(alpha)
    rgb = arr[:, :, :3].copy()
    # bleed colours of opaque pixels outward a few pixels (prevents dark outlines when sampled)
    solid = a > 0
    for _ in range(3):
        grown = rgb.copy()
        cnt = np.zeros(a.shape, dtype=np.int32)
        acc = np.zeros(rgb.shape, dtype=np.int32)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                if dx == 0 and dy == 0:
                    continue
                sh = np.roll(np.roll(solid, dy, 0), dx, 1)
                shc = np.roll(np.roll(rgb, dy, 0), dx, 1)
                acc += shc * sh[:, :, None]
                cnt += sh
        fill = (~solid) & (cnt > 0)
        grown[fill] = (acc[fill] / cnt[fill][:, None]).astype(np.uint8)
        rgb = grown
        solid = solid | fill
    out = np.dstack([rgb, a]).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


# --------------------------------------------------------------------------- grid / depth

def coverage_grid(alpha_img, gx, gy):
    """Fraction of opaque pixels per grid cell."""
    mask = Image.fromarray(((np.array(alpha_img) > 127) * 255).astype(np.uint8))
    small = mask.resize((gx, gy), Image.BOX)
    return np.array(small).astype(np.float32) / 255.0


def chamfer_distance(occ):
    """Distance (in cells) from each occupied cell to the nearest empty cell."""
    h, w = occ.shape
    inf = 1e9
    d = np.where(occ, inf, 0.0).astype(np.float64)
    # pad with empty border so the outline counts as an edge
    p = np.zeros((h + 2, w + 2))
    p[1:-1, 1:-1] = d
    d = p
    for y in range(1, h + 1):
        for x in range(1, w + 1):
            if d[y, x] > 0:
                d[y, x] = min(d[y, x], d[y - 1, x] + 1, d[y, x - 1] + 1,
                              d[y - 1, x - 1] + 1.4, d[y - 1, x + 1] + 1.4)
    for y in range(h, 0, -1):
        for x in range(w, 0, -1):
            if d[y, x] > 0:
                d[y, x] = min(d[y, x], d[y + 1, x] + 1, d[y, x + 1] + 1,
                              d[y + 1, x + 1] + 1.4, d[y + 1, x - 1] + 1.4)
    return d[1:-1, 1:-1]


def build_runs(levels, head_rows):
    """
    levels: 2D int array, -1 = empty, 0..L-1 = depth level.
    Returns list of (bone, r0, r1, c0, c1, level) with horizontal runs merged and stacked vertically.
    """
    gy, gx = levels.shape
    finished = []
    active = {}
    for r in range(gy):
        bone = "head" if r < head_rows else "body"
        if r == head_rows:  # never merge across the neck
            finished.extend(active.values())
            active = {}
        cur = {}
        c = 0
        while c < gx:
            lv = levels[r, c]
            if lv < 0:
                c += 1
                continue
            c0 = c
            while c < gx and levels[r, c] == lv:
                c += 1
            cur[(c0, c, int(lv))] = None
        nxt = {}
        for key in cur:
            if key in active:
                run = active.pop(key)
                run[2] = r + 1  # extend r1
                nxt[key] = run
            else:
                nxt[key] = [bone, r, r + 1, key[0], key[1], key[2]]
        finished.extend(active.values())
        active = nxt
    finished.extend(active.values())
    return [tuple(x) for x in finished]


# --------------------------------------------------------------------------- outputs

def make_atlas(front, side_rgb):
    """front on top, back (blurred, darkened mirror) below, a 3x3 edge-colour block at the very bottom."""
    tw, th = front.size
    back = front.transpose(Image.FLIP_LEFT_RIGHT)
    rgb = back.convert("RGB").filter(ImageFilter.GaussianBlur(max(2, th // 120)))
    rgb = ImageEnhance.Brightness(rgb).enhance(0.45)
    rgb = ImageEnhance.Color(rgb).enhance(0.6)
    back = Image.merge("RGBA", (*rgb.split(), back.split()[3]))
    atlas = Image.new("RGBA", (max(tw, 3), th * 2 + 3), (0, 0, 0, 0))
    atlas.paste(front, (0, 0))
    atlas.paste(back, (0, th))
    block = Image.new("RGBA", (3, 3), side_rgb + (255,))
    atlas.paste(block, (0, th * 2))
    return atlas


def face_crop(front, head_frac):
    tw, th = front.size
    head = front.crop((0, 0, tw, max(8, int(th * head_frac * 1.15))))
    a = np.array(head)[:, :, 3]
    ys, xs = np.where(a > 127)
    if len(xs) == 0:
        head = front
        a = np.array(head)[:, :, 3]
        ys, xs = np.where(a > 127)
    x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    side = max(x1 - x0, y1 - y0) * 1.25
    box = (int(cx - side / 2), int(cy - side / 2), int(cx + side / 2), int(cy + side / 2))
    face = Image.new("RGBA", (int(side), int(side)), (0, 0, 0, 0))
    face.paste(head, (-box[0], -box[1]))
    face = face.resize((256, 256), Image.LANCZOS)
    rgb = ImageEnhance.Contrast(face.convert("RGB")).enhance(1.25)
    rgb = ImageEnhance.Brightness(rgb).enhance(0.9)
    # vignette
    yy, xx = np.mgrid[0:256, 0:256]
    v = 1.0 - 0.55 * np.clip(np.hypot(xx - 128, yy - 128) / 150.0, 0, 1) ** 2
    arr = (np.array(rgb).astype(np.float32) * v[:, :, None]).clip(0, 255).astype(np.uint8)
    out = Image.fromarray(arr, "RGB").convert("RGBA")
    out.putalpha(face.split()[3])
    return out


def r4(v):
    return round(float(v), 4)


def build_geo(runs, gx, gy, tw, th, wu, hu, depths, head_rows):
    cell_w, cell_h = wu / gx, hu / gy
    tex_cw, tex_ch = tw / gx, th / gy
    side_uv = {"uv": [1, th * 2 + 1], "uv_size": [1, 1]}
    head_cubes, body_cubes = [], []
    for bone, r0, r1, c0, c1, lv in runs:
        depth = depths[lv]
        u0, v0 = c0 * tex_cw, r0 * tex_ch
        uw, vh = (c1 - c0) * tex_cw, (r1 - r0) * tex_ch
        cube = {
            "origin": [r4(-wu / 2 + c0 * cell_w), r4(hu - r1 * cell_h), r4(-depth / 2)],
            "size": [r4((c1 - c0) * cell_w), r4((r1 - r0) * cell_h), r4(depth)],
            "uv": {
                "north": {"uv": [r4(u0), r4(v0)], "uv_size": [r4(uw), r4(vh)]},
                "south": {"uv": [r4((gx - c1) * tex_cw), r4(th + v0)], "uv_size": [r4(uw), r4(vh)]},
                "east": dict(side_uv), "west": dict(side_uv),
                "up": dict(side_uv), "down": dict(side_uv),
            },
        }
        (head_cubes if bone == "head" else body_cubes).append(cube)
    neck_y = r4(hu - head_rows * cell_h)
    bones = [
        {"name": "root", "pivot": [0, 0, 0]},
        {"name": "body", "parent": "root", "pivot": [0, 0, 0], "cubes": body_cubes},
        {"name": "head", "parent": "body", "pivot": [0, neck_y, 0], "cubes": head_cubes},
    ]
    return {
        "format_version": "1.12.0",
        "minecraft:geometry": [{
            "description": {
                "identifier": "geometry.knocker",
                "texture_width": tw,
                "texture_height": th * 2 + 3,
                "visible_bounds_width": r4(max(wu, hu) / 16 + 2),
                "visible_bounds_height": r4(hu / 16 + 2),
                "visible_bounds_offset": [0, r4(hu / 32), 0],
            },
            "bones": bones,
        }],
    }


def kf(values, length):
    """Evenly spaced keyframes: values is a list of [x,y,z]; first == last for loops."""
    n = len(values) - 1
    return {("%.4f" % (length * i / n)).rstrip("0").rstrip(".") if i else "0.0": v for i, v in enumerate(values)}


def build_animations(hu):
    """
    Sign-agnostic movements only (side rocking, bobbing, scaling, head twitches), so nothing
    looks wrong whichever way an axis points. Edit freely in Blockbench.
    """
    bob = hu / 16 * 0.55  # bobbing in model units, scaled with height
    return {
        "format_version": "1.8.0",
        "animations": {
            "idle": {
                "loop": True,
                "animation_length": 4.0,
                "bones": {
                    "body": {
                        "rotation": kf([[0, 0, 0], [0, 0, 0.8], [0, 0, 0], [0, 0, -0.8], [0, 0, 0]], 4.0),
                        "scale": kf([[1, 1, 1], [1, 1.008, 1], [1, 1, 1], [1, 1.008, 1], [1, 1, 1]], 4.0),
                    },
                    "head": {
                        "rotation": {
                            "0.0": [0, 0, 0], "1.2": [0, 0, 0], "1.3": [0, 6, 4], "2.2": [0, 6, 4],
                            "2.3": [0, -5, -3], "3.2": [0, -5, -3], "3.4": [0, 0, 0], "4.0": [0, 0, 0],
                        }
                    },
                },
            },
            "walk": {
                "loop": True,
                "animation_length": 1.0,
                "bones": {
                    "body": {
                        "position": kf([[0, 0, 0], [0, r4(bob), 0], [0, 0, 0], [0, r4(bob), 0], [0, 0, 0]], 1.0),
                        "rotation": kf([[0, 0, 0], [0, 0, 3], [0, 0, 0], [0, 0, -3], [0, 0, 0]], 1.0),
                    },
                    "head": {"rotation": kf([[0, 0, 0], [0, 0, -2], [0, 0, 0], [0, 0, 2], [0, 0, 0]], 1.0)},
                },
            },
            "run": {
                "loop": True,
                "animation_length": 0.5,
                "bones": {
                    "body": {
                        "position": kf([[0, 0, 0], [0, r4(bob * 2.2), 0], [0, 0, 0], [0, r4(bob * 2.2), 0], [0, 0, 0]], 0.5),
                        "rotation": kf([[0, 0, 0], [0, 0, 6], [0, 0, 0], [0, 0, -6], [0, 0, 0]], 0.5),
                    },
                    "head": {"rotation": kf([[0, 0, 0], [0, 4, -4], [0, 0, 0], [0, -4, 4], [0, 0, 0]], 0.5)},
                },
            },
            "attack": {
                "loop": False,
                "animation_length": 0.4,
                "bones": {
                    "body": {
                        "scale": {"0.0": [1, 1, 1], "0.12": [1.14, 1.14, 1.14], "0.4": [1, 1, 1]},
                        "rotation": {"0.0": [0, 0, 0], "0.1": [0, 0, 7], "0.25": [0, 0, -7], "0.4": [0, 0, 0]},
                    },
                    "head": {"rotation": {"0.0": [0, 0, 0], "0.1": [0, 14, 0], "0.25": [0, -14, 0], "0.4": [0, 0, 0]}},
                },
            },
        },
    }


def write_json(path, data, compact=False):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, separators=(",", ":") if compact else None,
                  indent=None if compact else 2)


def write_size_java(width, height):
    src = f"""package com.myknocker;

/**
 * Hitbox of the character (in blocks).
 * This file is rewritten automatically by tools/make_character.py when you change the photo,
 * but you can also edit the numbers by hand.
 */
public final class KnockerSize {{
    public static final float WIDTH = {width:.2f}F;
    public static final float HEIGHT = {height:.2f}F;

    private KnockerSize() {{
    }}
}}
"""
    with open(JAVA_SIZE, "w", encoding="utf-8") as f:
        f.write(src)


def preview(runs, gx, gy, wu, hu, depths, out_dir):
    """Quick side view of the generated boxes (for checking the shape without launching the game)."""
    scale = 6
    wpx = int(max(depths) * scale) + 20
    hpx = int(hu * scale) + 10
    im = Image.new("RGB", (wpx, hpx), (20, 20, 20))
    px = im.load()
    cell_h = hu / gy
    for bone, r0, r1, c0, c1, lv in runs:
        d = depths[lv]
        x0 = int(wpx / 2 - d * scale / 2)
        x1 = int(wpx / 2 + d * scale / 2)
        y0 = int(5 + r0 * cell_h * scale)
        y1 = int(5 + r1 * cell_h * scale)
        shade = (200, 120, 120) if bone == "head" else (120, 160, 200)
        for y in range(y0, y1):
            for x in range(x0, x1):
                px[x, y] = shade
    im.save(os.path.join(out_dir, "preview_side.png"))


# --------------------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser(description="Photo -> Knocker 3D model")
    ap.add_argument("photo")
    ap.add_argument("--remove-bg", action="store_true", help="cut the background out automatically (needs rembg)")
    ap.add_argument("--height", type=float, default=2.4, help="character height in blocks (default 2.4)")
    ap.add_argument("--depth", type=float, default=7.0, help="max thickness in model units, 16 = 1 block (default 7)")
    ap.add_argument("--head", type=float, default=0.17, help="head share of the image height (default 0.17)")
    ap.add_argument("--rows", type=int, default=96, help="vertical detail (default 96)")
    ap.add_argument("--tex-height", type=int, default=768, help="texture height in px (default 768)")
    ap.add_argument("--preview-dir", default=None, help="also write preview images here")
    args = ap.parse_args()

    img = load_cutout(args.photo, args.remove_bg)
    img = crop_to_figure(img)
    aspect = img.width / img.height

    th = max(64, args.tex_height)
    tw = max(16, int(round(th * aspect)))
    img = img.resize((tw, th), Image.LANCZOS)
    img = clean_edges(img)

    hu = args.height * 16.0
    wu = hu * aspect
    gy = max(24, args.rows)
    gx = max(8, int(round(gy * aspect)))

    cov = coverage_grid(img.split()[3], gx, gy)
    occ = cov > 0.12
    if occ.sum() < 20:
        sys.exit("Almost nothing is opaque in this image - check the background removal.")

    dist = chamfer_distance(occ)
    dref = 5.0
    L = 4
    t = np.sqrt(np.clip(dist / dref, 0, 1))
    levels = np.where(occ, np.rint(t * (L - 1)).astype(int), -1)
    dmin = max(1.5, args.depth * 0.25)
    depths = [dmin + (args.depth - dmin) * math.sqrt(i / (L - 1)) for i in range(L)]

    head_rows = int(round(gy * args.head))
    runs = build_runs(levels, head_rows)

    arr = np.array(img)
    opaque = arr[:, :, 3] > 127
    avg = arr[:, :, :3][opaque].mean(axis=0) * 0.5
    side_rgb = tuple(int(v) for v in avg)

    atlas = make_atlas(img, side_rgb)
    geo = build_geo(runs, gx, gy, atlas.width, th, wu, hu, depths, head_rows)
    geo["minecraft:geometry"][0]["description"]["texture_width"] = atlas.width

    os.makedirs(os.path.join(RES, "textures", "entity"), exist_ok=True)
    os.makedirs(os.path.join(RES, "textures", "gui"), exist_ok=True)
    atlas.save(os.path.join(RES, "textures", "entity", "knocker.png"))
    face_crop(img, args.head).save(os.path.join(RES, "textures", "gui", "jumpscare.png"))
    write_json(os.path.join(RES, "geo", "knocker.geo.json"), geo)
    write_json(os.path.join(RES, "animations", "knocker.animation.json"), build_animations(hu))

    hit_w = min(1.2, max(0.6, wu / 16 * 0.55))
    write_size_java(hit_w, args.height)

    if args.preview_dir:
        os.makedirs(args.preview_dir, exist_ok=True)
        preview(runs, gx, gy, wu, hu, depths, args.preview_dir)

    print(f"Figure: {img.width}x{img.height}px  |  grid {gx}x{gy}  |  boxes: {len(runs)}")
    print(f"Model size: {wu / 16:.2f} x {args.height:.2f} blocks, hitbox {hit_w:.2f} x {args.height:.2f}")
    print(f"Atlas: {atlas.width}x{atlas.height}px")
    if len(runs) > 1500:
        print("WARNING: many boxes - lower --rows if the game stutters.")
    print("Done. Rebuild the mod (gradle build) to get the new .jar.")


if __name__ == "__main__":
    main()
