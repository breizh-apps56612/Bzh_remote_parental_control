#!/usr/bin/env python3
"""Prépare le site de téléchargement (telecommande-box.netlify.app) : APK + version.json (empreintes SHA-256)."""
import hashlib, json, re, shutil, subprocess, sys, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
site = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else root / "site-out")
aapt = "/opt/android-sdk/build-tools/34.0.0/aapt"
notes = sys.argv[2] if len(sys.argv) > 2 else ""
site.mkdir(parents=True, exist_ok=True)
for f in (root / "site").iterdir():
    shutil.copy(f, site / f.name)
def info(apk):
    out = subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True)
    m = re.search(r"versionCode='(\d+)' versionName='([^']*)'", out)
    return int(m.group(1)), m.group(2)
base = "https://telecommande-box.netlify.app"
data = {}
for ch, src, name in [("box", root / "box/build/outputs/apk/release/box-release.apk", "TelecommandeBox.apk"),
                      ("phone", root / "app/build/outputs/apk/release/app-release.apk", "Telecommande.apk")]:
    shutil.copy(src, site / name)
    code, vname = info(src)
    data[ch] = {"versionCode": code, "versionName": vname, "url": f"{base}/{name}",
                "sha256": hashlib.sha256((site / name).read_bytes()).hexdigest(), "notes": notes}
(site / "version.json").write_text(json.dumps(data, indent=2, ensure_ascii=False))
print(json.dumps(data, indent=2, ensure_ascii=False))
