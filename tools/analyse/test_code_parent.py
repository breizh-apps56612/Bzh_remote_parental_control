#!/usr/bin/env python3
"""Banc : code parent refusé → la TV doit l'afficher (Box 1.4.58).

Prérequis : téléphone de test déjà appairé (boxcli.py pair), TV = emulator-5556, OUT = dossier des résultats.
Affiche une ligne par vérification : « OK|nom|détail », « KO|nom|détail » ou « INFO|nom|détail ».
Code parent de test : 1234 (posé pour le test, puis la protection des réglages est remise comme avant).
"""
import hashlib, json, os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import boxcli  # noqa: E402  (outil du dépôt : téléphone de test)

TV = os.environ.get("TV", "emulator-5556")
OUT = os.environ.get("OUT", ".")
boxcli.ensure_id()

def adb(*a, t=30):
    try: return subprocess.run(["adb", "-s", TV, *a], capture_output=True, timeout=t).stdout
    except subprocess.TimeoutExpired: return b""

def touches(*k):
    for x in k: adb("shell", "input", "keyevent", "KEYCODE_" + x); time.sleep(0.4)

def capture(nom):
    with open(os.path.join(OUT, "captures", nom + ".png"), "wb") as f: f.write(adb("exec-out", "screencap", "-p"))
    return os.path.join(OUT, "captures", nom + ".png")

def textes_ecran(png=None):
    """Textes visibles : arbre d'accessibilité de la Box (toutes fenêtres) + lecture de la capture (OCR) en secours."""
    t = []
    try:
        r = boxcli.call("dev_screen", max=1500)
        for w in r.get("windows", []):
            for n in w.get("nodes", []): t += [n.get("text", ""), n.get("desc", "")]
    except Exception as e: t.append("(dev_screen : %s)" % e)
    if png:
        try: t.append(subprocess.run(["tesseract", png, "-", "-l", "eng"], capture_output=True, timeout=60).stdout.decode(errors="replace"))
        except Exception: pass
    return " ".join(x for x in t if x)

def ligne(etat, nom, detail=""): print("%s|%s|%s" % (etat, nom, str(detail).replace("|", "/").replace("\n", " ")[:160]), flush=True)

def pad_ouvert():
    try: return boxcli.call("pin_pending")
    except Exception as e: return {"erreur": str(e)}

regles = boxcli.call("get_rules").get("rules") or {}
avant = (regles.get("protectSettings", True), regles.get("enabled", False))
regles["pinHash"] = hashlib.sha256(b"telecommande-pin:1234").hexdigest()
regles["protectSettings"] = True
regles["enabled"] = True   # contrôle parental actif : sinon la TV ne protège rien
r = boxcli.call("set_rules", rules=regles)
if not r.get("ok"): ligne("KO", "Code parent de test posé sur la TV", r); sys.exit(0)

try:
    # Les réglages de la TV sont protégés : leur ouverture fait apparaître le pavé du code parent
    adb("shell", "am", "start", "-a", "android.settings.SETTINGS"); time.sleep(5)
    p = pad_ouvert()
    if not p.get("pin"):
        adb("shell", "am", "start", "-n", "com.android.tv.settings/.MainSettings"); time.sleep(5); p = pad_ouvert()
    if not p.get("pin"): ligne("KO", "Pavé du code parent ouvert (réglages protégés)", p); sys.exit(0)
    ligne("OK", "Pavé du code parent ouvert (réglages protégés)", p.get("title", ""))
    pid = p["pin"]

    # 1) Code faux tapé avec les chiffres de la télécommande (focus sur la touche « 1 » du pavé), puis OK du pavé
    touches("9", "9", "9", "9"); time.sleep(0.5)
    t = textes_ecran()
    ligne("OK" if "●●●●" in t else "KO", "Chiffres de la télécommande pris en compte", "" if "●●●●" in t else t[:120])
    touches("DPAD_DOWN", "DPAD_DOWN", "DPAD_DOWN", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_CENTER")
    time.sleep(0.8)
    t = textes_ecran(capture("05-tv-code-faux"))
    ok = ("Code refusé" in t or "Code refuse" in t) and "incorrect" in t
    ligne("OK" if ok else "KO", "Code faux sur la TV : « Code refusé » affiché", "bandeau + « Code incorrect »" if ok else t[:120])

    # 2) Code faux tapé sur le téléphone du parent pour cette demande
    r = boxcli.call("pin_failed", id=pid); time.sleep(1)
    t = textes_ecran(capture("06-tv-code-faux-telephone"))
    ok = r.get("shown") is True and ("téléphone du parent" in t or "refusé sur le" in t or "refuse sur le" in t)
    ligne("OK" if ok else "KO", "Code faux sur le téléphone : refus affiché sur la TV", r)

    # 3) Validation du téléphone refusée (demande inconnue) alors que le pavé est à l'écran
    r = boxcli.call("pin_approve", id="demande-inconnue", title="Autre demande"); time.sleep(1)
    t = textes_ecran(capture("07-tv-validation-refusee"))
    ok = r.get("ok") is False and ("Validation du téléphone refusée" in t or "Validation du" in t)
    ligne("OK" if ok else "KO", "Validation du téléphone refusée : affiché sur la TV", r)

    # 4) Le bon code, tapé sur le pavé à l'écran (focus sur OK) : 1 2 3 puis 4, puis OK
    touches("DPAD_UP", "DPAD_UP", "DPAD_UP", "DPAD_LEFT", "DPAD_LEFT", "DPAD_CENTER",   # 1
            "DPAD_RIGHT", "DPAD_CENTER", "DPAD_RIGHT", "DPAD_CENTER",                   # 2, 3
            "DPAD_DOWN", "DPAD_LEFT", "DPAD_LEFT", "DPAD_CENTER",                       # 4
            "DPAD_DOWN", "DPAD_DOWN", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_CENTER")       # OK
    time.sleep(1.5)
    p = pad_ouvert()
    ligne("OK" if not p.get("pin") else "KO", "Bon code après refus : pavé fermé", "" if not p.get("pin") else p)

    # 5) Journal : le refus est noté, jamais les chiffres
    j = json.dumps(boxcli.call("diag"), ensure_ascii=False)
    ok = "Code parent faux tapé sur la TV" in j and "9999" not in j
    ligne("OK" if ok else "KO", "Journal TV : refus noté sans les chiffres")
finally:
    regles["protectSettings"], regles["enabled"] = avant
    boxcli.call("set_rules", rules=regles)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
