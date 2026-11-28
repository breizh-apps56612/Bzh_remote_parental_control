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
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-${RUNNER_TEMP:-/tmp}/avd}; mkdir -p "$ANDROID_AVD_HOME"
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
# EMU_DEJA_LA=1 : émulateurs déjà démarrés (session en direct) ; sinon on les démarre ici
source "$HERE/emu-lib.sh"
if [ "${EMU_DEJA_LA:-0}" = 1 ]; then ok "Émulateurs déjà démarrés (session en direct)"
elif emu_demarrer "$OUT/journaux"; then ok "Démarrage des deux émulateurs" "$EMU_SECONDES s"
else ko "Démarrage des émulateurs"; exit 1; fi

# ---------- 2. Installation ----------
timeout 180 adb -s $PH install -r -g "$APK_PHONE" >/dev/null 2>&1 && ok "Installation appli téléphone" || ko "Installation appli téléphone"
timeout 180 adb -s $TV install -r -g "$APK_BOX" >/dev/null 2>&1 && ok "Installation appli Box (TV)" || ko "Installation appli Box (TV)"
# Protection activée comme le ferait le parent dans les Paramètres de la TV
timeout 20 adb -s $TV shell settings put secure enabled_accessibility_services $PKG_TV/.GuardService
timeout 20 adb -s $TV shell settings put secure accessibility_enabled 1
timeout 20 adb -s $TV shell appops set $PKG_TV GET_USAGE_STATS allow
timeout 20 adb -s $TV logcat -c; timeout 20 adb -s $PH logcat -c
timeout 60 adb -s $TV shell monkey -p $PKG_TV -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
timeout 60 adb -s $PH shell monkey -p $PKG_PH -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
[ "${VIDEO:-1}" = 1 ] && video_debut "$OUT/videos"   # vidéo de tout le passage, téléphone et TV
sleep 8; cap $TV 01-tv-demarrage; cap $PH 01-telephone-accueil
vivant $TV $PKG_TV && ok "Appli Box lancée" || ko "Appli Box lancée"
vivant $PH $PKG_PH && ok "Appli téléphone lancée" || ko "Appli téléphone lancée"

# ---------- 2b. Menus rangés (Téléphone 2.6.6) : onglet Réglages sans informations mélangées aux actions ----------
ecran() { timeout 30 adb -s $PH shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb -s $PH exec-out cat /sdcard/ui.xml 2>/dev/null; }
toucher() { # toucher l'élément dont le texte est exactement $1 (dans le dernier écran lu, $2)
  local b; b=$(printf '%s' "$2" | python3 -c 'import sys,re,html
t=sys.argv[1]; x=sys.stdin.read()
for m in re.finditer(r"<node [^>]*>", x):
    n=m.group(0); tt=re.search(r" text=\"([^\"]*)\"", n); bb=re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
    if tt and bb and html.unescape(tt.group(1))==t:
        a=list(map(int,bb.groups())); print((a[0]+a[2])//2,(a[1]+a[3])//2); break' "$1")
  [ -n "$b" ] && timeout 20 adb -s $PH shell input tap $b && sleep 2
}
x=$(ecran); toucher "Passer" "$x"; x=$(ecran)
if toucher "Réglages" "$x"; then
  x=$(ecran); cap $PH 02-telephone-reglages
  if [[ $x == *'MES ÉCRANS'* && $x == *"L&apos;APPLI"* || $x == *'MES ÉCRANS'* && $x == *"L'APPLI"* ]]; then
    if [[ $x == *'text="Vos données restent chez vous"'* ]]; then ko "Réglages : actions et informations séparées" "« Vos données » encore en ligne de menu"
    else ok "Réglages : actions et informations séparées" "Mes écrans · Enfants · L'appli, note « Vos données » en bas"; fi
  else ko "Réglages : actions et informations séparées" "parties attendues absentes"; fi
  timeout 20 adb -s $PH shell input keyevent KEYCODE_BACK
else info "Réglages : onglet non atteint par le banc (vérifié par le test MenusTest du build)"; fi

# ---------- 2c. Thème sombre du téléphone (Téléphone 2.6.7 : la télécommande suit le thème) ----------
# Les couleurs de l'écran Télécommande elles-mêmes sont mesurées par RemoteTest (build) en clair et en sombre.
if timeout 20 adb -s $PH shell cmd uimode night yes >/dev/null 2>&1; then
  timeout 20 adb -s $PH shell am force-stop $PKG_PH
  timeout 60 adb -s $PH shell monkey -p $PKG_PH -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6; cap $PH 02c-telephone-theme-sombre
  vivant $PH $PKG_PH && ok "Thème sombre du téléphone : l'appli s'ouvre" "capture 02c" || ko "Thème sombre du téléphone : l'appli s'ouvre" "appli arrêtée"
  timeout 20 adb -s $PH shell cmd uimode night no >/dev/null 2>&1
  timeout 20 adb -s $PH shell am force-stop $PKG_PH
  timeout 60 adb -s $PH shell monkey -p $PKG_PH -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 4
else info "Thème sombre : commande uimode indisponible sur cet émulateur"; fi

# ---------- 3. Sécurité du serveur de la Box (port 7363) ----------
timeout 20 adb -s $TV forward tcp:7363 tcp:7363 >/dev/null   # 127.0.0.1:7363 ici : vu par la TV comme venant de 127.0.0.1
timeout 20 adb -s $TV emu redir add tcp:7364:7363 >/dev/null  # 127.0.0.1:7364 ici : vu par la TV comme venant de 10.0.2.2 (un autre appareil)
for i in $(seq 1 20); do timeout 5 bash -c '</dev/tcp/127.0.0.1/7363' 2>/dev/null && break; sleep 3; done
r=$(cli nocert); [[ $r == *"refusé"* ]] && ok "Connexion sans certificat refusée" || ko "Connexion sans certificat refusée" "$r"
r=$(cli status); [[ $r == *'"ok": false'* || $r == *"non appairé"* ]] && ok "Téléphone inconnu refusé" "${r:0:120}" || ko "Téléphone inconnu refusé" "$r"

r=$(cli pair_start); info "Demande d'appairage" "${r:0:120}"
# Code lu sur l'écran TV (lecture automatique, parfois fausse ou illisible) : jusqu'à 3 codes affichés, 3 lectures
appaire=0; faux_fait=0; code=""
for essai in 1 2 3; do
  [ $essai = 1 ] || { sleep 5; cli pair_start >/dev/null; }
  nom=02-tv-code-appairage; [ $essai = 1 ] || nom=$nom-$essai
  sleep 4; cap $TV $nom
  code=""; command -v tesseract >/dev/null && code=$(tesseract "$OUT/captures/$nom.png" - --psm $([ $essai = 2 ] && echo 6 || echo 11) 2>/dev/null | grep -oE '[0-9]{3} *[0-9]{3}' | head -1 | tr -d ' ')
  if [ ${#code} != 6 ]; then info "Code illisible, nouvel essai" "essai $essai"; continue; fi
  if [ $faux_fait = 0 ]; then
    faux=$(printf '%06d' $(( (10#$code + 1) % 1000000 ))); faux_fait=1
    r=$(cli pair "$faux"); [[ $r == *'"ok": false'* || $r != *'"ok": true'* ]] && ok "Mauvais code d'appairage refusé" || ko "Mauvais code d'appairage refusé" "$r"
  fi
  r=$(cli pair "$code")
  [[ $r == *'"ok": true'* ]] && { appaire=1; break; }
  info "Code mal lu, nouvel essai" "essai $essai (lu : $code)"
done
if [ $appaire = 1 ]; then
  if [[ $r == *'"box_prouve_le_code": true'* ]]; then ok "Appairage SPAKE2 avec le code affiché"; else ko "Appairage SPAKE2 avec le code affiché" "$r"; fi
  r=$(cli status); [[ $r == *'"ok": true'* ]] && ok "Téléphone appairé accepté" || ko "Téléphone appairé accepté" "${r:0:160}"
  # Activité : la TV envoie le détail des séances [appli, début, fin] (diagrammes du téléphone, Box 1.4.56)
  maint=$(( $(date +%s) * 1000 ))
  r=$(timeout 90 python3 -I "$HERE/boxcli.py" usage "{\"from\":$((maint - 7*86400000)),\"to\":$maint}" 2>&1 | python3 -I -c '
import json, sys
try:
    d = json.loads(sys.stdin.read()); s = d.get("sessions")
    ok = isinstance(s, list) and all(isinstance(x, list) and len(x) == 3 and x[1] < x[2] for x in s)
    print(("OUI " if ok else "NON ") + str(len(s) if isinstance(s, list) else "absent"))
except Exception as e: print("NON " + str(e)[:80])')
  [[ $r == OUI* ]] && ok "Activité : détail des séances envoyé par la TV" "${r#OUI } séance(s)" || ko "Activité : détail des séances envoyé par la TV" "$r"
  # Code parent refusé (TV, téléphone, validation) : toujours affiché sur la TV (Box 1.4.58)
  sortie=$(TV=$TV OUT="$OUT" timeout 240 python3 -I "$HERE/test_code_parent.py" 2>&1); echo "$sortie" > "$OUT/journaux/test-code-parent.txt"
  while IFS='|' read -r etat nom detail; do
    case "$etat" in OK) ok "$nom" "$detail" ;; KO) ko "$nom" "$detail" ;; INFO) info "$nom" "$detail" ;; esac
  done <<< "$sortie"
  grep -qE '^(OK|KO)\|' <<< "$sortie" || ko "Tests du code parent" "le script n'a rien rendu : $(tail -c 150 <<< "$sortie" | tr '\n' ' ')"

  # Pastille de profil sur l'accueil de toutes les box + changement de profil sans l'appli (Box 1.4.58)
  touche() { for k in "$@"; do timeout 20 adb -s $TV shell input keyevent KEYCODE_$k; sleep 0.8; done; }
  # Vraie télécommande : appuis envoyés par la console de l'émulateur (clavier virtuel). Les touches « adb input »
  # sont simulées et Android ne les montre pas au service de la Box, contrairement à une vraie télécommande.
  telecommande() { local c n; for k in "$@"; do case $k in DPAD_UP) c=KEY_UP; n=103;; DPAD_DOWN) c=KEY_DOWN; n=108;; DPAD_LEFT) c=KEY_LEFT; n=105;; DPAD_RIGHT) c=KEY_RIGHT; n=106;; *) c=KEY_ENTER; n=28;; esac
    if [ -n "${CLAVIER:-}" ]; then   # appui brut sur le clavier virtuel de l'émulateur (droits root)
      timeout 20 adb -s $TV shell "sendevent $CLAVIER 1 $n 1; sendevent $CLAVIER 0 0 0; sendevent $CLAVIER 1 $n 0; sendevent $CLAVIER 0 0 0" >/dev/null 2>&1
    else
      timeout 20 adb -s $TV emu event send EV_KEY:$c:1 EV_SYN:0:0 >> "$OUT/journaux/console-touches.txt" 2>&1; sleep 0.1
      timeout 20 adb -s $TV emu event send EV_KEY:$c:0 EV_SYN:0:0 >> "$OUT/journaux/console-touches.txt" 2>&1
    fi; sleep 0.8; done; }
  # Clavier virtuel de l'émulateur TV (pour les appuis bruts) : adb root, puis le périphérique qui a les flèches
  timeout 30 adb -s $TV root >/dev/null 2>&1; sleep 3; timeout 30 adb -s $TV wait-for-device
  timeout 20 adb -s $TV shell getevent -pl 2>/dev/null | tr -d '\r' > "$OUT/journaux/tv-peripheriques.txt"
  timeout 20 adb -s $TV shell id 2>/dev/null | tr -d '\r' >> "$OUT/journaux/tv-peripheriques.txt"
  # Le clavier de l'émulateur (« qwerty2 ») porte les flèches de la télécommande
  CLAVIER=$(awk '/^add device/{d=$4} /name:.*(qwerty|[Kk]eyboard)/{print d; exit}' "$OUT/journaux/tv-peripheriques.txt")
  info "Télécommande du banc" "${CLAVIER:+appuis bruts sur $CLAVIER}${CLAVIER:-console de l émulateur}"
  pastille() { timeout 90 python3 -I "$HERE/boxcli.py" status 2>&1 | python3 -I -c 'import json,sys
try:
    d=json.loads(sys.stdin.read()); b=d.get("badge") or {}; p=d.get("profile") or {}
    print(" ".join(k for k in ("visible","selected","home","who") if b.get(k)) + " | profil=" + str(p.get("name","aucun")) + " | accueil=" + str(b.get("homeApp","")))
except Exception as e: print("erreur " + str(e)[:80])'; }
  cli set_rules '{"rules":{"enabled":true,"protectSettings":false,"stillThereMin":0,"version":9001,"profiles":[{"id":"leo","name":"Léo","emoji":"🦖"},{"id":"zoe","name":"Zoé","emoji":"🦄"}]}}' >/dev/null
  sleep 4; cap $TV 05-tv-qui-regarde; touche DPAD_CENTER; sleep 3
  touche HOME; sleep 5; b=$(pastille); cap $TV 06-tv-pastille-accueil
  [[ $b == *visible* && $b == *home* && $b != *"profil=aucun"* ]] && ok "Pastille de profil sur l'accueil de la box" "${b:0:140}" || ko "Pastille de profil sur l'accueil de la box" "${b:0:160}"
  telecommande DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_UP DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT DPAD_RIGHT
  sleep 1; b=$(pastille); cap $TV 07-tv-pastille-selection
  # Relevé : ce que la Box a vu des flèches (journal Box) et l'arbre de l'écran TV (émulateur, aucune donnée personnelle)
  timeout 90 python3 -I "$HERE/boxcli.py" box_log '{"max":20000}' 2>/dev/null | python3 -I -c 'import json,sys
try: print("\n".join(l for l in json.loads(sys.stdin.read()).get("log","").splitlines() if any(m in l for m in ("PASTILLE", "PROFIL", "TOUCHE", "SERVICE"))))
except Exception as e: print("journal illisible", e)' > "$OUT/journaux/pastille.txt"
  timeout 30 adb -s $TV shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; timeout 20 adb -s $TV exec-out cat /sdcard/ui.xml > "$OUT/journaux/ecran-tv-accueil.xml" 2>/dev/null
  info "Relevé pastille" "$(grep -E "PASTILLE|TOUCHE" "$OUT/journaux/pastille.txt" | tail -3 | cut -c1-200 | tr '\n|' '  ')"
  [[ $b == *selected* ]] && ok "Pastille atteinte à la télécommande (haut puis droite)" "${b:0:140}" || ko "Pastille atteinte à la télécommande (haut puis droite)" "${b:0:160}"
  avant=${b#*profil=}; avant=${avant%% |*}
  telecommande DPAD_CENTER; sleep 2; b=$(pastille); cap $TV 08-tv-changer-profil
  [[ $b == *who* ]] && ok "OK sur la pastille : « Qui regarde ? » sans passer par l'appli" "${b:0:140}" || ko "OK sur la pastille : « Qui regarde ? » sans passer par l'appli" "${b:0:160}"
  telecommande DPAD_RIGHT DPAD_CENTER; sleep 3; b=$(pastille)
  apres=${b#*profil=}; apres=${apres%% |*}
  [[ $b != *who* && $apres != aucun && $apres != "$avant" ]] && ok "Profil changé depuis la TV" "$avant → $apres" "${b:0:140}" || ko "Profil changé depuis la TV" "${b:0:160}"
  cli set_rules '{"rules":{"enabled":false,"version":9002}}' >/dev/null; sleep 2; touche HOME
else
  ko "Appairage SPAKE2 avec le code affiché" "code illisible ou faux après 3 essais (voir captures 02-tv-code-appairage*.png) : tests appairés non faits"
fi

# Un autre appareil ouvre 12 connexions muettes : le téléphone du parent doit toujours passer
r=$(FLOOD=127.0.0.1:7364 cli flood 12); [[ $r == *"BLOQUÉ"* || $r != *'"ok"'* ]] && ko "Parent servi malgré 12 connexions muettes d'un autre appareil" "$r" || ok "Parent servi malgré 12 connexions muettes d'un autre appareil"
cli profond 5000 >/dev/null; sleep 3
vivant $TV $PKG_TV && ok "Message JSON très imbriqué : Box toujours en marche" || ko "Message JSON très imbriqué : la Box a planté"

# ---------- 4. Téléphone : appui au hasard (graine fixe, reproductible) ----------
timeout 300 adb -s $PH shell monkey -p $PKG_PH -s 4242 --throttle 120 --pct-syskeys 0 --pct-appswitch 0 -v 600 > "$OUT/journaux/monkey-telephone.txt" 2>&1
if grep -qE "CRASH|ANR in|Monkey aborted" "$OUT/journaux/monkey-telephone.txt"; then ko "600 appuis au hasard sur le téléphone" "voir journaux/monkey-telephone.txt"
else ok "600 appuis au hasard sur le téléphone" "graine 4242"; fi
cap $PH 03-telephone-apres-monkey

# ---------- 5. TV : navigation à la télécommande ----------
for k in DPAD_DOWN DPAD_DOWN DPAD_RIGHT DPAD_CENTER BACK HOME; do timeout 20 adb -s $TV shell input keyevent KEYCODE_$k; sleep 1; done
cap $TV 04-tv-apres-navigation
vivant $TV $PKG_TV && ok "Navigation télécommande sur la TV" || ko "Navigation télécommande sur la TV" "appli Box arrêtée"

video_fin
n=$(ls "$OUT/videos"/*.mp4 2>/dev/null | wc -l); [ "$n" -gt 0 ] && info "Vidéos du passage" "$n fichier(s) dans videos/" || info "Vidéos du passage" "aucune vidéo enregistrée"

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
[ "${KEEP:-0}" = 1 ] || emu_arreter   # KEEP=1 : on garde les émulateurs (session en direct)
[ $ECHECS = 0 ]
