# Serveur DYLAN VPN (inscription, sessions)

1. Crée un NOUVEAU jeton GitHub « fine-grained » : dépôt Vpndylan uniquement, permission Contents = lecture et écriture.
2. Installe wrangler et connecte-toi : `npm i -g wrangler && wrangler login`
3. Enregistre les secrets (ils ne sont lus que par le Worker) :
   - `wrangler secret put GH_TOKEN`    (le nouveau jeton)
   - `wrangler secret put PEPPER`      (texte aléatoire long, sert à hacher les numéros)
   - `wrangler secret put ADMIN_KEY`   (texte aléatoire long, pour l'écran admin)
4. `wrangler deploy` : tu obtiens l'adresse https://dylan-vpn-server.<toi>.workers.dev

Routes (POST, JSON) : /check, /register, /login, /session, /admin/user (en-tête x-admin-key).
Limites : Cloudflare Workers plafonne PBKDF2 à 100 000 itérations ; garde des mots de passe de 8 caractères ou plus.
