"""Generates TubeBox brand assets: launcher icons, Android TV banner and GitHub images.

Run: python tools/generate_brand_assets.py
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
ASSETS = os.path.join(ROOT, "docs", "assets")
FONTS = r"C:\Windows\Fonts"

BG_STOPS = [(0.0, (49, 46, 129, 255)), (0.55, (109, 40, 217, 255)), (1.0, (8, 145, 178, 255))]
SCREEN_STOPS = [(0.0, (255, 84, 160, 255)), (1.0, (255, 176, 59, 255))]
WHITE = (255, 255, 255, 255)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def gradient(w, h, stops, angle_deg=45, small=256):
    sw = small
    sh = max(1, int(small * h / w))
    img = Image.new("RGBA", (sw, sh))
    px = img.load()
    ang = math.radians(angle_deg)
    dx, dy = math.cos(ang), math.sin(ang)
    projs = [x * dx + y * dy for x, y in [(0, 0), (sw, 0), (0, sh), (sw, sh)]]
    mn, mx = min(projs), max(projs)
    for y in range(sh):
        for x in range(sw):
            t = ((x * dx + y * dy) - mn) / (mx - mn)
            for i in range(len(stops) - 1):
                p0, c0 = stops[i]
                p1, c1 = stops[i + 1]
                if t <= p1 or i == len(stops) - 2:
                    tt = 0 if p1 == p0 else min(max((t - p0) / (p1 - p0), 0), 1)
                    px[x, y] = lerp(c0, c1, tt)
                    break
    return img.resize((w, h), Image.BICUBIC)


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask


def render_icon(S=1024, margin=0.04, with_background=True):
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    k = S / 1024

    def R(*v):
        return [int(x * k) for x in v]

    if with_background:
        m = int(S * margin)
        size = (S - 2 * m, S - 2 * m)
        bg = gradient(size[0], size[1], BG_STOPS, 45)
        hl = Image.new("RGBA", size, (0, 0, 0, 0))
        ImageDraw.Draw(hl).ellipse([-S * 0.3, -S * 0.5, S * 0.85, S * 0.42], fill=(255, 255, 255, 42))
        hl = hl.filter(ImageFilter.GaussianBlur(S * 0.06))
        bg = Image.alpha_composite(bg, hl)
        img.paste(bg, (m, m), rounded_mask(size, int(S * 0.22)))

    # Soft shadow below the TV
    sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle(R(212, 380, 812, 800), radius=int(76 * k), fill=(10, 6, 40, 150))
    sh = sh.filter(ImageFilter.GaussianBlur(28 * k))
    img = Image.alpha_composite(img, sh)

    d = ImageDraw.Draw(img)
    # Antennae
    w = int(30 * k)
    d.line(R(512, 360, 392, 222), fill=WHITE, width=w)
    d.line(R(512, 360, 632, 222), fill=WHITE, width=w)
    for cx, cy, r in [(392, 222, 28), (632, 222, 28), (512, 352, 34)]:
        d.ellipse(R(cx - r, cy - r, cx + r, cy + r), fill=WHITE)
    # Legs
    d.rounded_rectangle(R(318, 740, 388, 812), radius=int(18 * k), fill=WHITE)
    d.rounded_rectangle(R(636, 740, 706, 812), radius=int(18 * k), fill=WHITE)
    # TV body
    d.rounded_rectangle(R(212, 350, 812, 770), radius=int(76 * k), fill=WHITE)

    # Screen with warm gradient and gloss
    sx0, sy0, sx1, sy1 = R(256, 394, 768, 726)
    scr = gradient(sx1 - sx0, sy1 - sy0, SCREEN_STOPS, 45)
    gl = Image.new("RGBA", scr.size, (0, 0, 0, 0))
    ImageDraw.Draw(gl).polygon(
        [(0, 0), (int(scr.size[0] * 0.55), 0), (int(scr.size[0] * 0.25), scr.size[1]), (0, scr.size[1])],
        fill=(255, 255, 255, 38),
    )
    scr = Image.alpha_composite(scr, gl)
    img.paste(scr, (sx0, sy0), rounded_mask(scr.size, int(46 * k)))

    # Play triangle with shadow
    tri = [(470, 488), (470, 632), (592, 560)]
    ts = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(ts).polygon([(int(x * k), int((y + 10) * k)) for x, y in tri], fill=(120, 20, 60, 110))
    ts = ts.filter(ImageFilter.GaussianBlur(10 * k))
    img = Image.alpha_composite(img, ts)
    d = ImageDraw.Draw(img)
    pts = [(int(x * k), int(y * k)) for x, y in tri]
    d.polygon(pts, fill=WHITE)
    d.line(pts + [pts[0], pts[1]], fill=WHITE, width=int(24 * k), joint="curve")
    return img


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size)


def render_wide(W, H, title_scale, subtitle, subtitle_scale, glow=True):
    img = gradient(W, H, [(0.0, (17, 14, 48, 255)), (0.6, (46, 22, 110, 255)), (1.0, (10, 80, 110, 255))], 20)
    icon_size = int(H * 0.62)
    ix = int(H * 0.17)
    iy = (H - icon_size) // 2
    if glow:
        g = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(g).ellipse(
            [ix - icon_size * 0.15, iy - icon_size * 0.15, ix + icon_size * 1.15, iy + icon_size * 1.15],
            fill=(139, 92, 246, 120),
        )
        g = g.filter(ImageFilter.GaussianBlur(icon_size * 0.18))
        img = Image.alpha_composite(img, g)
    icon = render_icon(1024, 0.0).resize((icon_size, icon_size), Image.LANCZOS)
    img.alpha_composite(icon, (ix, iy))

    d = ImageDraw.Draw(img)
    tx = ix + icon_size + int(H * 0.1)
    title_font = font("seguibl.ttf", int(H * title_scale))
    sub_font = font("segoeuisl.ttf", int(H * subtitle_scale))
    tb = d.textbbox((0, 0), "TubeBox", font=title_font)
    sb = d.textbbox((0, 0), subtitle, font=sub_font)
    th = tb[3] - tb[1]
    shh = sb[3] - sb[1]
    gap = int(H * 0.04)
    top = (H - (th + gap + shh)) // 2
    d.text((tx, top - tb[1]), "TubeBox", font=title_font, fill=WHITE)
    d.text((tx, top + th + gap - sb[1]), subtitle, font=sub_font, fill=(196, 181, 253, 255))
    return img


def main():
    os.makedirs(ASSETS, exist_ok=True)
    master = render_icon(1024)
    for folder, px in [("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                       ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
        out = os.path.join(RES, folder)
        os.makedirs(out, exist_ok=True)
        master.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
    master.resize((512, 512), Image.LANCZOS).save(os.path.join(ASSETS, "icon-512.png"))

    banner = render_wide(1280, 720, 0.24, "Vídeos leves para TV Box", 0.085)
    os.makedirs(os.path.join(RES, "drawable-xhdpi"), exist_ok=True)
    banner.resize((320, 180), Image.LANCZOS).save(os.path.join(RES, "drawable-xhdpi", "tv_banner.png"))

    social = render_wide(1280, 640, 0.22, "YouTube leve e rápido para TV Box Android", 0.06)
    social.convert("RGB").save(os.path.join(ASSETS, "social-preview.png"))
    print("Brand assets generated.")


if __name__ == "__main__":
    main()
