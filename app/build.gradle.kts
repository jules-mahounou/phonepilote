import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Config lue (par ordre de priorité) : variables d'environnement (CI) puis local.properties.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(env: String, prop: String, def: String = ""): String =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(prop) ?: def

fun str(v: String) = "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val keystoreFile = cfg("PP_KEYSTORE_FILE", "pp.keystore.file")

android {
    namespace = "bj.phonepilote.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "bj.phonepilote.app"
        minSdk = 26
        targetSdk = 35
        versionCode = cfg("PP_VERSION_CODE", "pp.versionCode", "1").toInt()
        versionName = cfg("PP_VERSION_NAME", "pp.versionName", "0.1.0")

        buildConfigField("String", "SUPABASE_URL", str(cfg("SUPABASE_URL", "supabase.url").trimEnd('/')))
        buildConfigField("String", "SUPABASE_KEY", str(cfg("SUPABASE_ANON_KEY", "supabase.anonKey")))
        // Firebase n'est utilisé que pour les notifications push (FCM). Pas de google-services.json :
        // les 4 valeurs sont injectées ici et Firebase est initialisé à la main dans PhonePiloteApp.
        buildConfigField("String", "FB_PROJECT_ID", str(cfg("FIREBASE_PROJECT_ID", "firebase.projectId")))
        buildConfigField("String", "FB_APP_ID", str(cfg("FIREBASE_APP_ID", "firebase.appId")))
        buildConfigField("String", "FB_API_KEY", str(cfg("FIREBASE_API_KEY", "firebase.apiKey")))
        buildConfigField("String", "FB_SENDER_ID", str(cfg("FIREBASE_SENDER_ID", "firebase.senderId")))
        // Domaine technique des comptes « numéro de téléphone + mot de passe ».
        buildConfigField("String", "ACCOUNT_DOMAIN", str(cfg("PP_ACCOUNT_DOMAIN", "pp.accountDomain", "phonepilote.vocta.site")))
        buildConfigField("String", "SITE_URL", str(cfg("PP_SITE_URL", "pp.siteUrl", "https://phonepilote.vocta.site")))
    }

    androidResources {
        localeFilters += listOf("fr")
    }

    signingConfigs {
        if (keystoreFile.isNotBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = cfg("PP_KEYSTORE_PASSWORD", "pp.keystore.password")
                keyAlias = cfg("PP_KEY_ALIAS", "pp.key.alias")
                keyPassword = cfg("PP_KEY_PASSWORD", "pp.key.password")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystoreFile.isNotBlank()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/**/LICENSE*",
                "META-INF/**/NOTICE*",
                "META-INF/androidx*",
                "kotlin/**",
                "DebugProbesKt.bin",
                "**/*.kotlin_builtins",
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
    }
}

dependencies {
    // UI : Compose + Material3 uniquement. Le verre (glassmorphisme) est dessiné à la main : aucune lib.
    implementation(platform("androidx.compose:compose-bom:2025.04.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Push (commandes à distance : localiser, verrouiller).
    implementation(platform("com.google.firebase:firebase-bom:33.12.0"))
    implementation("com.google.firebase:firebase-messaging")

    // Réseau : HttpURLConnection + org.json du framework Android (comme xyd).
}
