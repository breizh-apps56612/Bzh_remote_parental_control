#!/usr/bin/env bash
# Stress test complet du banc — « sourcé » par ci-emulateurs.sh quand STRESS=1, après les tests habituels
# (le téléphone de test est déjà appairé). Utilise ok / ko / info / cap / cli / vivant du script principal.
# Lancement : branche « stress/… », message de commit contenant [stress], ou « Run workflow » avec stress = oui.
#
# Ce qui est éprouvé : rafales et téléphones en parallèle, messages poubelle, règles absurdes, appairages en rafale,
# connexions muettes en masse, mémoire faible, coupure réseau, plantages répétés de la Box, changement d'heure et minuit,
# appuis au hasard sur la TV et le téléphone (rotations comprises), redémarrage complet de la TV, fuites de fils / mémoire.

stress_json() { # stress_json <clé> <json> → valeur (vide si absente)
  python3 -I -c 'import json,sys
try: v=json.loads(sys.argv[2]).get(sys.argv[1]); print("" if v is None else v)
except Exception: print("")' "$1" "$2"; }
box_pid()   { timeout 20 adb -s $TV shell pidof $PKG_TV 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
box_fils()  { local p; p=$(box_pid); [ -n "$p" ] && timeout 20 adb -s $TV shell ls /proc/$p/task 2>/dev/null | wc -l || echo 0; }
box_pss()   { timeout 30 adb -s $TV shell dumpsys meminfo $PKG_TV 2>/dev/null | awk '/TOTAL/{for(i=1;i<=NF;i++) if($i ~ /^[0-9]+$/){print int($i/1024); exit}}'; }
# Plantages de la TV lus avant les plantages forcés (S10) et avant le redémarrage (S15), qui effacent ou faussent le journal
plantages_tv() { # plantages_tv <nom du fichier> <libellé>
  timeout 30 adb -s $TV logcat -d -b crash > "$OUT/journaux/$1" 2>/dev/null
  if grep -qE "FATAL EXCEPTION|Process: fr\.paul" "$OUT/journaux/$1"; then ko "$2" "voir journaux/$1"; else ok "$2"; fi
}
box_repond() { [[ $(cli status) == *'"ok": true'* ]]; }
attendre_box() { # attendre_box <secondes max> → durée dans $ATTENTE ; 0 si la Box répond (appairée)
  local t0=$SECONDS
  while [ $((SECONDS - t0)) -lt "$1" ]; do box_repond && { ATTENTE=$((SECONDS - t0)); return 0; }; sleep 2; done
  ATTENTE=$1; return 1
}
# Service de protection réellement lié (« Bound services »), pas seulement cité dans « Crashed services »
# (Android 11 : « Bound services » n'affiche que le libellé ; on exige donc : activé, au moins un service lié, absent des plantés)
service_lie() {
  local d; d=$(timeout 30 adb -s $TV shell dumpsys accessibility 2>/dev/null | tr -d '\r')
  grep -iE "enabled services" <<< "$d" | grep -q "GuardService" && grep -qiE "bound services:\{ *Service" <<< "$d" \
    && ! grep -iE "crashed services" <<< "$d" | grep -q "GuardService"
}
remettre_ports() {
  timeout 20 adb -s $TV forward tcp:7363 tcp:7363 >/dev/null 2>&1
  timeout 20 adb -s $TV emu redir del tcp:7364 >/dev/null 2>&1; timeout 20 adb -s $TV emu redir add tcp:7364:7363 >/dev/null 2>&1
}

echo "| | **Stress test complet** | |" >> "$RESUME"
F0=$(box_fils); M0=$(box_pss); info "Stress : départ" "Box : $F0 fils, ${M0:-?} Mo"

# --- S1. Rafale : 300 commandes d'affilée sur une connexion ---
r=$(cli rafale 300); n=$(stress_json ok "$r"); p95=$(stress_json ms_p95 "$r")
[ "${n:-0}" = 300 ] && ok "S1 Rafale : 300 commandes d'affilée" "300/300, p95 ${p95} ms, max $(stress_json ms_max "$r") ms" || ko "S1 Rafale : 300 commandes d'affilée" "${r:0:160}"
python3 -I -c 'import sys; sys.exit(0 if float(sys.argv[1] or 9e9) < 800 else 1)' "${p95:-}" 2>/dev/null \
  && ok "S1 Rafale : réponses rapides" "p95 ${p95} ms (< 800 ms)" || ko "S1 Rafale : réponses rapides" "p95 ${p95:-?} ms"

# --- S2. Six téléphones appairés en même temps (limite par appareil = 6) ---
r=$(cli parallele 6 50); n=$(stress_json ok "$r")
[ "${n:-0}" = 300 ] && ok "S2 Six connexions en parallèle" "300/300, p95 $(stress_json ms_p95 "$r") ms" || ko "S2 Six connexions en parallèle" "${r:0:160}"

# --- S3. Au-delà de la limite : 15 connexions → l'excédent est refusé, la Box tient ---
r=$(cli parallele 15 20); info "S3 Quinze connexions (au-delà de la limite)" "${r:0:160}"
sleep 3; box_repond && ok "S3 Après 15 connexions : la Box répond toujours" || ko "S3 Après 15 connexions : la Box répond toujours"

# --- S4. Messages poubelle depuis un autre appareil ---
r=$(FLOOD=127.0.0.1:7364 cli poubelle 60); info "S4 60 messages poubelle (autre appareil)" "${r:0:160}"
sleep 3; { vivant $TV $PKG_TV && box_repond; } && ok "S4 Messages poubelle : Box en marche et joignable" || ko "S4 Messages poubelle : Box en marche et joignable"

# --- S5. Règles absurdes (valeurs énormes, négatives, mauvais types) ---
r=$(cli fuzz_rules 150); info "S5 150 règles absurdes" "${r:0:160}"
[[ $(stress_json regles_relues "$r") == True ]] && vivant $TV $PKG_TV && ok "S5 Règles absurdes : Box en marche, règles relues" || ko "S5 Règles absurdes : Box en marche, règles relues" "${r:0:160}"
cli set_rules '{"rules":{"enabled":false,"version":30000}}' >/dev/null

# --- S6. Appairages en rafale d'un téléphone inconnu ---
r=$(cli pair_spam 20); acc=$(stress_json acceptees "$r")
[ "${acc:-99}" -le 6 ] && ok "S6 20 demandes d'appairage d'un inconnu : freinées" "${r:0:140}" || ko "S6 20 demandes d'appairage d'un inconnu : freinées" "${r:0:160}"
cap $TV s06-tv-apres-appairages
for k in BACK BACK HOME; do timeout 20 adb -s $TV shell input keyevent KEYCODE_$k; sleep 1; done
vivant $TV $PKG_TV && box_repond && ok "S6 Après les demandes d'appairage : la Box répond" || ko "S6 Après les demandes d'appairage : la Box répond"

# --- S7. 50 connexions muettes d'un autre appareil ---
r=$(FLOOD=127.0.0.1:7364 cli flood 50)
[[ $r == *"BLOQUÉ"* || $r != *'"ok": true'* ]] && ko "S7 Parent servi malgré 50 connexions muettes" "${r:0:160}" || ok "S7 Parent servi malgré 50 connexions muettes"

# --- S8. Mémoire faible (Android demande de libérer de la mémoire) ---
for lvl in RUNNING_MODERATE RUNNING_LOW RUNNING_CRITICAL COMPLETE; do
  timeout 20 adb -s $TV shell am send-trim-memory $PKG_TV $lvl >/dev/null 2>&1; sleep 1; done
sleep 3; vivant $TV $PKG_TV && box_repond && ok "S8 Mémoire faible (4 niveaux) : Box en marche" || ko "S8 Mémoire faible (4 niveaux) : Box en marche"

# --- S9. Coupure réseau de la TV (mode avion 15 s) ---
if timeout 20 adb -s $TV shell cmd connectivity airplane-mode enable >/dev/null 2>&1; then
  sleep 15; timeout 20 adb -s $TV shell cmd connectivity airplane-mode disable >/dev/null 2>&1; sleep 3; remettre_ports
  attendre_box 90 && ok "S9 Coupure réseau de 15 s : Box rejointe" "après ${ATTENTE} s" || ko "S9 Coupure réseau de 15 s : Box rejointe" "toujours injoignable après 90 s"
else info "S9 Coupure réseau" "mode avion indisponible sur cet émulateur"; fi

# --- S10. Plantages répétés de la Box (5 fois) : Android doit la relancer seul ---
plantages_tv plantages-tv-stress-1.txt "Aucun plantage de la TV pendant S1 à S9"
ROOT=0; r=$(timeout 30 adb -s $TV root 2>&1 | tr -d '\r' | head -1); sleep 5; timeout 30 adb -s $TV wait-for-device >/dev/null 2>&1
[ "$(timeout 20 adb -s $TV shell id -u 2>/dev/null | tr -d '\r')" = 0 ] && ROOT=1
info "S10 Accès administrateur sur la TV" "$([ $ROOT = 1 ] && echo oui || echo "non : ${r:0:80}")"
remettre_ports
# Box 1.4.61 : une erreur dans l'appli est rattrapée (journal CRASH), la Box reste en marche.
# Avant : après deux plantages en une minute, Android ne relançait plus la protection (« Crashed services »).
p0=$(box_pid); vivants=0; delais=""
for i in 1 2 3 4 5; do
  timeout 20 adb -s $TV shell am crash $PKG_TV >/dev/null 2>&1; t0=$SECONDS; sleep 2
  attendre_box 240 && d=$((SECONDS - t0)) || d="> 240"; delais="$delais $d s ;"
  [ "$(box_pid)" = "$p0" ] && vivants=$((vivants+1))
  timeout 20 adb -s $TV shell input keyevent KEYCODE_BACK >/dev/null 2>&1   # ferme un éventuel « l'appli s'est arrêtée »
done
etat=$(timeout 30 adb -s $TV shell dumpsys accessibility 2>/dev/null | grep -iE "crashed services" | head -1 | tr -s ' \r\n' ' ' | cut -c1-120)
nerr=$(stress_json errorsCaught "$(timeout 90 python3 -I "$HERE/boxcli.py" status 2>/dev/null)")
info "S10 5 erreurs forcées (am crash)" "délais :${delais} même processus $vivants/5, erreurs rattrapées : ${nerr:-?} — ${etat:-}"
[ $vivants = 5 ] && ok "S10 5 erreurs forcées : la Box reste en marche (erreur rattrapée)" "même processus 5/5, ${nerr:-?} erreur(s) notée(s)" \
  || ko "S10 5 erreurs forcées : la Box reste en marche (erreur rattrapée)" "processus changé $((5 - vivants)) fois — délais :${delais}"
[[ $delais != *">"* ]] && ok "S10 Après chaque erreur : la Box répond" "délais :${delais}" || ko "S10 Après chaque erreur : la Box répond" "délais :${delais} — ${etat:-}"
service_lie && ok "S10 Service de protection toujours lié" || ko "S10 Service de protection toujours lié" "${etat:-absent de « Bound services »}"
if [ $ROOT = 1 ]; then   # vrai arrêt du processus (impossible à rattraper) : Android doit relancer la Box
  p=$(box_pid); timeout 20 adb -s $TV shell kill -9 "$p"; sleep 2
  attendre_box 120 && ok "S10 Processus tué (kill -9) : Box relancée par Android" "${ATTENTE} s" || ko "S10 Processus tué (kill -9) : Box relancée par Android" "pas de retour en 120 s"
fi
timeout 20 adb -s $TV logcat -b crash -c 2>/dev/null   # plantages volontaires : on ne les compte pas ensuite

# --- S11. Changement d'heure : passage de minuit et fuseau horaire ---
if [ $ROOT = 1 ]; then
  tz0=$(timeout 20 adb -s $TV shell getprop persist.sys.timezone 2>/dev/null | tr -d '\r')
  timeout 20 adb -s $TV shell settings put global auto_time 0 >/dev/null 2>&1
  jour=$(timeout 20 adb -s $TV shell date +%m%d 2>/dev/null | tr -d '\r'); an=$(timeout 20 adb -s $TV shell date +%Y 2>/dev/null | tr -d '\r')
  timeout 20 adb -s $TV shell date "${jour}2359${an}.40" >/dev/null 2>&1   # 23:59:40, heure de la TV
  sleep 35
  d=$(timeout 20 adb -s $TV shell date +%H:%M 2>/dev/null | tr -d '\r')
  maint=$(( $(timeout 20 adb -s $TV shell date +%s 2>/dev/null | tr -d '\r') * 1000 ))
  r=$(timeout 90 python3 -I "$HERE/boxcli.py" usage "{\"from\":$((maint - 2*86400000)),\"to\":$((maint + 86400000))}" 2>&1 | python3 -I -c '
import json, sys
try: d = json.loads(sys.stdin.read()); print("OUI" if isinstance(d.get("sessions"), list) else "NON " + str(d)[:80])
except Exception as e: print("NON " + str(e)[:80])')
  { vivant $TV $PKG_TV && [[ $r == OUI* ]]; } && ok "S11 Passage de minuit : Box en marche, activité lisible" "TV à $d" || ko "S11 Passage de minuit : Box en marche, activité lisible" "TV à $d — $r"
  for tz in Pacific/Kiritimati America/Los_Angeles Europe/Paris; do
    timeout 20 adb -s $TV shell setprop persist.sys.timezone $tz >/dev/null 2>&1; sleep 3; done
  box_repond && ok "S11 Trois changements de fuseau horaire : Box joignable" || ko "S11 Trois changements de fuseau horaire : Box joignable"
  timeout 20 adb -s $TV shell setprop persist.sys.timezone "${tz0:-GMT}" >/dev/null 2>&1
  timeout 20 adb -s $TV shell date -u "$(date -u +%m%d%H%M%Y.%S)" >/dev/null 2>&1   # heure réelle remise
  timeout 20 adb -s $TV shell settings put global auto_time 1 >/dev/null 2>&1
else info "S11 Changement d'heure" "pas d'accès administrateur sur l'émulateur TV : non testé"; fi

# --- S12. 1500 appuis au hasard dans l'appli Box (TV) ---
timeout 20 adb -s $TV shell monkey -p $PKG_TV -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1; sleep 3
timeout 420 adb -s $TV shell monkey -p $PKG_TV -s 1717 --throttle 100 --pct-syskeys 0 --pct-appswitch 0 -v 1500 > "$OUT/journaux/monkey-tv.txt" 2>&1
cap $TV s12-tv-apres-monkey
if grep -qE "CRASH: fr\.paul|ANR in fr\.paul" "$OUT/journaux/monkey-tv.txt"; then ko "S12 1500 appuis au hasard sur la TV" "voir journaux/monkey-tv.txt"
else ok "S12 1500 appuis au hasard sur la TV" "graine 1717$(grep -qE "ANR in|CRASH" "$OUT/journaux/monkey-tv.txt" && echo " (arrêt causé par le système de l'émulateur, pas par la Box)")"; fi
for k in BACK BACK HOME; do timeout 20 adb -s $TV shell input keyevent KEYCODE_$k; sleep 1; done

# --- S13. Téléphone : 3 × 1000 appuis au hasard avec rotations d'écran ---
rot_ko=0
for g in 11 22 33; do
  timeout 60 adb -s $PH shell monkey -p $PKG_PH -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 2
  timeout 420 adb -s $PH shell monkey -p $PKG_PH -s $g --throttle 80 --pct-syskeys 0 --pct-appswitch 0 --pct-rotation 5 -v 1000 \
    > "$OUT/journaux/monkey-telephone-$g.txt" 2>&1
  grep -qE "CRASH: fr\.paul|ANR in fr\.paul" "$OUT/journaux/monkey-telephone-$g.txt" && rot_ko=$((rot_ko+1))
  a=$(grep -oE "ANR in [a-z.]+|CRASH: [a-z.]+" "$OUT/journaux/monkey-telephone-$g.txt" | grep -v fr.paul | head -1)
  [ -n "$a" ] && info "S13 Série $g arrêtée par l'émulateur" "$a (pas l'appli Télécommande)"
done
[ $rot_ko = 0 ] && ok "S13 3000 appuis au hasard + rotations sur le téléphone" "graines 11, 22, 33" || ko "S13 3000 appuis au hasard + rotations sur le téléphone" "$rot_ko série(s) en échec, voir journaux/monkey-telephone-*.txt"
timeout 20 adb -s $PH shell settings put system accelerometer_rotation 0 >/dev/null 2>&1
for i in $(seq 1 20); do timeout 20 adb -s $PH shell settings put system user_rotation $((i % 4)) >/dev/null 2>&1; sleep 0.7; done
timeout 20 adb -s $PH shell settings put system user_rotation 0 >/dev/null 2>&1; sleep 2
vivant $PH $PKG_PH && ok "S13 20 rotations d'écran d'affilée : appli téléphone en marche" || ko "S13 20 rotations d'écran d'affilée : appli téléphone en marche"
cap $PH s13-telephone-apres-rotations

# --- S14. Fuites (avant le redémarrage) ---
F1=$(box_fils); M1=$(box_pss)
info "S14 Box après le stress" "$F1 fils (départ $F0), ${M1:-?} Mo (départ ${M0:-?} Mo)"
[ "$F1" -gt 0 ] && [ "$F1" -le $((F0 + 20)) ] && [ "$F1" -lt 90 ] && ok "S14 Pas d'accumulation de fils dans la Box" "$F0 → $F1" || ko "S14 Pas d'accumulation de fils dans la Box" "$F0 → $F1"
[ -n "$M1" ] && [ "$M1" -lt 250 ] && ok "S14 Mémoire de la Box raisonnable" "${M1} Mo (< 250 Mo)" || ko "S14 Mémoire de la Box raisonnable" "${M1:-?} Mo"

# --- S15. Redémarrage complet de la TV : la protection doit revenir seule ---
plantages_tv plantages-tv-stress-2.txt "Aucun plantage de la TV pendant S11 à S14"
t0=$SECONDS
timeout 30 adb -s $TV reboot >/dev/null 2>&1; sleep 10
for i in $(seq 1 36); do [ "$(timeout 10 adb -s $TV shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break; sleep 5; done
demarre=$((SECONDS - t0)); remettre_ports
if attendre_box 180; then ok "S15 Redémarrage de la TV : Box rejointe sans rien toucher" "TV démarrée en ${demarre} s, Box joignable ${ATTENTE} s après"
else ko "S15 Redémarrage de la TV : Box rejointe sans rien toucher" "injoignable 180 s après le démarrage"; fi
service_lie && ok "S15 Après redémarrage : service de protection lié" || ko "S15 Après redémarrage : service de protection lié"
cap $TV s15-tv-apres-redemarrage

echo "| | **Fin du stress test** | |" >> "$RESUME"
