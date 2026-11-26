#!/usr/bin/env bash
# Résumé « Wireshark » d'une capture d'émulateur : qui est contacté, en clair ou chiffré.
#   bash tools/analyse/capture.sh                 → les deux captures en cours (copiées à l'instant)
#   bash tools/analyse/capture.sh fichier.pcap    → un fichier précis
W=${ATELIER:-/home/claude/atelier}
files=("$@")
if [ ${#files[@]} -eq 0 ]; then
  for d in tv phone; do f=$(ls -t "$W"/avd/$d.avd/console_out/*.pcap 2>/dev/null | head -1); [ -n "$f" ] && cp "$f" "$W/cap/$d-$(date +%H%M%S).pcap" && files+=("$W/cap/$d-$(date +%H%M%S).pcap"); done
fi
for f in "${files[@]}"; do
  echo "################ $f"
  echo "-- Conversations TCP"; tshark -r "$f" -q -z conv,tcp 2>/dev/null | sed -n '6,25p'
  echo "-- Noms demandés (DNS)"; tshark -r "$f" -Y "dns.flags.response==0" -T fields -e dns.qry.name 2>/dev/null | sort | uniq -c
  echo "-- Serveurs HTTPS (SNI)"; tshark -r "$f" -Y tls.handshake.extensions_server_name -T fields -e tls.handshake.extensions_server_name 2>/dev/null | sort | uniq -c
  echo "-- HTTP en clair"; tshark -r "$f" -Y http.request -T fields -e http.host -e http.request.uri 2>/dev/null | sort | uniq -c | head -20
  echo "-- Lien appli Box (7363) : versions TLS / suites"; tshark -r "$f" -d tcp.port==7363,tls -Y "tcp.port==7363 && tls.handshake.type==2" -T fields -e tls.handshake.version -e tls.handshake.ciphersuite 2>/dev/null | sort | uniq -c
  echo "-- Mots sensibles lisibles dans la capture (doit être 0)"
  for m in pinHash get_rules set_rules pair_spake byApp dailyTotalMin; do printf "   %-14s %s\n" "$m" "$(grep -c -a "$m" "$f")"; done
done
# Rappel : ce qui n'est pas « telecommande-box.netlify.app » (connectivitycheck, mtalk, ntp…) vient d'Android lui-même.
