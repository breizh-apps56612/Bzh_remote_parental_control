# Règles du projet

## Banc de test obligatoire

Tout développement (appli téléphone, appli Box, outils) passe par le banc de test émulateurs **avant publication**.
Le propriétaire du projet ne teste plus lui-même : c'est le banc qui teste.

- Workflow : `.github/workflows/emulateurs.yml` (« Émulateurs (téléphone + TV) »), script `tools/analyse/ci-emulateurs.sh`.
- Il se lance tout seul à chaque envoi sur n'importe quelle branche et sur les pull requests du dépôt.
  À la main : `gh workflow run emulateurs.yml -R breizh-apps56612/bzh_remote_parental_control` (choix d'API possibles).
- Ne pas monter d'émulateur local : envoyer le commit, attendre la fin du run, lire le résumé
  (`gh run view <id> --log | grep -E "OK  |ECHEC|INFO "`) et, si besoin, l'artefact « emulateurs-resultats »
  (captures PNG, journaux, plantages).
- Publication (site, version.json) **seulement si le run du commit à publier est vert**. Un échec se corrige puis se reteste.
- Chaque nouvelle fonction visible ou correctif ajoute sa vérification dans `ci-emulateurs.sh`
  (une ligne `ok` / `ko` par comportement attendu), pour que tout soit testé automatiquement.
- Stress test complet (≈ 25 min de plus, `tools/analyse/stress.sh`) : envoyer sur une branche `stress/…`, mettre `[stress]`
  dans le message du commit, ou « Run workflow » avec stress = oui. À faire avant une grosse publication.
- Règles du banc : `timeout` sur chaque commande adb, aucun secret, aucune donnée personnelle dans les résultats.
- Le résumé dit ce qui a été testé ; ce qui ne peut l'être qu'avec une vraie TV (Freebox, Oqee…) est signalé à part.
