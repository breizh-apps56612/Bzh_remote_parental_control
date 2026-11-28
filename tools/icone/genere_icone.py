#!/usr/bin/env python3
"""Génère l'icône « Contrat parental » (téléphone + Box) et la bannière Android TV, en vecteurs Android + aperçus SVG.

Emblème : un pavé de télécommande blanc (repères haut/bas/gauche/droite) au centre duquel deux cercles,
l'un indigo, l'autre sarcelle, se chevauchent : le parent et l'enfant qui se mettent d'accord.
Fond : dégradé signature indigo → sarcelle (API 24+), couleur indigo unie en dessous.

Usage : python3 tools/icone/genere_icone.py [dossier_apercus]
"""
import pathlib, sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

root = pathlib.Path(__file__).resolve().parents[2]
prev = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path("/tmp/apercu-icone")
prev.mkdir(parents=True, exist_ok=True)

A, B, INK = "#4B48E0", "#0B8783", "#05061A"
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'
NS_AAPT = NS + ' xmlns:aapt="http://schemas.android.com/aapt"'


def circle(cx, cy, r):
    return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0,1 1,{2 * r:g},0a{r:g},{r:g} 0,1 1,{-2 * r:g},0z"


def emblem(cx, cy, s):
    """Liste (pathData, couleur, alpha) de l'emblème centré en (cx, cy), taille s (= rayon du pavé)."""
    k = s / 25.0
    out = [(circle(cx, cy, 25 * k), "#FFFFFF", 1.0)]
    # repères du pavé (petits chevrons) en lavande
    t, w = 19.5 * k, 3.2 * k
    marks = [
        f"M{cx - w:g},{cy - t + w * .9:g}L{cx:g},{cy - t:g}L{cx + w:g},{cy - t + w * .9:g}",
        f"M{cx - w:g},{cy + t - w * .9:g}L{cx:g},{cy + t:g}L{cx + w:g},{cy + t - w * .9:g}",
        f"M{cx - t + w * .9:g},{cy - w:g}L{cx - t:g},{cy:g}L{cx - t + w * .9:g},{cy + w:g}",
        f"M{cx + t - w * .9:g},{cy - w:g}L{cx + t:g},{cy:g}L{cx + t - w * .9:g},{cy + w:g}",
    ]
    out.append(("".join(marks), "STROKE:#B9BBE3:%g" % (1.9 * k), 1.0))
    d = 5.6 * k
    out.append((circle(cx - d, cy, 9.5 * k), A, 0.92))
    out.append((circle(cx + d, cy, 9.5 * k), B, 0.85))
    return out


def vec_paths(items, mono=False):
    xs = []
    for d, col, a in items:
        if col.startswith("STROKE:"):
            _, c, wd = col.split(":")
            c = "#FFFFFF" if mono else c
            xs.append(f'<path android:pathData="{d}" android:strokeColor="{c}" android:strokeWidth="{wd}" '
                      f'android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000"/>')
        else:
            c = "#FFFFFF" if mono else col
            xs.append(f'<path android:pathData="{d}" android:fillColor="{c}" android:fillAlpha="{a:g}"/>')
    return "\n    ".join(xs)


def svg_paths(items):
    xs = []
    for d, col, a in items:
        if col.startswith("STROKE:"):
            _, c, wd = col.split(":")
            xs.append(f'<path d="{d}" stroke="{c}" stroke-width="{wd}" stroke-linecap="round" stroke-linejoin="round" fill="none"/>')
        else:
            xs.append(f'<path d="{d}" fill="{col}" fill-opacity="{a:g}"/>')
    return "\n".join(xs)


def grad_android(x1, y1, x2, y2):
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
            f'android:endX="{x2}" android:endY="{y2}" android:startColor="{A}" android:endColor="{B}"/></aapt:attr>')


def vector(w, h, body, aapt=False):
    return (f'<?xml version="1.0" encoding="utf-8"?>\n<!-- Généré par tools/icone/genere_icone.py : ne pas modifier à la main -->\n'
            f'<vector {NS_AAPT if aapt else NS} android:width="{w}dp" android:height="{h}dp" '
            f'android:viewportWidth="{w}" android:viewportHeight="{h}">\n    {body}\n</vector>\n')


def write(rel, txt):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(txt, encoding="utf-8")


def text_path(txt, x, baseline, size, font="app/src/main/res/font/sora_bold.ttf", spacing=0.0):
    f = TTFont(root / font)
    gs, cmap, upm = f.getGlyphSet(), f.getBestCmap(), f["head"].unitsPerEm
    sc = size / upm
    pen = SVGPathPen(gs)
    cur = x
    for ch in txt:
        g = cmap[ord(ch)]
        gs[g].draw(TransformPen(pen, (sc, 0, 0, -sc, cur, baseline)))
        cur += gs[g].width * sc + spacing
    return pen.getCommands(), cur - x


# ---------- Icône du téléphone (et de la Box) ----------
emb = emblem(54, 54, 28)   # tient dans la zone sûre (rayon 33) des icônes adaptatives
squircle = "M54,4C88,4 104,20 104,54C104,88 88,104 54,104C20,104 4,88 4,54C4,20 20,4 54,4z"
legacy = vector(108, 108, f'<path android:pathData="{squircle}" android:fillColor="{A}"/>\n    '
                + vec_paths(emblem(54, 54, 31)))
bg_grad = vector(108, 108, f'<path android:pathData="M0,0h108v108h-108z">{grad_android(0, 0, 108, 108)}</path>', aapt=True)
fg = vector(108, 108, vec_paths(emb))
mono = vector(108, 108, vec_paths(emb[:2], mono=True)
              + f'\n    <path android:pathData="{circle(48.4, 54, 9.5)}{circle(59.6, 54, 9.5)}" android:fillColor="#FFFFFF" android:fillType="evenOdd"/>')
adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n<!-- Icône adaptative Android 8+ (thèmes Android 13+ via monochrome) -->\n'
            f'<adaptive-icon {NS}>\n    <background android:drawable="@drawable/ic_launcher_bg"/>\n'
            '    <foreground android:drawable="@drawable/ic_launcher_fg"/>\n    <monochrome android:drawable="@drawable/ic_launcher_mono"/>\n</adaptive-icon>\n')

for mod in ("app", "box"):
    write(f"{mod}/src/main/res/drawable/ic_launcher.xml", legacy)
    write(f"{mod}/src/main/res/drawable-v24/ic_launcher_bg.xml", bg_grad)
    write(f"{mod}/src/main/res/drawable/ic_launcher_bg.xml", vector(108, 108, f'<path android:pathData="M0,0h108v108h-108z" android:fillColor="{A}"/>'))
    write(f"{mod}/src/main/res/drawable/ic_launcher_fg.xml", fg)
    write(f"{mod}/src/main/res/drawable/ic_launcher_mono.xml", mono)
    write(f"{mod}/src/main/res/drawable-anydpi-v26/ic_launcher.xml", adaptive)

# ---------- Bannière Android TV 320×180 ----------
ban_emb = emblem(64, 90, 36)
tx = 116
l1, _ = text_path("Contrat", tx, 78, 30)
l2, _ = text_path("parental", tx, 110, 30)
title_d = l1 + l2
sub_d, _ = text_path("TV · temps d'écran en famille", tx, 134, 11.5, "app/src/main/res/font/sora_semibold.ttf")
ban_body = (lambda bg: f'{bg}\n    ' + vec_paths(ban_emb)
            + f'\n    <path android:pathData="{title_d}" android:fillColor="#FFFFFF"/>'
            + f'\n    <path android:pathData="{sub_d}" android:fillColor="#FFFFFF" android:fillAlpha="0.82"/>')
write("box/src/main/res/drawable/banner.xml", vector(320, 180, ban_body(f'<path android:pathData="M0,0h320v180h-320z" android:fillColor="{A}"/>')))
write("box/src/main/res/drawable-v24/banner.xml", vector(320, 180, ban_body(
    f'<path android:pathData="M0,0h320v180h-320z">{grad_android(0, 0, 320, 180)}</path>'), aapt=True))

# ---------- Aperçus SVG ----------
gdef = f'<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="{A}"/><stop offset="1" stop-color="{B}"/></linearGradient></defs>'
(prev / "icone.svg").write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">{gdef}'
                                f'<path d="{squircle}" fill="url(#g)"/>{svg_paths(emb)}</svg>')
(prev / "banniere.svg").write_text(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180" width="960" height="540">{gdef}'
                                   f'<rect width="320" height="180" fill="url(#g)"/>{svg_paths(ban_emb)}'
                                   f'<path d="{title_d}" fill="#fff"/><path d="{sub_d}" fill="#fff" fill-opacity=".82"/></svg>')
print("ok", prev)
