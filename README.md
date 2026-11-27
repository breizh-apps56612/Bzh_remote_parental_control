# Télécommande

**Une télécommande complète et un vrai contrôle parental pour les box et TV Android TV.**
Gratuite, open source (GPL-3.0), sans compte, sans serveur, sans publicité.

- **Site et téléchargement** : https://telecommande-box.netlify.app
- **Téléphone** : Android 5.0 ou plus récent · **TV** : box ou téléviseur sous Android TV / Google TV (testé sur Freebox Player POP)

## Ce que fait l'appli

- **Interface « Aurora »** (téléphone 2.4.38) : barre de navigation Maison · Parental · Activité · Réglages avec la télécommande au centre ; accueil « en direct » de la TV principale et demandes de temps à accepter d'un geste ; écran Parental organisé par profil (temps par jour de chaque enfant réglable en un geste).
- **Activité par profil** (téléphone 2.4.48 / TV 1.4.47) : l'écran Activité montre le temps, les applis et la semaine de l'enfant choisi (pastilles de profil), avec ses propres limites ; le profil parent n'a jamais de limite affichée.

- **Télécommande** : pavé tactile, clavier du téléphone, vraie souris (flèche affichée sur la TV), lancement des applis avec leurs icônes, volume, veille.
- **Contrôle parental** (appli « Box » installée sur la TV, active même téléphone éteint) : temps par jour au total et par appli, horaires et jours autorisés, couvre-feu, profils « Qui regarde ? » (demandé à chaque allumage, ou profil par défaut changeable sur la TV), fin d'épisode avant de couper, demandes de temps depuis la TV, messages et récompenses, Paramètres et installations protégés.
- **Code parent validé sur le téléphone** (empreinte, visage ou code) : l'enfant ne voit jamais le code.

## Vie privée

Les données restent à la maison : sur la TV et le téléphone appairé, échangées en TLS mutuel sur le Wi-Fi local. Aucun compte, aucun serveur, aucun traceur. Le seul accès Internet sert à vérifier les mises à jour sur le site.

- **Appairage sûr** (téléphone 2.4.39 / Box 1.4.41) : le code affiché sur la TV est vérifié par SPAKE2. Il ne circule jamais, même sous une forme devinable : une fausse box sur le Wi-Fi n'apprend rien, et chaque tentative d'intrus ne teste qu'un seul code (3 essais par code).
- **Un seul parent décide** : dès qu'un téléphone est appairé, tout nouveau téléphone doit être validé par le parent (code parent sur la TV ou téléphone déjà appairé). Un enfant ne peut pas appairer le sien en lisant le code à l'écran.
- **Rien dans les sauvegardes** : la clé du téléphone et le code parent ne partent pas dans la sauvegarde Google ni dans une sauvegarde ADB.
- **Résistant au blocage** : un appareil du Wi-Fi ne peut pas occuper toutes les connexions de la box pour empêcher le parent de se connecter.

## Installer

1. Sur le téléphone : ouvrez https://telecommande-box.netlify.app et installez l'appli.
2. Sur la TV : installez l'appli gratuite « Downloader » depuis le Play Store.
3. Suivez l'assistant du téléphone, étape par étape. Aucun mode développeur, aucun ordinateur.

## Compiler

```
gradle :shared:testReleaseUnitTest :app:assembleRelease :box:assembleRelease
```

Il faut le SDK Android 34 (`ANDROID_HOME`). Les APK officiels sont signés avec une clé qui **n'est pas** dans ce dépôt : un APK que vous compilez vous-même ne peut pas mettre à jour la version installée depuis le site (Android refuse une signature différente). C'est voulu, pour que personne ne puisse publier une fausse mise à jour.

Les APK officiels sont compilés, testés et signés par GitHub Actions à chaque envoi sur `main` (onglet **Actions** › dernier run › **Artifacts**). La clé de signature y est un secret du dépôt, invisible et inaccessible aux copies du dépôt. Les numéros de version sont fixés dans `app/build.gradle.kts` et `box/build.gradle.kts`.

## Licence

GNU General Public License v3.0 : vous pouvez utiliser, étudier, modifier et redistribuer l'appli, à condition que les versions modifiées restent sous la même licence et que leur code source soit fourni. Voir `LICENSE`.

## Signaler un problème

Ouvrez une « issue » sur GitHub. Joignez si possible le journal de diagnostic de l'appli (bouton « Partager » du journal) ; il ne contient jamais ce que vous tapez, ni les textes affichés sur la TV (titres, recherches) : seulement leur nombre et les mentions de classification d'âge.

---

# Documentation technique

Application Android native (Kotlin), **Android 5.0 et plus** (~99 % des appareils), sans compte ni publicité.
Pilote les téléviseurs et box en **infrarouge** et en **Wi-Fi**.

## Appareils pris en charge

| Type | Protocole | Appairage |
|---|---|---|
| Infrarouge (téléphone avec émetteur IR : Xiaomi, Redmi, POCO, Huawei, Honor…) | IR – Samsung, LG, Sony, Philips RC5, Toshiba, ou « Toutes marques » | Aucun |
| Samsung Smart TV 2016+ (Tizen) | WebSocket 8002/8001 | « Autoriser » sur la TV |
| LG webOS 2014+ | WebSocket 3001/3000 | Accepter sur la TV |
| Android TV / Google TV (Sony, Philips, TCL, Hisense, Xiaomi, Shield, Chromecast, box Android TV) | Android TV Remote v2 (TLS + protobuf) | Code à 6 caractères affiché sur la TV |
| Roku / TV Roku | ECP HTTP 8060 | Aucun |
| Sony Bravia non Android | IRCC-IP | Clé pré-partagée |
| Philips non Android (avant 2016) | JointSpace 1925 | Aucun |
| Panasonic Viera 2011-2018 | SOAP 55000 | Aucun |
| Freebox Player | API télécommande HTTP | Code réseau 8 chiffres |
| Kodi / LibreELEC / OSMC | JSON-RPC HTTP | Identifiant facultatif |

Fonctions : recherche automatique sur le Wi-Fi (SSDP + mDNS), allumage Wake-on-LAN, boutons volume du téléphone, répétition en maintenant une touche, pavé numérique, touches couleur et lecture, reconnexion automatique.

## Assistant intelligent (box Android TV / Google TV)

L'écran télécommande est un **pavé tactile** (glisser = flèches, toucher = OK, appui long = menu, double-toucher à gauche/droite = −10 s/+10 s, bande de droite = volume) surmonté d'un **panneau qui s'adapte à l'appli ouverte sur la box** :

- **YouTube** : appui automatique sur « Passer l'annonce » dès que YouTube l'autorise, saut des passages sponsorisés (SponsorBlock), barre de progression, ±10 s, vitesse, recherche au clavier du téléphone. Association une fois pour toutes via YouTube > Paramètres > Associer un appareil (code TV). Continue en arrière-plan (notification « Assistant TV »).
- **TV en direct** : chaînes ±, chaîne d'avant, guide, pavé numérique, veille programmée.
- **Accueil** : lancement direct de YouTube, Netflix, Prime Video, Disney+ ; veille dans 30 min.
- **Autre appli** (Netflix, myCANAL…) : ±10 s, clavier, veille.

Les autres appareils (Samsung, LG, Roku, IR…) gardent le pavé tactile avec des touches rapides.

## Appli Box (Android TV) – contrôle parental avancé

Module `box/`, installé **sur la TV**. Aucun mode développeur, aucun ordinateur :

1. Installer « Télécommande Box » sur la TV (Play Store de la TV une fois publiée).
2. L'ouvrir et activer **une** option : Paramètres > Accessibilité > Télécommande Box.
3. Dans l'appli du téléphone : la box > Temps d'écran > « Appairer l'appli Box » et saisir le code affiché sur la TV.

Fonctions : mesure du temps par appli (même téléphone éteint), limites et horaires par appli (jours, plages), temps total, couvre-feu,
écran « Temps écoulé » avec **demande de temps** (notification Accepter/Refuser sur le téléphone), compte à rebours à 5 et 1 min,
bonus de temps à distance, protection des Paramètres de la TV par le code parent, tableau de bord (aujourd'hui, 7 jours, plages horaires, top applis),
titre en cours (facultatif). Options facultatives selon la box : historique des jours précédents, titre en cours, anti-désinstallation.

**Assistant des règles** (calculé sur le téléphone, sans IA en ligne ni serveur) :
- **réglages pré-remplis selon l'âge** de l'enfant (temps par jour, couvre-feu du plus jeune), toujours modifiables ;
- **détection des règles incohérentes**, avec une correction en un geste : couvre-feu qui coupe avant la fin des horaires,
  limite par jour qui ne peut jamais être atteinte, aucun jour coché, programmes au-dessus de l'âge, limite d'appli plus longue que le temps total,
  YouTube sans classement par âge pour les moins de 13 ans, couvre-feu commun à des enfants d'âges éloignés.

Les âges conseillés sont des propositions de départ, pas une recommandation officielle.

Sécurité : TLS mutuel (clé RSA par appareil), appairage par code TV + preuve HMAC liée aux deux certificats (anti-interception), empreinte de la box épinglée,
code parent jamais transmis (empreinte uniquement), réglages chiffrés sur la box (EncryptedSharedPreferences), aucune donnée hors du domicile.

## Récupérer l'APK

Dépôt GitHub → onglet **Actions** → dernier run « Build Android APK » → section **Artifacts** → télécharger `Telecommande-apk` → dézipper → installer l'APK sur le téléphone (autoriser « sources inconnues »).

## Signature (facultatif)

Sans configuration, l'APK est signé avec une clé de debug : il s'installe directement.
Pour une signature définitive (mises à jour sans désinstaller), créer ces secrets GitHub (Settings → Secrets → Actions) :

- `ANDROID_KEYSTORE_BASE64` : le fichier .jks encodé en base64
- `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`

## Structure

```
app/src/main/java/fr/paul/telecommande/
├── core/      Model.kt (touches, types), DeviceStore.kt (sauvegarde), Discovery.kt (scan réseau)
├── drivers/   un pilote par protocole + Net.kt (HTTP/WebSocket/WoL), Proto.kt, SelfSignedCert.kt
└── ui/        MainActivity (liste d'appareils), RemoteActivity (télécommande)
```
