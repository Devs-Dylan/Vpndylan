-- Base D1 du chat : sessions, clés publiques, messages (chiffrés par les applications)
CREATE TABLE IF NOT EXISTS sessions (
  name TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL,
  device_id TEXT,
  at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS pubkeys (
  name TEXT PRIMARY KEY,
  pub TEXT NOT NULL,
  at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  room TEXT NOT NULL,          -- 'general' ou 'dm'
  sender TEXT NOT NULL,
  recipient TEXT,              -- NULL pour le salon
  ts INTEGER NOT NULL,
  body TEXT NOT NULL           -- texte chiffré (base64url), illisible pour le serveur dans les privés
);
CREATE INDEX IF NOT EXISTS idx_msg_room ON messages(room, id);
CREATE INDEX IF NOT EXISTS idx_msg_rcpt ON messages(recipient, id);
CREATE INDEX IF NOT EXISTS idx_msg_sender ON messages(sender, id);
