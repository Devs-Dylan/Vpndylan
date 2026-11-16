# Serveur DYLAN VPN (inscription, sessions)

1. Crée un NOUVEAU jeton GitHub « fine-grained » : dépôt Vpndylan uniquement, permission Contents = lecture et écriture.
2. Installe wrangler et connecte-toi : `npm i -g wrangler && wrangler login`
3. Enregistre les secrets (ils ne sont lus que par le Worker) :
   - `wrangler secret put GH_TOKEN`   (le nouveau jeton)
   - `wrangler secret put PEPPER`     (texte aléatoire long, sert à hacher les numéros)
   - `wrangler secret put ADMIN_KEY`  (texte aléatoire long, pour l'écran admin)
   - `wrangler secret put BASIC_KEY`  (clé de la catégorie basic : dans l'app, connecté en admin, onglet Git → « Copier BASIC_KEY »)
   - `wrangler secret put USERS_KEY`  (facultatif, configs personnelles : « Copier USERS_KEY »)
4. Base du chat (D1) :
   - `wrangler d1 create dylan-chat` puis colle le `database_id` affiché dans `wrangler.toml` (remplace REMPLACE_PAR_TON_DATABASE_ID)
   - `wrangler d1 execute dylan-chat --remote --file=schema.sql`
5. `wrangler deploy` : tu obtiens l'adresse https://dylan-vpn-server.<toi>.workers.dev
6. Dans le dépôt, crée `server_url.txt` à la racine avec cette adresse (une ligne). L'app l'intègre au build et le bouton
   « Créer un compte » apparaît sur l'écran de connexion.

Prérequis : le dépôt de données doit être initialisé depuis l'app (admin → Git → Initialiser), sinon /register répond 503.

Routes (POST, JSON) : /check, /register, /login, /session, /chat/key/put, /chat/key/get, /chat/users, /chat/send, /chat/poll,
/admin/user et /admin/users (en-tête x-admin-key).

Chat : salon commun + messages privés. Les messages sont chiffrés par l'app avant l'envoi (le serveur ne stocke que du texte chiffré,
30 jours). Privés : chiffrement de bout en bout (ECDH P-256, clé privée uniquement sur l'appareil). Salon : clé dérivée de la clé basic.
Une nouvelle connexion remplace l'ancienne session : l'ancien appareil est déconnecté du chat.
À l'inscription, le serveur crée `chat/users/<nom>.json` (nom unique, métadonnées) puis ajoute la ligne du compte dans
`vpn/users.txt` au format de l'app (catégorie basic). L'app s'y connecte ensuite normalement.
Limite : Cloudflare Workers plafonne PBKDF2 à 100 000 itérations ; l'app lit ce nombre dans le champ sel (« sel~100000 »).
