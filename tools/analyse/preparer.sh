#!/usr/bin/env bash
# Prépare l'atelier d'analyse en une commande : SDK Android, émulateur téléphone + émulateur TV,
# capture réseau (tshark/tcpdump), APK officiels installés, protection Box activée.
#   bash tools/analyse/preparer.sh            → installe (si besoin) et démarre tout
#   bash tools/analyse/preparer.sh demarrer   → démarre seulement les émulateurs (déjà installés)
# Ensuite : python3 tools/analyse/boxcli.py status   (téléphone de test, protocole de l'appli Box)
#           bash tools/analyse/capture.sh            (résumé réseau : DNS, SNI, HTTP en clair, conversations)
set -euo pipefail
W=${ATELIER:-/home/claude/atelier}
export ANDROID_HOME=$W/sdk ANDROID_SDK_ROOT=$W/sdk ANDROID_AVD_HOME=$W/avd ANDROID_I_WANT_MY_TCG=yes
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
mkdir -p "$W" "$ANDROID_AVD_HOME" "$W/cap" "$W/apk"
SITE=https://telecommande-box.netlify.app

installer() {
  command -v tshark >/dev/null || { sudo apt-get update -qq; sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq tshark tcpdump >/dev/null; }
  if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    cd "$ANDROID_HOME" 2>/dev/null || { mkdir -p "$ANDROID_HOME"; cd "$ANDROID_HOME"; }
    curl -sSLo clt.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
    unzip -q clt.zip && mkdir -p cmdline-tools/latest
    mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/
    rm clt.zip
  fi
  SDKM=$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager
  yes | $SDKM --licenses >/dev/null 2>&1 || true
  # Sans KVM : images x86 « default » (téléphone, Android 7) et « android-tv » (TV, Android 7)
  $SDKM "platform-tools" "emulator" "build-tools;34.0.0" \
    "system-images;android-24;default;x86_64" "system-images;android-24;android-tv;x86" >/dev/null 2>&1
  AVDM=$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager
  [ -d "$ANDROID_AVD_HOME/phone.avd" ] || echo no | $AVDM create avd -n phone -k "system-images;android-24;default;x86_64" -d pixel -f >/dev/null
  [ -d "$ANDROID_AVD_HOME/tv.avd" ] || echo no | $AVDM create avd -n tv -k "system-images;android-24;android-tv;x86" -d tv_1080p -f >/dev/null
}

console() { # console <port> <commande>…  (console de l'émulateur, ex. « network capture start x.pcap »)
  local port=$1; shift
  { echo "auth $(cat ~/.emulator_console_auth_token)"; for c in "$@"; do echo "$c"; sleep 1; done; echo quit; } | nc -q 2 localhost "$port" | grep -E "KO|capturing" || true
}

demarrer() {
  pgrep -f "avd tv" >/dev/null || nohup emulator -avd tv -port 5556 -no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect -accel off -memory 1536 > "$W/emu-tv.log" 2>&1 &
  sleep 5
  pgrep -f "avd phone" >/dev/null || nohup emulator -avd phone -port 5554 -no-window -no-audio -no-snapshot-save -gpu swiftshader_indirect -accel off -memory 1536 > "$W/emu-phone.log" 2>&1 &
  echo "Démarrage des émulateurs (10 à 15 min sans accélération)…"
  for i in $(seq 1 80); do
    a=$(adb -s emulator-5556 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') || true
    b=$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') || true
    [ "$a" = 1 ] && [ "$b" = 1 ] && break; sleep 15
  done
  echo "TV=$a téléphone=$b"
  # Le port 7363 de l'appli Box (TV) est joignable depuis l'atelier sur 127.0.0.1:7363 et depuis le téléphone sur 10.0.2.2:7363
  console 5556 "redir add tcp:7363:7363"
  console 5556 "network capture start tv.pcap"
  console 5554 "network capture start phone.pcap"
}

installer_apk() {
  cd "$W/apk"
  curl -s "$SITE/version.json?t=$(date +%s)" -o version.json
  curl -sSLo Telecommande.apk "$SITE/Telecommande.apk"; curl -sSLo TelecommandeBox.apk "$SITE/TelecommandeBox.apk"
  sha256sum *.apk
  adb -s emulator-5554 install -r Telecommande.apk >/dev/null && adb -s emulator-5556 install -r TelecommandeBox.apk >/dev/null
  # Protection Box (accessibilité) activée directement, comme le ferait le parent dans Paramètres
  adb -s emulator-5556 shell settings put secure enabled_accessibility_services fr.paul.telecommande.box/.GuardService
  adb -s emulator-5556 shell settings put secure accessibility_enabled 1
  adb -s emulator-5556 shell appops set fr.paul.telecommande.box GET_USAGE_STATS allow
  adb -s emulator-5556 shell monkey -p fr.paul.telecommande.box -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null
  adb -s emulator-5554 shell monkey -p fr.paul.telecommande -c android.intent.category.LAUNCHER 1 >/dev/null
  echo "Captures : $ANDROID_AVD_HOME/{tv,phone}.avd/console_out/*.pcap"
}

case "${1:-tout}" in
  demarrer) demarrer ;;
  apk) installer_apk ;;
  *) installer; demarrer; installer_apk ;;
esac
