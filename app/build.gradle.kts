import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Jeton GitHub en LECTURE SEULE embarqué dans l'application (sert à lire les configs chiffrées).
// Source, dans l'ordre : propriété Gradle, variable d'environnement, fichier git_read_token.txt
// (à la racine du dépôt GitHub, ou dans le dossier du projet). Jamais le jeton d'écriture.
val gitReadToken: String = run {
    val raw = (project.findProperty("GIT_READ_TOKEN") as String?)?.trim()
        ?: System.getenv("GIT_READ_TOKEN")?.trim()
        ?: listOf(
            File(rootProject.projectDir, "git_read_token.txt"),
            File(rootProject.projectDir.parentFile, "git_read_token.txt")
        ).firstOrNull { it.exists() }?.readText()?.trim()
        ?: ""
    raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
}

// Adresse du serveur d'inscription (publique, pas un secret). Source : propriété Gradle, variable d'environnement
// SERVER_URL, ou fichier server_url.txt à la racine du dépôt.
val serverUrl: String = run {
    val raw = (project.findProperty("SERVER_URL") as String?)?.trim()
        ?: System.getenv("SERVER_URL")?.trim()
        ?: listOf(
            File(rootProject.projectDir, "server_url.txt"),
            File(rootProject.projectDir.parentFile, "server_url.txt")
        ).firstOrNull { it.exists() }?.readText()?.trim()
        ?: ""
    raw.filter { it.isLetterOrDigit() || it in ":/.-_" }
}

android {
    namespace = "com.devsdylan.dylanvpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.devsdylan.dylanvpn"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.3.4"
        buildConfigField("long", "BUILD_TIME", System.currentTimeMillis().toString() + "L")
        buildConfigField("String", "GIT_READ_TOKEN", "\"" + gitReadToken + "\"")
        buildConfigField("String", "SERVER_URL", "\"" + serverUrl + "\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    signingConfigs {
        create("dylan") {
            // Clé fixe : indispensable pour que la mise à jour depuis l'application s'installe par-dessus la version précédente.
            // Par défaut : dylan.keystore du projet. Pour ne plus dépendre d'une clé publique, définis les variables
            // d'environnement DYLAN_KEYSTORE_FILE / DYLAN_KEYSTORE_PASSWORD / DYLAN_KEY_ALIAS / DYLAN_KEY_PASSWORD
            // (le workflow GitHub les remplit depuis les secrets du dépôt, voir README).
            val envStore = System.getenv("DYLAN_KEYSTORE_FILE")
            storeFile = if (!envStore.isNullOrBlank()) file(envStore) else file("dylan.keystore")
            storePassword = System.getenv("DYLAN_KEYSTORE_PASSWORD") ?: "dylanvpn"
            keyAlias = System.getenv("DYLAN_KEY_ALIAS") ?: "dylan"
            keyPassword = System.getenv("DYLAN_KEY_PASSWORD") ?: "dylanvpn"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dylan")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

// Télécharge automatiquement le core Xray s'il manque (nécessite Internet, ~60 Mo).
// Le fichier est vérifié par son empreinte SHA-256 : un AAR modifié ou corrompu fait échouer le build.
val libv2rayFile = layout.projectDirectory.file("libs/libv2ray.aar").asFile
val libv2rayUrl = "https://github.com/2dust/AndroidLibXrayLite/releases/download/v26.10.21/libv2ray.aar"
val libv2raySha256 = "e56ec3b06485fe7b37af26948673951edf719c50d6bbc618b8539045952adde4"
val downloadLibv2ray by tasks.registering {
    outputs.file(libv2rayFile)
    onlyIf { !libv2rayFile.exists() }
    doLast {
        libv2rayFile.parentFile.mkdirs()
        val part = File(libv2rayFile.parentFile, "libv2ray.aar.part")
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = uri(libv2rayUrl).toURL().openConnection().apply {
            connectTimeout = 20_000
            readTimeout = 60_000
        }
        connection.getInputStream().use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    output.write(buffer, 0, n)
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != libv2raySha256) {
            part.delete()
            throw GradleException("libv2ray.aar : empreinte SHA-256 inattendue ($actual)")
        }
        if (!part.renameTo(libv2rayFile)) throw GradleException("Impossible d'enregistrer libv2ray.aar")
    }
}
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadLibv2ray)
}

dependencies {
    // Core Xray (AndroidLibXrayLite). Le fichier est téléchargé par le workflow GitHub Actions
    // ou à la main : voir le README.
    implementation(files("libs/libv2ray.aar"))
    // Génération des QR codes (partage de config, Wi-Fi du point d'accès)
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
}
