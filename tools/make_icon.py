"""Theater Planner icon: the PICO 3D icon layers and the 512 export, drawn from the same shapes as the
adaptive icon (`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`).

    python tools/make_icon.py [export.png]

Layer 0: the velvet tile. Layer 1: the ring of 5.1 speakers around the listening position (white).
sdf_N = the layer's alpha in white. Coordinates below are the vector's 108-unit viewport.
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
K = S / 108.0
VELVET = (110, 31, 51, 255)
WHITE = (255, 255, 255, 255)
# (cx, cy, half size) of the five speakers and the listener dot: design appIcon.glyph
SPEAKERS = [(54, 32, 7), (33, 37.5, 5), (75, 37.5, 5), (22, 74.5, 5), (86, 74.5, 5)]
DOT = (54, 58, 6.5)


def tile():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((40, 40, S - 40, S - 40), radius=220, fill=VELVET)
    return im


def front():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    cx, cy, r = DOT
    d.ellipse(((cx - r) * K, (cy - r) * K, (cx + r) * K, (cy + r) * K), fill=WHITE)
    for cx, cy, h in SPEAKERS:
        d.rounded_rectangle(((cx - h) * K, (cy - h) * K, (cx + h) * K, (cy + h) * K), radius=h * 0.45 * K, fill=WHITE)
    return im


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    l0, l1 = tile(), front()
    draw = os.path.join(RES, "drawable")
    l0.save(os.path.join(draw, "icon_3d_layer_0.png"))
    l1.save(os.path.join(draw, "icon_3d_layer_1.png"))
    alpha_white(l0).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(l1).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        # the 512 export: the full tile (no rounded inset) under the glyph, as the launcher shows it
        full = Image.new("RGBA", (S, S), VELVET)
        Image.alpha_composite(full, l1).resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("icon layers written")


if __name__ == "__main__":
    main()
