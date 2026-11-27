#!/usr/bin/env bash
# Banc d'essai sur émulateurs (téléphone + TV Android) — lancé par GitHub Actions (.github/workflows/emulateurs.yml).
# Démarre les deux émulateurs avec accélération KVM, installe les APK de debug compilés depuis ce commit,
# puis vérifie le fonctionnement et la sécurité de l'appli Box (appairage, refus des inconnus, résistance aux abus).
#
# Entrées (variables d'environnement) :
#   APK_PHONE, APK_BOX   chemins des APK à tester (obligatoires)
#   API_PHONE, API_TV    niveaux d'API des émulateurs (défaut 30)
#   OUT                  dossier des résultats (défaut ./emu-resultats) : resume.md, captures/, journaux/
# Code de sortie : 0 si tout est vert, 1 sinon.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$PWD/emu-resultats}
API_PHONE=${API_PHONE:-30}; API_TV=${API_TV:-30}
mkdir -p "$OUT/captures" "$OUT/journaux"
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
PH=emulator-5554; TV=emulator-5556
PKG_PH=fr.paul.telecommande; PKG_TV=fr.paul.telecommande.box
RESUME=$OUT/resume.md; ECHECS=0
export ID=${RUNNER_TEMP:-/tmp}/id-test BOX=127.0.0.1:7363   # certificat du téléphone de test : créé pour ce run, hors des résultats publiés

ok()   { echo "| ✅ | $1 | ${2:-} |" >> "$RESUME"; echo "OK   $1 ${2:-}"; }
ko()   { echo "| ❌ | $1 | ${2:-} |" >> "$RESUME"; echo "ECHEC $1 ${2:-}"; ECHECS=$((ECHECS+1)); }
info() { echo "| ℹ️ | $1 | ${2:-} |" >> "$RESUME"; echo "INFO $1 ${2:-}"; }
cap()  { timeout 30 adb -s "$1" exec-out screencap -p > "$OUT/captures/$2.png" 2>/dev/null || true; }
cli()  { timeout 90 python3 -I "$HERE/boxcli.py" "$@" 2>&1 | tail -c 600 | tr '\n|' '  '; }
vivant() { [ -n "$(timeout 20 adb -s "$1" shell pidof "$2" 2>/dev/null | tr -d '\r')" ]; }

{ echo "# Banc d'essai émulateurs"; echo
  echo "Commit \`${GITHUB_SHA:-local}\` — téléphone API $API_PHONE, TV API $API_TV — $(date -u '+%d/%m/%Y %H:%M UTC')"; echo
  echo "| | Vérification | Détail |"; echo "|---|---|---|"; } > "$RESUME"

# ---------- 1. Émulateurs ----------
yes | sdkmanager --licenses >/dev/null 2>&1 || true
sdkmanager --install "emulator" "platform-tools" \
  "system-images;android-$API_PHONE;default;x86_64" "system-images;android-$API_TV;android-tv;x86" >/dev/null || { ko "Téléchargement des images Android"; exit 1; }
echo no | avdmanager create avd -f -n phone -k "system-images;android-$API_PHONE;default;x86_64" -d pixel >/dev/null
echo no | avdmanager create avd -f -n tv -k "system-images;android-$API_TV;android-tv;x86" -d tv_1080p >/dev/null
OPTS=(-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on -memory 2048 -netfast)
nohup emulator -avd tv -port 5556 "${OPTS[@]}" > "$OUT/journaux/emulateur-tv.log" 2>&1 &
nohup emulator -avd phone -port 5554 "${OPTS[@]}" > "$OUT/journaux/emulateur-telephone.log" 2>&1 &
for i in $(seq 1 60); do
  a=$(timeout 10 adb -s $TV shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  b=$(timeout 10 adb -s $PH shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$a" = 1 ] && [ "$b" = 1 ] && break; sleep 10
done
if [ "$a" = 1 ] && [ "$b" = 1 ]; then ok "Démarrage des deux émulateurs" "$((i*10)) s"; else ko "Démarrage des émulateurs" "TV=$a téléphone=$b"; exit 1; fi
for d in $PH $TV; do for s in window_animation_scale transition_animation_scale animator_duration_scale; do adb -s $d shell settings put global $s 0; done; done

# ---------- 2. Installation ----------
adb -s $PH install -r -g "$APK_PHONE" >/dev/null 2>&1 && ok "Installation appli téléphone" || ko "Installation appli téléphone"
adb -s $TV install -r -g "$APK_BOX" >/dev/null 2>&1 && ok "Installation appli Box (TV)" || ko "Installation appli Box (TV)"
# Protection activée comme le ferait le parent dans les Paramètres de la TV
adb -s $TV shell settings put secure enabled_accessibility_services $PKG_TV/.GuardService
adb -s $TV shell settings put secure accessibility_enabled 1
adb -s $TV shell appops set $PKG_TV GET_USAGE_STATS allow
adb -s $TV logcat -c; adb -s $PH logcat -c
adb -s $TV shell monkey -p $PKG_TV -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
adb -s $PH shell monkey -p $PKG_PH -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8; cap $TV 01-tv-demarrage; cap $PH 01-telephone-accueil
vivant $TV $PKG_TV && ok "Appli Box lancée" || ko "Appli Box lancée"
vivant $PH $PKG_PH && ok "Appli téléphone lancée" || ko "Appli téléphone lancée"

# ---------- 3. Sécurité du serveur de la Box (port 7363) ----------
adb -s $TV forward tcp:7363 tcp:7363 >/dev/null   # 127.0.0.1:7363 ici, et 10.0.2.2:7363 depuis le téléphone
for i in $(seq 1 20); do timeout 5 bash -c '</dev/tcp/127.0.0.1/7363' 2>/dev/null && break; sleep 3; done
r=$(cli nocert); [[ $r == *"refusé"* ]] && ok "Connexion sans certificat refusée" || ko "Connexion sans certificat refusée" "$r"
r=$(cli status); [[ $r == *'"ok": false'* || $r == *"non appairé"* ]] && ok "Téléphone inconnu refusé" "${r:0:120}" || ko "Téléphone inconnu refusé" "$r"

r=$(cli pair_start); info "Demande d'appairage" "${r:0:120}"
sleep 4; cap $TV 02-tv-code-appairage
code=""
if command -v tesseract >/dev/null; then
  code=$(tesseract "$OUT/captures/02-tv-code-appairage.png" - --psm 11 2>/dev/null | grep -oE '[0-9]{3} *[0-9]{3}' | head -1 | tr -d ' ')
fi
if [ ${#code} = 6 ]; then
  faux=$(printf '%06d' $(( (10#$code + 1) % 1000000 )))
  r=$(cli pair "$faux"); [[ $r == *'"ok": false'* || $r != *'"ok": true'* ]] && ok "Mauvais code d'appairage refusé" || ko "Mauvais code d'appairage refusé" "$r"
  r=$(cli pair "$code")
  if [[ $r == *'"ok": true'* && $r == *'"box_prouve_le_code": true'* ]]; then ok "Appairage SPAKE2 avec le code affiché"; else ko "Appairage SPAKE2 avec le code affiché" "$r"; fi
  r=$(cli status); [[ $r == *'"ok": true'* ]] && ok "Téléphone appairé accepté" || ko "Téléphone appairé accepté" "${r:0:160}"
else
  info "Appairage non testé" "code illisible sur la capture (lecture automatique), voir 02-tv-code-appairage.png"
fi

r=$(cli flood 12); [[ $r == *"BLOQUÉ"* ]] && ko "Résiste à 12 connexions muettes" "$r" || ok "Résiste à 12 connexions muettes" "${r:0:120}"
cli profond 5000 >/dev/null; sleep 3
vivant $TV $PKG_TV && ok "Message JSON très imbriqué : Box toujours en marche" || ko "Message JSON très imbriqué : la Box a planté"

# ---------- 4. Téléphone : appui au hasard (graine fixe, reproductible) ----------
adb -s $PH shell monkey -p $PKG_PH -s 4242 --throttle 120 --pct-syskeys 0 --pct-appswitch 0 -v 600 > "$OUT/journaux/monkey-telephone.txt" 2>&1
if grep -qE "CRASH|ANR in|Monkey aborted" "$OUT/journaux/monkey-telephone.txt"; then ko "600 appuis au hasard sur le téléphone" "voir journaux/monkey-telephone.txt"
else ok "600 appuis au hasard sur le téléphone" "graine 4242"; fi
cap $PH 03-telephone-apres-monkey

# ---------- 5. TV : navigation à la télécommande ----------
for k in DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_CENTER BACK HOME; do adb -s $TV shell input keyevent KEYCODE_$k; sleep 1; done
cap $TV 04-tv-apres-navigation
vivant $TV $PKG_TV && ok "Navigation télécommande sur la TV" || ko "Navigation télécommande sur la TV" "appli Box arrêtée"

# ---------- 6. Plantages ----------
for d in $PH $TV; do
  n=$([ $d = $PH ] && echo telephone || echo tv)
  timeout 30 adb -s $d logcat -d -b crash > "$OUT/journaux/plantages-$n.txt" 2>/dev/null
  # Journal limité aux applis testées (les émulateurs ne contiennent aucune donnée personnelle)
  timeout 30 adb -s $d logcat -d -v time 2>/dev/null | grep -iE "telecommande|AndroidRuntime" | tail -n 2000 > "$OUT/journaux/logcat-$n.txt"
  if grep -qE "FATAL EXCEPTION.*|Process: fr\.paul" "$OUT/journaux/plantages-$n.txt"; then ko "Aucun plantage ($n)" "voir journaux/plantages-$n.txt"; else ok "Aucun plantage ($n)"; fi
done

rm -rf "$ID"   # la clé du téléphone de test ne quitte jamais le runner
echo >> "$RESUME"; [ $ECHECS = 0 ] && echo "**Tout est vert.**" >> "$RESUME" || echo "**$ECHECS vérification(s) en échec.**" >> "$RESUME"
adb -s $PH emu kill >/dev/null 2>&1; adb -s $TV emu kill >/dev/null 2>&1
[ $ECHECS = 0 ]
