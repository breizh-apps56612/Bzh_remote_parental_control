#!/usr/bin/env python3
"""Fabrique les GIF animés du site à partir des vraies captures (lancer ScreenshotTest avec SCREENS=1 avant)."""
import math, os, shutil, subprocess, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter

SRC = os.environ.get("SCREENS_DIR", "app/build/screens")
OUT = sys.argv[1] if len(sys.argv) > 1 else "site"
os.makedirs(OUT, exist_ok=True)
BG = (8, 8, 12)
S = 2  # suréchantillonnage
FPS = 20
FONT = "/usr/share/fonts/opentype/inter/Inter-SemiBold.otf"


def font(px):
    return ImageFont.truetype(FONT, px * S)


def load(name):
    p = f"{SRC}/{name}.png"
    if not os.path.exists(p):
        p = f"{SRC}/frames/{name}.png"
    return Image.open(p).convert("RGB")


def rounded(im, r):
    m = Image.new("L", im.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, im.size[0] - 1, im.size[1] - 1), r, fill=255)
    return m


def phone(screen, w):
    """Téléphone : écran 1080×2400 réduit à la largeur w (en px finaux), avec bordure."""
    w *= S
    h = round(w * screen.size[1] / screen.size[0])
    sc = screen.resize((w, h), Image.LANCZOS)
    b = 9 * S
    body = Image.new("RGBA", (w + 2 * b, h + 2 * b), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle((0, 0, body.size[0] - 1, body.size[1] - 1), 44 * S, fill=(30, 30, 40, 255), outline=(70, 70, 88, 255), width=S * 2)
    body.paste(sc, (b, b), rounded(sc, 36 * S))
    return body, w / 1080  # facteur écran -> pixels suréchantillonnés


def tv(screen, w):
    w *= S
    h = round(w * 1080 / 1920)
    sc = screen.resize((w, h), Image.LANCZOS)
    b = 7 * S
    body = Image.new("RGBA", (w + 2 * b, h + 2 * b + 26 * S), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle((0, 0, w + 2 * b - 1, h + 2 * b - 1), 10 * S, fill=(22, 22, 30, 255), outline=(70, 70, 88, 255), width=S * 2)
    body.paste(sc, (b, b))
    cx = body.size[0] // 2
    d.rectangle((cx - 6 * S, h + 2 * b, cx + 6 * S, h + 2 * b + 16 * S), fill=(40, 40, 52, 255))
    d.rounded_rectangle((cx - 70 * S, h + 2 * b + 14 * S, cx + 70 * S, h + 2 * b + 24 * S), 5 * S, fill=(40, 40, 52, 255))
    return body, w / 1920


def blend(a, b, t):
    return Image.blend(a, b, max(0.0, min(1.0, t)))


def ease(t):
    t = max(0.0, min(1.0, t))
    return t * t * (3 - 2 * t)


def finger(img, x, y, alpha=1.0, ripple=None):
    """Doigt (rond translucide) et onde de toucher, coordonnées suréchantillonnées."""
    ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    if ripple is not None:
        r = (14 + 46 * ripple) * S
        d.ellipse((x - r, y - r, x + r, y + r), outline=(255, 255, 255, int(200 * (1 - ripple) * alpha)), width=3 * S)
    r = 19 * S
    d.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, int(120 * alpha)), outline=(255, 255, 255, int(230 * alpha)), width=2 * S)
    img.alpha_composite(ov)


def trail(img, pts, alpha=1.0):
    ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for i in range(1, len(pts)):
        a = int(150 * alpha * i / len(pts))
        d.line((pts[i - 1], pts[i]), fill=(185, 160, 255, a), width=10 * S)
    img.alpha_composite(ov.filter(ImageFilter.GaussianBlur(2 * S)))


def caption(img, text, y, alpha=1.0, size=17, sub=None):
    if not text:
        return
    f = font(size)
    d0 = ImageDraw.Draw(img)
    tw = d0.textlength(text, font=f)
    pad = 16 * S
    h = (size + 20) * S
    x0 = (img.size[0] - tw) / 2 - pad
    ov = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    d.rounded_rectangle((x0, y, x0 + tw + 2 * pad, y + h), h / 2, fill=(139, 108, 255, int(235 * alpha)))
    d.text((img.size[0] / 2, y + h / 2), text, font=f, fill=(255, 255, 255, int(255 * alpha)), anchor="mm")
    img.alpha_composite(ov)


class Gif:
    def __init__(self, name, w, h):
        self.name, self.w, self.h = name, w, h
        self.frames = []

    def canvas(self):
        return Image.new("RGBA", (self.w * S, self.h * S), BG + (255,))

    def add(self, im, n=1):
        small = im.convert("RGB").resize((self.w, self.h), Image.LANCZOS)
        for _ in range(n):
            self.frames.append(small)

    def save(self):
        tmp = f"/tmp/gif-{self.name}"
        shutil.rmtree(tmp, ignore_errors=True)
        os.makedirs(tmp)
        for i, f in enumerate(self.frames):
            f.save(f"{tmp}/{i:04d}.png")
        out = f"{OUT}/{self.name}.gif"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(FPS), "-i", f"{tmp}/%04d.png",
                        "-filter_complex", "[0:v]split[a][b];[a]palettegen=max_colors=256:stats_mode=full[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
                        "-loop", "0", out], check=True)
        # image fixe (première image utile) pour l'aperçu et les réseaux
        self.frames[min(len(self.frames) - 1, 10)].save(f"{OUT}/{self.name}.png")
        print(out, len(self.frames), "images", os.path.getsize(out) // 1024, "Ko")


def sec(s):
    return max(1, round(s * FPS))


# ---------- 1. La télécommande ----------
def remote():
    g = Gif("telecommande", 360, 780)
    body, k = phone(load("00-telecommande"), 300)
    ox, oy = (g.w * S - body.size[0]) // 2, 14 * S
    b = 9 * S

    def P(x, y):
        return ox + b + x * k, oy + b + y * k

    def base():
        c = g.canvas()
        c.alpha_composite(body, (ox, oy))
        return c
    cy = 726 * S

    def still(text, s):
        for i in range(sec(s)):
            c = base(); caption(c, text, cy); g.add(c)

    def swipe(text, a, z, s=0.7):
        n = sec(s)
        pts = []
        for i in range(n):
            t = ease(i / (n - 1))
            p = (a[0] + (z[0] - a[0]) * t, a[1] + (z[1] - a[1]) * t)
            pts.append(P(*p))
            c = base(); trail(c, pts[-8:]); finger(c, *pts[-1]); caption(c, text, cy); g.add(c)
        for i in range(sec(0.5)):
            c = base(); trail(c, pts[-8:], 1 - i / sec(0.5)); finger(c, *pts[-1], alpha=1 - i / sec(0.5)); caption(c, text, cy); g.add(c)

    def tap(text, p, s=0.9):
        n = sec(s)
        for i in range(n):
            t = i / (n - 1)
            c = base()
            finger(c, *P(*p), alpha=1 if t < 0.7 else (1 - t) / 0.3, ripple=min(1, t * 1.6))
            caption(c, text, cy); g.add(c)

    still("Le téléphone devient la télécommande", 1.4)
    swipe("Glisser : flèche droite", (540, 1090), (860, 1090))
    swipe("Glisser : flèche bas", (540, 900), (540, 1300))
    tap("Toucher : OK", (540, 1090))
    tap("Volume +", (192, 1740))
    tap("Lecture / pause", (640, 1900))
    tap("Accueil", (440, 1700))
    still("Le téléphone devient la télécommande", 0.6)
    g.save()


# ---------- 2. Le code parent depuis le téléphone ----------
def pin():
    g = Gif("code-parent", 960, 540)
    tvs = {k: load(v) for k, v in [("fini", "tv-01-temps-ecoule"), ("pad", "tv-03-code-parent"), ("play", "tv-05-sablier")]}
    # écran « lecture » : on retire la pastille « Encore 4 min » en recopiant le ciel voisin
    p = tvs["play"].copy()
    p.paste(p.crop((1100, 40, 1500, 180)), (1480, 40))
    tvs["play"] = p
    phs = {k: load(v) for k, v in [("dash", "02-tableau-de-bord-sans-demande"), ("ask", "07-validation-code")]}
    tvb = {k: tv(v, 600)[0] for k, v in tvs.items()}
    phb = {}
    for kk, v in phs.items():
        phb[kk], pk = phone(v, 200)
    tx, ty = 36 * S, 70 * S
    px, py = 712 * S, 26 * S
    cy = 470 * S

    def frame(tva, tvbb, tvt, pha, phb_, pht, text, shake=0, fing=None):
        c = g.canvas()
        c.alpha_composite(blend(tvb[tva], tvb[tvbb], tvt), (tx, ty))
        c.alpha_composite(blend(phb[pha], phb[phb_], pht), (px + shake, py))
        if fing:
            finger(c, *fing[:2], alpha=fing[2], ripple=fing[3])
        caption(c, text, cy, size=18)
        return c

    def hold(state, text, s):
        for i in range(sec(s)):
            g.add(frame(*state, text))

    hold(("fini", "fini", 0, "dash", "dash", 0), "1. Le temps de TV est écoulé", 1.6)
    n = sec(0.5)
    for i in range(n):
        g.add(frame("fini", "pad", ease(i / (n - 1)), "dash", "dash", 0, "2. L'enfant demande à continuer"))
    hold(("pad", "pad", 1, "dash", "dash", 0), "2. L'enfant demande à continuer", 1.1)
    for i in range(n):
        g.add(frame("pad", "pad", 1, "dash", "ask", ease(i / (n - 1)), "3. Le téléphone du parent reçoit la demande", shake=int(4 * S * math.sin(i * 2.4))))
    hold(("pad", "pad", 1, "ask", "ask", 1), "3. Le téléphone du parent reçoit la demande", 1.6)
    bx, by = px + 9 * S + 540 * pk, py + 9 * S + 1420 * pk
    m = sec(0.9)
    for i in range(m):
        t = i / (m - 1)
        g.add(frame("pad", "pad", 1, "ask", "ask", 1, "4. Il valide avec son empreinte", fing=(bx, by, 1 if t < 0.7 else (1 - t) / 0.3, min(1, t * 1.6))))
    for i in range(n):
        e = ease(i / (n - 1))
        g.add(frame("pad", "play", e, "ask", "dash", e, "5. C'est validé : la lecture reprend"))
    hold(("play", "play", 1, "dash", "dash", 1), "5. C'est validé : la lecture reprend", 2.0)
    for i in range(n):
        e = ease(i / (n - 1))
        g.add(frame("play", "fini", e, "dash", "dash", 1, ""))
    g.save()


# ---------- 3. Accepter une demande ----------
def request():
    g = Gif("demande", 360, 780)
    a, k = phone(load("01-tableau-de-bord"), 300)
    b_, _ = phone(load("02-tableau-de-bord-sans-demande"), 300)
    ox, oy = (g.w * S - a.size[0]) // 2, 14 * S
    cy = 726 * S

    def fr(t, text, fing=None):
        c = g.canvas()
        c.alpha_composite(blend(a, b_, t), (ox, oy))
        if fing:
            finger(c, *fing[:2], alpha=fing[2], ripple=fing[3])
        caption(c, text, cy)
        return c
    for i in range(sec(1.8)):
        g.add(fr(0, "La TV demande 15 min de YouTube"))
    fx, fy = ox + 9 * S + 330 * k, oy + 9 * S + 670 * k
    m = sec(0.9)
    for i in range(m):
        t = i / (m - 1)
        g.add(fr(0, "Accepter", (fx, fy, 1 if t < 0.7 else (1 - t) / 0.3, min(1, t * 1.6))))
    n = sec(0.45)
    for i in range(n):
        g.add(fr(ease(i / (n - 1)), "C'est accordé, la TV est prévenue"))
    for i in range(sec(2.2)):
        g.add(fr(1, "C'est accordé, la TV est prévenue"))
    for i in range(n):
        g.add(fr(1 - ease(i / (n - 1)), ""))
    g.save()


# ---------- 4. Les règles (défilement réel) ----------
def rules():
    g = Gif("regles", 360, 780)
    frames = [phone(load(f"regles-{i:02d}"), 300)[0] for i in range(41)]
    ox, oy = (g.w * S - frames[0].size[0]) // 2, 14 * S
    cy = 726 * S

    def fr(i, text):
        c = g.canvas()
        c.alpha_composite(frames[i], (ox, oy))
        caption(c, text, cy)
        return c

    def label(i):
        return "Temps par jour, couvre-feu" if i < 14 else ("Un profil par enfant" if i < 27 else "Appli par appli")
    for _ in range(sec(1.2)):
        g.add(fr(0, label(0)))
    for i in range(41):
        g.add(fr(i, label(i)), 2)
        if i in (13, 26):
            for _ in range(sec(1.0)):
                g.add(fr(i, label(i)))
    for _ in range(sec(1.4)):
        g.add(fr(40, label(40)))
    for i in range(40, -1, -4):
        g.add(fr(i, ""))
    g.save()


# ---------- 5. Les statistiques (onglets réels) ----------
def stats():
    W = 420
    g = Gif("activite", W, 600)
    off = {0: 164, 1: 40, 2: 40}
    crops = {}
    for t in range(3):
        im = load(f"activite-{t}")
        cr = im.crop((0, off[t] - 30, 1080, off[t] - 30 + 1290))
        w = W * S - 40 * S
        h = round(w * cr.size[1] / cr.size[0])
        cr = cr.resize((w, h), Image.LANCZOS)
        card = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        card.paste(cr, (0, 0), rounded(cr, 28 * S))
        crops[t] = card
    k = (W * S - 40 * S) / 1080
    ox, oy = 20 * S, 14 * S
    cy = 530 * S
    tabs = {0: 267, 1: 580, 2: 892}  # x des onglets, écran 1080
    tab_y = (off[1] + 175 - (off[1] - 30))  # y de la rangée d'onglets dans la découpe

    def fr(a, b, t, text, fing=None):
        c = g.canvas()
        c.alpha_composite(blend(crops[a], crops[b], t), (ox, oy))
        if fing:
            finger(c, *fing[:2], alpha=fing[2], ripple=fing[3])
        caption(c, text, cy)
        return c
    names = {0: "Aujourd'hui, appli par appli", 1: "La semaine en un coup d'œil", 2: "Les heures où la TV est allumée"}
    seq = [0, 1, 2, 0]
    for j in range(3):
        a, b = seq[j], seq[j + 1]
        for _ in range(sec(2.0)):
            g.add(fr(a, a, 0, names[a]))
        fx, fy = ox + tabs[b] * k, oy + tab_y * k
        m = sec(0.7)
        for i in range(m):
            t = i / (m - 1)
            g.add(fr(a, a, 0, names[a], (fx, fy, 1 if t < 0.7 else (1 - t) / 0.3, min(1, t * 1.6))))
        n = sec(0.4)
        for i in range(n):
            g.add(fr(a, b, ease(i / (n - 1)), names[b]))
    g.save()


if __name__ == "__main__":
    which = sys.argv[2:] or ["remote", "pin", "request", "rules", "stats"]
    for w in which:
        globals()[w]()
