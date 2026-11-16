package com.devsdylan.dylanvpn.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.devsdylan.dylanvpn.ui.Pal
import androidx.compose.ui.unit.dp

@Composable
fun GuideScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Pal.CardHi)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Guide", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("Premiers pas avec DYLAN VPN.", color = Pal.Sub)
            }
        }
        GuideCard(
            "Comptes et catégories",
            "Connecte-toi avec ton identifiant et ton mot de passe. Ta catégorie (Basic, Pro ou Admin) détermine les configs que tu reçois.\n" +
                "• Les configs sont téléchargées automatiquement et mises à jour à chaque synchronisation (onglet Plus).\n" +
                "• Si l'administrateur supprime ton compte ou change tes accès, tu es déconnecté à la prochaine synchronisation."
        )
        GuideCard(
            "Utilisation",
            "1. Onglet Profils : tes configs apparaissent après la synchronisation. Tu peux aussi importer un lien avec le bouton +.\n" +
                "2. Appuie sur l'icône de test d'un profil pour vérifier que le serveur répond.\n" +
                "3. Sélectionne le profil, retourne sur Accueil et appuie sur le gros bouton. Android demande d'autoriser le VPN la première fois."
        )
        GuideCard(
            "Partage de connexion",
            "Plus > Partage de connexion : l'app crée un point d'accès Wi-Fi (nom et mot de passe choisis par Android).\n" +
                "Active « Proxy de partage », connecte le VPN, puis sur l'autre appareil règle le proxy du Wi-Fi sur l'adresse et le port affichés."
        )
        GuideCard(
            "Configs et mise à jour",
            "• Onglet Configs : le + permet d'importer (abonnement, presse-papiers, fichier) ou de créer une config à la main. Le crayon modifie une config, l'éclair-compteur teste le ping.\n" +
                "• Plus > Mise à jour de l'application : télécharge et installe la dernière version publiée sur GitHub.\n" +
                "• Un administrateur peut envoyer des configs à une catégorie ou à des personnes précises."
        )
        GuideCard(
            "Liens pris en charge",
            "VLESS, VMess, Trojan et Shadowsocks.\n" +
                "Transports : TCP, WebSocket, gRPC, HTTPUpgrade, XHTTP.\n" +
                "Sécurité : TLS et Reality."
        )
        GuideCard(
            "Configs Dylan",
            "Les liens dylan:// sont des configs chiffrées créées par l'administrateur.\n" +
                "• Ils peuvent être liés à un seul appareil : donne ton ID (menu ☰, À propos) à l'administrateur.\n" +
                "• Une config verrouillée cache les détails du serveur.\n" +
                "• Elle peut avoir une durée de validité et des limites de débit : elles sont affichées sur ton profil.\n" +
                "• Si tu as un identifiant et un mot de passe, utilise « Connexion » dans le menu ☰."
        )
        GuideCard(
            "Bon à savoir",
            "• Un lien VLESS sans TLS ni Reality est refusé par le core Xray.\n" +
                "• Les réglages (DNS, MTU...) s'appliquent à la prochaine connexion.\n" +
                "• L'onglet Journaux du menu ☰ affiche les détails en cas d'erreur.\n" +
                "• Deux DNS sont utilisés (Réglages) : si le premier ne répond pas, Android bascule sur le second.\n" +
                "• La reconnexion automatique relance le VPN en cas de changement de réseau ou de connexion morte.\n" +
                "• Le trafic IPv6 n'est pas encore envoyé dans le tunnel."
        )
    }
}

@Composable
private fun GuideCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Pal.Card)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Text(body, color = Pal.Sub)
        }
    }
}
