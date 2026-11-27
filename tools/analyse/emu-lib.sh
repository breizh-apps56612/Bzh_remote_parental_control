#!/usr/bin/env bash
# Fonctions communes du banc (à « sourcer ») : démarrage des émulateurs téléphone + TV avec KVM.
#   source tools/analyse/emu-lib.sh ; emu_demarrer "$OUT"   → 0 si les deux émulateurs ont démarré
# Variables : API_PHONE, API_TV (défaut 30). Téléphone = emulator-5554, TV = emulator-5556.
API_PHONE=${API_PHONE:-30}; API_TV=${API_TV:-30}
PH=emulator-5554; TV=emulator-5556
PKG_PH=fr.paul.telecommande; PKG_TV=fr.paul.telecommande.box
export ANDROID_AVD_HOME=${ANDROID_AVD_HOME:-${RUNNER_TEMP:-/tmp}/avd}; mkdir -p "$ANDROID_AVD_HOME"
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH

emu_demarrer() { # emu_demarrer <dossier des journaux>
  local log=$1; mkdir -p "$log"
  yes | sdkmanager --licenses >/dev/null 2>&1 || true
  sdkmanager --install "emulator" "platform-tools" \
    "system-images;android-$API_PHONE;default;x86_64" "system-images;android-$API_TV;android-tv;x86" >/dev/null || return 1
  echo no | avdmanager create avd -f -n phone -k "system-images;android-$API_PHONE;default;x86_64" -d pixel
  echo no | avdmanager create avd -f -n tv -k "system-images;android-$API_TV;android-tv;x86" -d tv_1080p
  adb start-server >/dev/null 2>&1   # crée la clé adb AVANT les émulateurs (sinon « device unauthorized »)
  local opts=(-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel on -memory 2048 -netfast)
  nohup emulator -avd tv -port 5556 "${opts[@]}" > "$log/emulateur-tv.log" 2>&1 &
  nohup emulator -avd phone -port 5554 "${opts[@]}" > "$log/emulateur-telephone.log" 2>&1 &
  local a b i
  for i in $(seq 1 60); do
    a=$(timeout 10 adb -s $TV shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    b=$(timeout 10 adb -s $PH shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
    [ "$a" = 1 ] && [ "$b" = 1 ] && break
    if [ "$i" = 12 ] && timeout 10 adb devices | grep -q unauthorized; then adb kill-server; adb start-server >/dev/null 2>&1; fi
    pgrep -f "avd tv" >/dev/null && pgrep -f "avd phone" >/dev/null || { tail -5 "$log"/emulateur-*.log; break; }
    sleep 10
  done
  EMU_SECONDES=$((i*10))
  [ "$a" = 1 ] && [ "$b" = 1 ] || { echo "TV=$a téléphone=$b"; return 1; }
  local d s
  for d in $PH $TV; do for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    timeout 20 adb -s $d shell settings put global $s 0; done; done
}

emu_installer() { # emu_installer <apk téléphone> <apk box> ; protection Box activée comme le ferait le parent
  timeout 180 adb -s $PH install -r -g "$1" >/dev/null 2>&1 || return 1
  timeout 180 adb -s $TV install -r -g "$2" >/dev/null 2>&1 || return 2
  timeout 20 adb -s $TV shell settings put secure enabled_accessibility_services $PKG_TV/.GuardService
  timeout 20 adb -s $TV shell settings put secure accessibility_enabled 1
  timeout 20 adb -s $TV shell appops set $PKG_TV GET_USAGE_STATS allow
  # Port 7363 de la Box : 127.0.0.1:7363 ici (adb forward) et 10.0.2.2:7363 depuis le téléphone ;
  # 127.0.0.1:7364 ici = redirection console, vue par la TV comme un autre appareil (10.0.2.2)
  timeout 20 adb -s $TV forward tcp:7363 tcp:7363 >/dev/null
  timeout 20 adb -s $TV emu redir add tcp:7364:7363 >/dev/null
}

emu_arreter() { timeout 20 adb -s $PH emu kill >/dev/null 2>&1; timeout 20 adb -s $TV emu kill >/dev/null 2>&1; }
