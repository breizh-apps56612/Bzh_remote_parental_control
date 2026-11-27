#!/usr/bin/env bash
# Émulateurs en direct depuis GitHub : téléphone + TV visibles et utilisables dans un navigateur (même sur téléphone).
# Lancé par .github/workflows/emulateur-direct.yml (à la main, onglet Actions).
#
#   bash tools/analyse/direct.sh ouvrir    → démarre émulateurs + écrans virtuels + page web protégée + lien public temporaire
#
# Sécurité :
#  - la page exige un identifiant et le mot de passe du secret GitHub EMULATEUR_MOT_DE_PASSE (jamais affiché) ;
#  - le lien passe en HTTPS par un tunnel Cloudflare temporaire ; il disparaît à la fin du job (durée choisie, 90 min max) ;
#  - les serveurs d'écran (VNC) n'écoutent que sur la machine elle-même, jamais directement sur Internet ;
#  - les émulateurs sont vierges : aucune donnée personnelle, aucune clé.
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${OUT:-$PWD/direct}; mkdir -p "$OUT/journaux" "$OUT/page"
source "$HERE/emu-lib.sh"

[ -n "${MOT_DE_PASSE:-}" ] || { echo "::error::Créez d'abord le secret EMULATEUR_MOT_DE_PASSE (Settings › Secrets and variables › Actions)."; exit 1; }
[ ${#MOT_DE_PASSE} -ge 12 ] || { echo "::error::Le mot de passe doit faire au moins 12 caractères."; exit 1; }

# --- Outils : écrans virtuels, VNC, noVNC (paquets Ubuntu) ; cloudflared depuis le dépôt signé de Cloudflare ---
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq xvfb x11vnc novnc websockify caddy >/dev/null
curl -fsSL https://pkg.cloudflare.com/cloudflare-main.gpg | sudo tee /usr/share/keyrings/cloudflare-main.gpg >/dev/null
echo "deb [signed-by=/usr/share/keyrings/cloudflare-main.gpg] https://pkg.cloudflare.com/cloudflared $(lsb_release -cs) main" \
  | sudo tee /etc/apt/sources.list.d/cloudflared.list >/dev/null
sudo apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq cloudflared >/dev/null

# --- Écrans virtuels : :1 téléphone (portrait), :2 TV (paysage) ---
nohup Xvfb :1 -screen 0 1200x2160x24 -nolisten tcp > "$OUT/journaux/xvfb-tel.log" 2>&1 &
nohup Xvfb :2 -screen 0 1980x1160x24 -nolisten tcp > "$OUT/journaux/xvfb-tv.log" 2>&1 &
sleep 2

# --- Émulateurs avec fenêtres, applis installées, protection Box activée ---
EMU_FENETRE=1 DISPLAY_PH=:1 DISPLAY_TV=:2 emu_demarrer "$OUT/journaux" || { echo "::error::Les émulateurs n'ont pas démarré"; exit 1; }
emu_installer "$APK_PHONE" "$APK_BOX" || { echo "::error::Installation des applis impossible"; exit 1; }
timeout 60 adb -s $TV shell monkey -p $PKG_TV -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
timeout 60 adb -s $PH shell monkey -p $PKG_PH -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

# --- VNC (local uniquement) → websockify → Caddy (mot de passe, HTTP local) → tunnel HTTPS ---
nohup x11vnc -display :1 -rfbport 5901 -localhost -forever -shared -nopw -quiet > "$OUT/journaux/vnc-tel.log" 2>&1 &
nohup x11vnc -display :2 -rfbport 5902 -localhost -forever -shared -nopw -quiet > "$OUT/journaux/vnc-tv.log" 2>&1 &
nohup websockify --web /usr/share/novnc 127.0.0.1:6081 127.0.0.1:5901 > "$OUT/journaux/ws-tel.log" 2>&1 &
nohup websockify --web /usr/share/novnc 127.0.0.1:6082 127.0.0.1:5902 > "$OUT/journaux/ws-tv.log" 2>&1 &

cat > "$OUT/page/index.html" <<'HTML'
<!doctype html><html lang="fr"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="robots" content="noindex"><title>Émulateurs en direct</title>
<style>body{margin:0;font:15px system-ui;background:#0e0f1a;color:#eee}header{padding:10px 14px;display:flex;gap:14px;flex-wrap:wrap;align-items:center}
a{color:#7fe3c4}main{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,2fr);gap:8px;padding:0 8px 8px;height:calc(100vh - 52px)}
iframe{width:100%;height:100%;border:0;border-radius:10px;background:#000}@media(max-width:800px){main{grid-template-columns:1fr;height:auto}iframe{height:80vh}}</style>
<header><b>Émulateurs en direct</b><a href="tel/vnc.html?path=tel/websockify&autoconnect=1&resize=scale&reconnect=1">Téléphone seul</a>
<a href="tv/vnc.html?path=tv/websockify&autoconnect=1&resize=scale&reconnect=1">TV seule</a><span>Touchez ou cliquez directement dans les écrans.</span></header>
<main><iframe src="tel/vnc.html?path=tel/websockify&autoconnect=1&resize=scale&reconnect=1" title="Téléphone"></iframe>
<iframe src="tv/vnc.html?path=tv/websockify&autoconnect=1&resize=scale&reconnect=1" title="TV"></iframe></main></html>
HTML

HASH=$(caddy hash-password --plaintext "$MOT_DE_PASSE")
cat > "$OUT/Caddyfile" <<EOF
{
  admin off
  auto_https off
}
http://127.0.0.1:8080 {
  basicauth {
    telecommande $HASH
  }
  header X-Robots-Tag "noindex, nofollow"
  handle_path /tel/* {
    reverse_proxy 127.0.0.1:6081
  }
  handle_path /tv/* {
    reverse_proxy 127.0.0.1:6082
  }
  handle {
    root * $OUT/page
    file_server
  }
}
EOF
nohup caddy run --config "$OUT/Caddyfile" --adapter caddyfile > "$OUT/journaux/caddy.log" 2>&1 &
sleep 2

nohup cloudflared tunnel --no-autoupdate --url http://127.0.0.1:8080 > "$OUT/journaux/tunnel.log" 2>&1 &
LIEN=""
for i in $(seq 1 30); do LIEN=$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$OUT/journaux/tunnel.log" | head -1); [ -n "$LIEN" ] && break; sleep 2; done
[ -n "$LIEN" ] || { echo "::error::Lien temporaire non obtenu"; tail -20 "$OUT/journaux/tunnel.log"; exit 1; }
echo "$LIEN" > "$OUT/lien.txt"
{ echo "## Émulateurs en direct"; echo
  echo "**Lien :** $LIEN"; echo
  echo "Identifiant : \`telecommande\` — mot de passe : celui du secret EMULATEUR_MOT_DE_PASSE."; echo
  echo "Le lien s'arrête à la fin de la session (annulez le run pour arrêter plus tôt)."; } >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
echo "LIEN DIRECT : $LIEN"
