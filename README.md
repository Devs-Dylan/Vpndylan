# DYLAN VPN

App VPN Android en Kotlin (Jetpack Compose) par **Devs-Dylan**, basée sur le core **Xray**.

## Fonctionnalités
- Import avec choix du type (VLESS, VMess, Trojan, Shadowsocks) ou détection automatique
- Format chiffré `dylan://` (AES-256-GCM), configs verrouillables et liables à un appareil
- Espace Admin (menu ☰) protégé par mot de passe pour générer les configs
- Connexion par identifiant et mot de passe : charge la config par défaut
- Durée de validité (durée ou date fixe), limites de débit (global, montant, par fichier) et priorité
- Test de latence de chaque profil (sans VPN)
- Profils : coller un ou plusieurs liens `vless://`, `vmess://`, `trojan://`, `ss://`
- Transports : TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP · Sécurité : TLS, Reality
- Connexion réelle via `VpnService` + Xray (entrée TUN)
- Vitesse, pics et totaux en direct
- Réglages enregistrés (unité de vitesse, DNS, MTU, niveau de journal)
- Écran Journaux

## Compiler
- **GitHub Actions** : chaque push sur `main` compile l'APK (artefact `DYLAN_VPN-debug`).
- **Android Studio** : ouvrir le dossier, Sync, Run.
Le core Xray (`libv2ray.aar`, ~60 Mo) est téléchargé automatiquement par Gradle dans `app/libs/` au premier build.

## Limites connues
- Le trafic IPv6 n'est pas encore envoyé dans le tunnel.
- Pas de proxy par application, ni d'abonnements, ni de service au premier plan.
- Les liens VLESS sans TLS/Reality sont refusés par Xray.

## Comptes, catégories et dépôt Git
- Connexion obligatoire. Seul le compte `admin` est intégré à l'application ; tous les autres utilisateurs sont créés dans l'espace Admin et vérifiés dans le dépôt Git.
- Catégories : Basic, Pro, Admin. L'admin peut aussi envoyer des configs à des utilisateurs précis.
- Les configs sont stockées chiffrées dans le dépôt Git (branche `vpn-data`, dossier `vpn/` : `users.txt`, `basic.txt`, `pro.txt`, `admin.txt`, `u/<login>.txt`).
- Chaque fichier est chiffré ; sa clé n'est ouverte que par le bon identifiant et le bon mot de passe.

### Mise en place
1. Passer le dépôt GitHub en **privé**.
2. Créer un jeton « fine-grained » limité à ce dépôt, permission **Contents : lecture seule**, dans un fichier `git_read_token.txt` à la racine du dépôt (le build l'intègre à l'APK).
3. Installer l'APK, se connecter en `admin`, puis Admin > Dépôt Git : coller un second jeton (**Contents : lecture et écriture**) puis « Initialiser le dépôt ». Ne jamais mettre ce jeton dans le code ni dans le dépôt.
4. Admin > Utilisateurs : créer les comptes. Admin > Générer : créer une config et la publier (catégorie ou utilisateurs choisis).

## Stabilité et partage
- Deux DNS (Réglages), keep-alive TCP, reconnexion automatique (changement de réseau, connexion morte), vérification de la connexion.
- Partage de connexion : point d'accès Wi-Fi local + proxy HTTP/SOCKS qui envoie le trafic dans le VPN.

## Interface et mise à jour (v1.2)
- Interface inspirée de NPV Tunnel (Accueil, Configs, Journaux, Plus), aux couleurs de DYLAN VPN.
- Éditeur de config (VLESS, VMess, Trojan, Shadowsocks), abonnements, import presse-papiers / fichier, QR code, ping de chaque config.
- Réglages : MTU, deux DNS, contournement du réseau local, multiplexage, fragmentation TLS, reconnexion automatique.
- Mise à jour depuis l'application : télécharge l'APK de la publication « latest » du dépôt. L'APK est signé avec `app/dylan.keystore`
  (clé fixe) : la première installation de cette version demande de désinstaller l'ancienne, les suivantes se font par-dessus.

## Quota, synchronisations, vitesse et stabilité (v1.3)
- Quota de données par config, au choix **par utilisateur** (un compteur par compte, partagé par ses configs) ou **pour le fichier** (compteur propre à la config). Compté sur l'appareil.
- Nombre maximal de synchronisations par fichier : au-delà, la config n'est plus mise à jour sur l'appareil (elle reste utilisable).
- Vitesse : tampon de 512 Ko par connexion (Xray n'en utilise que 4 Ko par défaut sur ARM64), QUIC bloqué pour un repli rapide en TCP.
- Stabilité : DNS dans le tunnel (cache, IPv4 seulement), plus de reconnexion en boucle quand la vérification échoue.

## Icône et nom
- Nom de l'application : **Devs-Dylan VPN**. Icône originale (bouclier vert avec un « D »), libre d'utilisation : `devs-dylan-vpn-icon.svg` / `.png` fournis à part.
- Configs synchronisées : conservées dans la mémoire de l'appareil et utilisables sans Internet (dernière adresse du serveur et empreinte de certificat mémorisées). Elles ne sont retirées que si le dépôt, lu avec succès, ne les contient plus.

## Exports privés dylanx
- Les exports `dylanx://` chiffrent le contenu en AES-256-GCM et enveloppent la clé de contenu avec RSA-OAEP (2048 bits) pour chaque destinataire autorisé. La clé privée est générée et conservée dans Android Keystore ; elle n’est pas exportable.
- Une passphrase facultative (PBKDF2-HMAC-SHA256, sel aléatoire, 210 000 itérations) peut servir de méthode d’ouverture complémentaire. L’import accepte la clé locale ou cette passphrase. « Accessible à toute personne ayant l’application » est un choix public, non un partage privé.
- Dans Plus > Ma clé publique, chaque appareil peut copier sa clé à transmettre à l’expéditeur. L’administration peut ajouter plusieurs clés, inclure l’appareil exportateur, saisir une passphrase, choisir le nom de fichier et enregistrer l’export. Les exports privés ne peuvent pas être publiés dans les catégories Git partagées ; ils sont destinés au partage direct.
- Les options par config comprennent « données mobiles uniquement » (connexion refusée sur Wi-Fi et arrêt lors d’un basculement), expiration date/heure, message destiné à l’utilisateur et message personnalisé affiché à la connexion.
- Les anciens liens `dylan://` restent décodables. Ils continuent toutefois d’utiliser leur ancien chiffrement partagé et ne bénéficient pas de l’isolation par appareil des exports `dylanx://`.

## Sécurité du build (v1.3.2)
- Le core `libv2ray.aar` est vérifié par son empreinte SHA-256 au téléchargement ; un fichier différent fait échouer le build.
- `allowBackup` est désactivé : la session et les clés ne partent plus dans les sauvegardes Android.
- Signature : par défaut `app/dylan.keystore` (mot de passe public dans le code). Pour une vraie clé privée, ajoute les secrets GitHub
  `DYLAN_KEYSTORE_B64` (`base64 -w0 ma.keystore`), `DYLAN_KEYSTORE_PASSWORD`, `DYLAN_KEY_ALIAS`, `DYLAN_KEY_PASSWORD` :
  le workflow les utilise automatiquement. Changer de clé oblige à désinstaller l'ancienne version une seule fois.
- Le workflow tourne en lecture seule (`permissions: contents: read`) et annule les builds en double.
