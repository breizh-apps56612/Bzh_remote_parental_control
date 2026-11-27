#!/usr/bin/env python3
"""Tour automatique d'une appli sur émulateur : parcourt tous les écrans accessibles en touchant chaque élément.

  python3 tour.py <appareil adb> <paquet> <dossier de sortie> [--minutes 12] [--launcher LEANBACK_LAUNCHER]

Produit dans le dossier : ecrans.json (écrans, éléments, chemins, liens), captures/eNN.png, index.html (galerie).
Ne touche pas les éléments destructifs (supprimer, réinitialiser, désinstaller…) pour garder l'appli explorable.
"""
import hashlib, html, json, os, re, subprocess, sys, time, xml.etree.ElementTree as ET

DEV, PKG, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
MINUTES = float(sys.argv[sys.argv.index("--minutes") + 1]) if "--minutes" in sys.argv else 12
LAUNCHER = sys.argv[sys.argv.index("--launcher") + 1] if "--launcher" in sys.argv else "LAUNCHER"
MAX_PAR_ECRAN, MAX_PROFONDEUR, MAX_ECRANS = 18, 7, 90
DANGER = re.compile(r"supprim|effac|réinitialis|reinitialis|désinstall|desinstall|oublier|retirer|delete|reset|uninstall|dissocier", re.I)
FIN = time.time() + MINUTES * 60
os.makedirs(os.path.join(OUT, "captures"), exist_ok=True)

def adb(*a, t=30, binary=False):
    try:
        r = subprocess.run(["adb", "-s", DEV, *a], capture_output=True, timeout=t)
        return r.stdout if binary else r.stdout.decode(errors="replace")
    except subprocess.TimeoutExpired:
        return b"" if binary else ""

def lancer():
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category." + LAUNCHER, "1")
    time.sleep(3)

def activite():
    s = adb("shell", "dumpsys", "activity", "activities")
    m = re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*? ([\w.]+)/([\w.$]+)", s)
    return (m.group(1), m.group(2).split(".")[-1]) if m else ("?", "?")

def noeuds():
    adb("shell", "uiautomator", "dump", "/sdcard/tour.xml", t=40)
    raw = adb("shell", "cat", "/sdcard/tour.xml")
    try: root = ET.fromstring(raw[raw.find("<"):])
    except Exception: return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) != 4: continue
        x1, y1, x2, y2 = map(int, b)
        out.append({"texte": (n.get("text") or "").strip(), "desc": (n.get("content-desc") or "").strip(),
                    "id": (n.get("resource-id") or "").split("/")[-1], "classe": (n.get("class") or "").split(".")[-1],
                    "paquet": n.get("package", ""), "cliquable": n.get("clickable") == "true" or n.get("long-clickable") == "true",
                    "actif": n.get("enabled") == "true", "x": (x1 + x2) // 2, "y": (y1 + y2) // 2,
                    "surface": max(0, x2 - x1) * max(0, y2 - y1)})
    return out

def libelle(n, tous):
    """Nom lisible d'un élément cliquable : son texte, sa description, ou le premier texte qu'il contient."""
    l = n["texte"] or n["desc"] or n["id"]
    if not l:  # conteneur cliquable : texte d'un enfant situé dans sa zone (approximation par proximité)
        proches = [m for m in tous if (m["texte"] or m["desc"]) and abs(m["y"] - n["y"]) < 60 and abs(m["x"] - n["x"]) < 500]
        l = (proches[0]["texte"] or proches[0]["desc"]) if proches else n["classe"]
    return re.sub(r"\s+", " ", l)[:60]

def ecran():
    pk, act = activite()
    tous = noeuds()
    a_nous = [n for n in tous if n["paquet"] == PKG]
    elems, vus = [], {}
    for n in a_nous:
        if not (n["cliquable"] and n["actif"]) or n["surface"] < 400: continue
        lib = libelle(n, a_nous)
        cle = f"{n['id']}|{lib}"
        vus[cle] = vus.get(cle, 0) + 1
        elems.append({"cle": f"{cle}#{vus[cle]}", "libelle": lib, "id": n["id"], "classe": n["classe"], "x": n["x"], "y": n["y"]})
    textes = [re.sub(r"\s+", " ", n["texte"] or n["desc"])[:80] for n in a_nous if (n["texte"] or n["desc"])]
    # Signature stable : activité + éléments cliquables, chiffres retirés (heures, durées, compteurs)
    sig_src = act + "|" + "|".join(sorted(re.sub(r"\d+", "#", e["cle"]) for e in elems))
    if not elems: sig_src += "|" + "|".join(sorted(re.sub(r"\d+", "#", t) for t in textes[:15]))
    return {"paquet": pk, "activite": act, "elements": elems, "textes": textes,
            "sig": hashlib.sha1(sig_src.encode()).hexdigest()[:10], "nous": pk == PKG or bool(a_nous)}

ECRANS, LIENS, SORTIES = {}, [], []
ordre = []

def enregistrer(e, chemin):
    if e["sig"] in ECRANS: return False
    num = len(ECRANS) + 1
    png = f"captures/e{num:02d}.png"
    with open(os.path.join(OUT, png), "wb") as f: f.write(adb("exec-out", "screencap", "-p", binary=True))
    ECRANS[e["sig"]] = {"num": num, "activite": e["activite"], "capture": png, "chemin": [c["libelle"] for c in chemin],
                        "elements": [x["libelle"] for x in e["elements"]], "textes": e["textes"][:40]}
    ordre.append(e["sig"]); print(f"écran {num:2d} {e['activite']:<28} {len(e['elements']):2d} éléments  ← {' › '.join(c['libelle'] for c in chemin) or 'lancement'}", flush=True)
    return True

def toucher(el):
    adb("shell", "input", "tap", str(el["x"]), str(el["y"])); time.sleep(1.6)

def retrouver(cle, e):
    return next((x for x in e["elements"] if x["cle"] == cle), None)

def restaurer(chemin):
    """Relance l'appli et rejoue le chemin ; renvoie l'écran atteint (ou None si le chemin ne se rejoue plus)."""
    lancer(); e = ecran()
    for c in chemin:
        el = retrouver(c["cle"], e)
        if not el: return None
        toucher(el); e = ecran()
    return e

def explorer(chemin, e):
    if not enregistrer(e, chemin) and chemin: return
    sig = e["sig"]
    for el in [x for x in e["elements"] if not DANGER.search(x["libelle"])][:MAX_PAR_ECRAN]:
        if time.time() > FIN or len(ECRANS) >= MAX_ECRANS: return
        cur = ecran()
        if cur["sig"] != sig:
            cur = restaurer(chemin)
            if not cur or cur["sig"] != sig: return
        el2 = retrouver(el["cle"], cur)
        if not el2: continue
        toucher(el2); apres = ecran()
        if not apres["nous"]:
            SORTIES.append({"depuis": ECRANS[sig]["num"], "element": el["libelle"], "vers": apres["paquet"] + "/" + apres["activite"]})
            adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2); continue
        if apres["sig"] == sig: continue
        LIENS.append({"depuis": ECRANS[sig]["num"], "element": el["libelle"], "vers": apres["sig"]})
        if apres["sig"] not in ECRANS and len(chemin) < MAX_PROFONDEUR:
            explorer(chemin + [el2], apres)
        adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.2)

lancer()
debut = ecran()
explorer([], debut)
for l in LIENS: l["vers"] = ECRANS[l["vers"]]["num"] if l["vers"] in ECRANS else None
plantages = adb("logcat", "-d", "-b", "crash")
res = {"paquet": PKG, "appareil": DEV, "minutes": MINUTES, "ecrans": [ECRANS[s] for s in ordre], "liens": LIENS,
       "sorties": SORTIES, "plantage": PKG in plantages, "fini": time.time() <= FIN}
json.dump(res, open(os.path.join(OUT, "ecrans.json"), "w"), ensure_ascii=False, indent=1)

h = ["<!doctype html><meta charset=utf-8><title>Tour " + html.escape(PKG) + "</title><style>body{font:14px system-ui;margin:20px;background:#f6f6f8}"
     ".e{display:inline-block;vertical-align:top;width:300px;margin:8px;background:#fff;border-radius:12px;padding:10px;box-shadow:0 1px 3px #0002}"
     ".e img{width:100%;border-radius:8px}.c{color:#666;font-size:12px}</style><h1>Tour de " + html.escape(PKG) +
     f"</h1><p>{len(ECRANS)} écrans, {len(LIENS)} liens, {len(SORTIES)} sorties vers d'autres applis.</p>"]
for s in ordre:
    x = ECRANS[s]
    h.append(f"<div class=e><b>{x['num']}. {html.escape(x['activite'])}</b><div class=c>{html.escape(' › '.join(x['chemin']) or 'lancement')}</div>"
             f"<img src='{x['capture']}' loading=lazy><div class=c>{html.escape(', '.join(x['elements'][:20]))}</div></div>")
open(os.path.join(OUT, "index.html"), "w").write("".join(h))
print(f"Fini : {len(ECRANS)} écrans, {len(LIENS)} liens, {len(SORTIES)} sorties, plantage={res['plantage']}")
