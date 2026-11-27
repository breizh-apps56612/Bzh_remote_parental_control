#!/usr/bin/env bash
# Tour automatique des deux applis (téléphone + TV) sur émulateurs — lancé par .github/workflows/tour.yml.
# Résultat dans $OUT/telephone et $OUT/tv : ecrans.json, captures/, index.html (galerie à ouvrir dans un navigateur).
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$PWD/tour-resultats}; MINUTES=${MINUTES:-12}
source "$HERE/emu-lib.sh"
emu_demarrer "$OUT/journaux" || exit 1
emu_installer "$APK_PHONE" "$APK_BOX" || { echo "installation impossible"; exit 1; }
timeout 20 adb -s $TV logcat -c; timeout 20 adb -s $PH logcat -c
timeout $((MINUTES*60+300)) python3 -I "$HERE/tour.py" $PH $PKG_PH "$OUT/telephone" --minutes "$MINUTES" > "$OUT/journaux/tour-telephone.txt" 2>&1 &
timeout $((MINUTES*60+300)) python3 -I "$HERE/tour.py" $TV $PKG_TV "$OUT/tv" --minutes "$MINUTES" --launcher LEANBACK_LAUNCHER > "$OUT/journaux/tour-tv.txt" 2>&1 &
wait
tail -3 "$OUT"/journaux/tour-*.txt
for d in $PH $TV; do timeout 30 adb -s $d logcat -d -b crash > "$OUT/journaux/plantages-$d.txt" 2>/dev/null; done
emu_arreter
