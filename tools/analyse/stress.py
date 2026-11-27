#!/usr/bin/env python3
"""
Stress test de l'appli Box (TV) depuis l'atelier d'émulation.

Prérequis : émulateur TV démarré, appli Box installée et protection active, port 7363 de la TV
redirigé vers BOX (127.0.0.1:7363 par défaut), téléphone de test déjà appairé (boxcli.py pair).
Variables : BOX=hôte:port, TV=numéro adb de la TV (emulator-5558), APP=appli à bloquer
(com.google.android.youtube.tv).

Usage : python3 stress.py [protocole|flood|contournement|bascule|tout]
Chaque scénario affiche OK / ÉCHEC et un résumé à la fin.
"""
import json, os, socket, ssl, subprocess, sys, threading, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import boxcli  # noqa: E402

TV = os.environ.get("TV", "emulator-5558")
APP = os.environ.get("APP", "com.google.android.youtube.tv")
BOX_PKG = "fr.paul.telecommande.box"
results = []


def adb(*args, timeout=60):
    r = subprocess.run(["adb", "-s", TV, *args], capture_output=True, text=True, timeout=timeout)
    return r.stdout.strip()


def pid():
    return adb("shell", "pidof", BOX_PKG)


def foreground():
    out = adb("shell", "dumpsys activity activities | grep -m1 -E 'mResumedActivity|topResumedActivity'")
    for tok in out.split():
        if "/" in tok:
            return tok.split("/")[0]
    return ""


def report(name, ok, detail=""):
    results.append((name, ok, detail))
    print(("OK     " if ok else "ÉCHEC  ") + name + (" — " + detail if detail else ""), flush=True)


def anon_tls():
    """Connexion TLS avec un certificat jetable (appareil NON appairé)."""
    if not os.path.exists("/tmp/intrus.pem"):
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "/tmp/intrus.key",
                        "-out", "/tmp/intrus.pem", "-days", "1", "-subj", "/CN=intrus"], capture_output=True)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    ctx.check_hostname = False
    ctx.verify_mode = ssl.CERT_NONE
    ctx.load_cert_chain("/tmp/intrus.pem", "/tmp/intrus.key")
    return ctx.wrap_socket(socket.create_connection((boxcli.HOST, boxcli.PORT), timeout=20))


def status_ok():
    try:
        return "error" not in boxcli.call("status")
    except Exception:
        return False


# ---------- 1. Protocole : messages piégés envoyés par un appareil non appairé ----------

def protocole():
    p0 = pid()
    payloads = {
        "imbriqué 20 000 niveaux": ('{"a":' + "[" * 20000 + "\n").encode(),
        "objets imbriqués": ('{"a":' + '{"b":' * 5000 + "\n").encode(),
        "ligne de 400 Ko": (b'{"cmd":"status","x":"' + b"A" * 400_000 + b'"}\n'),
        "octets invalides": bytes(range(256)) * 50 + b"\n",
        "JSON tronqué": b'{"cmd":"set_rules","rules":{"enabled":fal\n',
        "commande inconnue": b'{"id":"1","cmd":"rm -rf /"}\n',
        "set_rules sans appairage": b'{"id":"1","cmd":"set_rules","rules":{"enabled":false}}\n',
    }
    for name, data in payloads.items():
        try:
            s = anon_tls()
            s.sendall(data)
            try:
                rep = s.recv(300)
            except Exception:
                rep = b""
            s.close()
        except Exception as e:
            rep = repr(e).encode()
        alive = pid() == p0
        refused = b'"ok":true' not in rep or name == "commande inconnue"
        report(f"protocole · {name}", alive and refused and status_ok(),
               f"pid {'inchangé' if alive else 'CHANGÉ'} · réponse {rep[:70]!r}")
    # TCP brut sans TLS
    try:
        s = socket.create_connection((boxcli.HOST, boxcli.PORT), timeout=10)
        s.sendall(b"GET / HTTP/1.1\r\nHost: x\r\n\r\n")
        try:
            s.recv(100)
        except Exception:
            pass
        s.close()
    except Exception:
        pass
    report("protocole · HTTP en clair sur le port TLS", pid() == p0 and status_ok())


# ---------- 2. Saturation : connexions muettes et rafales de commandes ----------

def flood():
    p0 = pid()
    mute = []
    for _ in range(40):
        try:
            mute.append(socket.create_connection((boxcli.HOST, boxcli.PORT), timeout=5))
        except Exception:
            break
    t = time.time()
    ok = status_ok()
    report("saturation · 40 connexions muettes puis le vrai téléphone", ok, f"{len(mute)} ouvertes · réponse en {time.time() - t:.1f} s")
    for s in mute:
        s.close()

    t = time.time()
    n_ok = sum(1 for _ in range(60) if status_ok())
    report("saturation · 60 « status » d'affilée", n_ok == 60, f"{n_ok}/60 en {time.time() - t:.0f} s")

    oks = []
    def worker():
        oks.append(status_ok())
    th = [threading.Thread(target=worker) for _ in range(5)]
    t = time.time()
    [x.start() for x in th]
    [x.join(120) for x in th]
    report("saturation · 5 commandes simultanées du téléphone", all(oks) and len(oks) == 5, f"{sum(oks)}/5 en {time.time() - t:.0f} s")
    report("saturation · appli Box toujours la même", pid() == p0)


# ---------- 3. Contournement : plantage puis appli bloquée rouverte tout de suite ----------

def block_app():
    rules = boxcli.call("get_rules")["rules"]
    rules["enabled"] = True
    rules.setdefault("apps", {})[APP] = {"limitMin": 0, "days": 127, "from": 0, "to": 0, "blocked": True}
    rules["version"] = int(time.time() * 1000)
    boxcli.call("set_rules", rules=rules)


def wait_blocked(limit_s):
    t = time.time()
    while time.time() - t < limit_s:
        if foreground() != APP:
            return time.time() - t
        time.sleep(2)
    return None


def contournement():
    block_app()
    adb("shell", "monkey", "-p", APP, "-c", "android.intent.category.LEANBACK_LAUNCHER", "1")
    d = wait_blocked(60)
    report("contournement · appli bloquée ouverte normalement", d is not None, f"renvoyée à l'accueil en {d:.0f} s" if d is not None else "toujours ouverte après 60 s")
    for i in range(3):
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(3)
        p0 = pid()
        adb("shell", f"am crash {BOX_PKG}; monkey -p {APP} -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1")
        d = wait_blocked(150)
        report(f"contournement · plantage n°{i + 1} puis appli rouverte aussitôt", d is not None,
               (f"bloquée en {d:.0f} s" if d is not None else "TOUJOURS OUVERTE après 150 s") + f" · appli Box relancée ({p0} → {pid()})")
        time.sleep(20)
    t = time.time()
    while time.time() - t < 120 and not status_ok():
        time.sleep(5)
    report("contournement · le téléphone retrouve la TV après les plantages", status_ok())


# ---------- 4. Bascule rapide entre applis ----------

def bascule():
    block_app()
    leaks = 0
    for i in range(10):
        adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(1)
        adb("shell", "am", "start", "-a", "android.settings.SETTINGS")
        time.sleep(1)
        adb("shell", "monkey", "-p", APP, "-c", "android.intent.category.LEANBACK_LAUNCHER", "1")
        if wait_blocked(40) is None:
            leaks += 1
    report("bascule · 10 allers-retours accueil / Paramètres / appli bloquée", leaks == 0, f"{leaks} fuite(s)")


def main():
    what = sys.argv[1] if len(sys.argv) > 1 else "tout"
    steps = {"protocole": protocole, "flood": flood, "contournement": contournement, "bascule": bascule}
    for name, fn in steps.items():
        if what in (name, "tout"):
            try:
                fn()
            except Exception as e:
                report(f"{name} · erreur du banc de test", False, repr(e))
    ok = sum(1 for r in results if r[1])
    print(f"\nRésumé : {ok}/{len(results)} OK")
    print(json.dumps([{"test": n, "ok": o, "detail": d} for n, o, d in results], ensure_ascii=False))


if __name__ == "__main__":
    main()
