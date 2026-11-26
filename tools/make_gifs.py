#!/usr/bin/env python3
"""Fabrique les GIF animés du site à partir des vraies captures de l'appli (Robolectric)."""
import json, math, os, shutil, subprocess, sys
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


def pos(name):
    """Positions des éléments notées par les tests de capture (px écran 1080×2400)."""
    with open(f"{SRC}/{name}.json") as f:
        return json.load(f)


def center(b):
    return (b[0] + b[2]) / 2, (b[1] + b[3]) / 2


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
        d.line((pts[i - 1], pts[i]), fill=(120, 150, 255, a), width=10 * S)
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
    w = int(tw + 2 * pad)
    grad = Image.new("RGBA", (w, int(h)))
    gd = ImageDraw.Draw(grad)
    for x in range(w):  # dégradé Aurora : indigo -> sarcelle
        t = x / max(1, w - 1)
        gd.line((x, 0, x, h), fill=(int(75 + (11 - 75) * t), int(72 + (135 - 72) * t), int(224 + (131 - 224) * t), int(240 * alpha)))
    m = Image.new("L", grad.size, 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, int(h) - 1), h / 2, fill=255)
    ov.paste(grad, (int(x0), int(y)), m)
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

    q = pos("00-telecommande")
    l, t, r, btm = q["pad"]
    cx, cyy = center(q["pad"])
    strip = 46 * q["density"]
    volx, chx = r - strip / 2 - 12, l + strip / 2 + 12

    def hold_left(text, s=2.2):
        """Glisser vers la gauche puis garder le doigt posé : retour en continu."""
        a, z = (cx + 80, cyy), (cx - 170, cyy)
        n = sec(0.5); pts = []
        for i in range(n):
            e = ease(i / (n - 1))
            pts.append(P(a[0] + (z[0] - a[0]) * e, a[1]))
            c = base(); trail(c, pts[-8:]); finger(c, *pts[-1]); caption(c, text, cy); g.add(c)
        m = sec(s)
        for i in range(m):
            c = base()
            # le doigt reste posé : ondes répétées comme les appuis envoyés en continu
            finger(c, *pts[-1], ripple=(i % sec(0.45)) / sec(0.45))
            caption(c, text, cy); g.add(c)

    still("Le téléphone devient la télécommande", 1.4)
    swipe("Glisser : flèche droite", (cx - 120, cyy), (cx + 220, cyy))
    swipe("Glisser : flèche bas", (cx, cyy - 260), (cx, cyy + 200))
    tap("Toucher : OK", (cx, cyy))
    hold_left("Rester appuyé : retour en continu")
    tap("Barre de droite : volume +", (volx, t + 120))
    tap("Barre de gauche : chaîne suivante", (chx, t + 120))
    tap("Lecture / pause", center(q["play"]))
    tap("Accueil", center(q["home"]))
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
    ax, ay = center(pos("01-tableau-de-bord")["accept"])
    fx, fy = ox + 9 * S + ax * k, oy + 9 * S + ay * k
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
    N = len([f for f in os.listdir(f"{SRC}/frames") if f.startswith("regles-")])
    frames = [phone(load(f"regles-{i:02d}"), 300)[0] for i in range(N)]
    ox, oy = (g.w * S - frames[0].size[0]) // 2, 14 * S
    cy = 726 * S

    def fr(i, text):
        c = g.canvas()
        c.alpha_composite(frames[i], (ox, oy))
        caption(c, text, cy)
        return c

    def label(i):
        f = i / (N - 1)
        return ("Temps par jour, couvre-feu" if f < 0.31 else "Un profil par enfant" if f < 0.56
                else "Appli par appli" if f < 0.78 else "Programmes selon l'âge")
    for _ in range(sec(1.2)):
        g.add(fr(0, label(0)))
    pauses = {round(N * 0.41), round(N * 0.66)}
    for i in range(N):
        g.add(fr(i, label(i)), 2)
        if i in pauses:
            for _ in range(sec(1.0)):
                g.add(fr(i, label(i)))
    for _ in range(sec(1.4)):
        g.add(fr(N - 1, label(N - 1)))
    for i in range(N - 1, -1, -6):
        g.add(fr(i, ""))
    g.save()


# ---------- 5. Les statistiques (onglets réels) ----------
def stats():
    W, H = 420, 720
    g = Gif("activite", W, H)
    q = pos("activite")
    top, bot = q["hero"][1] - 30, 2040  # de l'anneau « qui regarde » jusqu'aux barres de la semaine
    w = W * S - 40 * S
    k = w / 1080
    crops = {}
    for t in range(3):
        cr = load(f"activite-{t}").crop((0, top, 1080, bot))
        h = round(w * cr.size[1] / cr.size[0])
        cr = cr.resize((w, h), Image.LANCZOS)
        card = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        card.paste(cr, (0, 0), rounded(cr, 28 * S))
        crops[t] = card
    ox, oy = 20 * S, 14 * S
    cy = (H - 62) * S

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
        tx, ty = center(q[f"tab{b}"])
        fx, fy = ox + tx * k, oy + (ty - top) * k
        m = sec(0.7)
        for i in range(m):
            t = i / (m - 1)
            g.add(fr(a, a, 0, names[a], (fx, fy, 1 if t < 0.7 else (1 - t) / 0.3, min(1, t * 1.6))))
        n = sec(0.4)
        for i in range(n):
            g.add(fr(a, b, ease(i / (n - 1)), names[b]))
    g.save()


# ---------- 6. L'installation guidée par le téléphone ----------
def note_tv(lines, w=600):
    """Écran de TV d'illustration (applis tierces qu'on ne reproduit pas) : simple panneau d'explication."""
    W, H = 1920, 1080
    im = Image.new("RGB", (W, H), (14, 14, 22))
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((360, 300, 1560, 780), 40, outline=(110, 100, 150), width=4)
    f1 = ImageFont.truetype(FONT, 64)
    f2 = ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-Medium.otf", 44)
    f3 = ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-Medium.otf", 34)
    d.text((W / 2, 400), lines[0], font=f1, fill=(243, 242, 250), anchor="mm")
    y = 520
    for l in lines[1:]:
        d.text((W / 2, y), l, font=f2, fill=(200, 197, 220), anchor="mm"); y += 70
    d.text((W / 2, 980), "Sur la TV", font=f3, fill=(120, 116, 140), anchor="mm")
    return im


def install():
    g = Gif("installation", 960, 540)
    tvs = {
        "store": note_tv(["Play Store", "La page de Downloader s'ouvre"]),
        "dl": note_tv(["Downloader", "Installer › Ouvrir"]),
        "recv": note_tv(["Downloader", "Téléchargement de l'appli Box…"]),
        "inst": note_tv(["Installation", "Installer › Ouvrir"]),
        "activer": load("tv-08-activer"),
        "play": load("tv-05-sablier"),
    }
    p = tvs["play"].copy(); p.paste(p.crop((1100, 40, 1500, 180)), (1480, 40)); tvs["play"] = p
    code = load("tv-09-code-appairage")
    tvs["code"] = code
    tvb = {k: tv(v, 600)[0] for k, v in tvs.items()}
    phs = {f"i{i}": load(f"install-{i}") for i in range(1, 8)}
    phs["dash"] = load("02-tableau-de-bord-sans-demande")
    phb = {}
    for kk, v in phs.items():
        phb[kk], pk = phone(v, 200)
    tx, ty = 36 * S, 70 * S
    px, py = 712 * S, 26 * S
    cy = 470 * S

    def frame(ta, tb, tt, pa, pb, pt, text, fing=None):
        c = g.canvas()
        c.alpha_composite(blend(tvb[ta], tvb[tb], tt), (tx, ty))
        c.alpha_composite(blend(phb[pa], phb[pb], pt), (px, py))
        if fing:
            finger(c, *fing[:2], alpha=fing[2], ripple=fing[3])
        caption(c, text, cy, size=18)
        return c

    def at(x, y):
        return px + 9 * S + x * pk, py + 9 * S + y * pk

    def hold(t, ph, text, s):
        for _ in range(sec(s)):
            g.add(frame(t, t, 0, ph, ph, 0, text))

    def tap(t, ph, text, xy):
        m = sec(0.8)
        for i in range(m):
            k = i / (m - 1)
            g.add(frame(t, t, 0, ph, ph, 0, text, (*at(*xy), 1 if k < 0.7 else (1 - k) / 0.3, min(1, k * 1.6))))

    def go(t0, t1, p0, p1, text):
        n = sec(0.45)
        for i in range(n):
            e = ease(i / (n - 1))
            g.add(frame(t0, t1, e if t0 != t1 else 0, p0, p1, e if p0 != p1 else 0, text))

    T = "1. Le téléphone ouvre Downloader sur la TV"
    hold("play", "i1", "Sur le téléphone : Installer l'appli Box", 1.4)
    tap("play", "i1", T, (592, 976))
    go("play", "store", "i1", "i2", T)
    hold("store", "i2", "Installez Downloader : la case se coche seule", 1.6)
    go("store", "dl", "i2", "i3", "2. Le téléphone envoie l'appli à la TV")
    tap("dl", "i3", "2. Le téléphone envoie l'appli à la TV", (592, 1288))
    go("dl", "recv", "i3", "i4", "La TV la télécharge depuis le téléphone")
    hold("recv", "i4", "La TV la télécharge depuis le téléphone", 1.5)
    go("recv", "inst", "i4", "i5", "3. Installer, puis ouvrir")
    hold("inst", "i5", "3. Installer, puis ouvrir", 1.4)
    go("inst", "activer", "i5", "i6", "4. Activer la protection sur la TV")
    hold("activer", "i6", "4. Activer la protection sur la TV", 1.8)
    go("activer", "activer", "i6", "i7", "5. Relier le téléphone")
    tap("activer", "i7", "5. Relier le téléphone", (592, 1788))
    go("activer", "code", "i7", "i7", "Tapez le code affiché sur la TV")
    hold("code", "i7", "Tapez le code affiché sur la TV", 1.6)
    go("code", "play", "i7", "dash", "C'est prêt. Les mises à jour seront proposées toutes seules.")
    hold("play", "dash", "C'est prêt. Les mises à jour seront proposées toutes seules.", 2.4)
    go("play", "play", "dash", "i1", "")
    g.save()


if __name__ == "__main__":
    which = sys.argv[2:] or ["remote", "pin", "request", "rules", "stats", "install"]
    for w in which:
        globals()[w]()
