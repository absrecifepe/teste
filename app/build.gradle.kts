import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Assinatura de release: lida de keystore.properties (local) ou de variáveis de ambiente (CI).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "KEYSTORE_PATH")

// AdMob: IDs de TESTE do Google (seguros para desenvolvimento). Para o release, defina
// ADMOB_APP_ID e ADMOB_INTERSTITIAL_ID em keystore.properties ou como variáveis de ambiente.
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testInterstitialId = "ca-app-pub-3940256099942544/1033173712"
// ID real do app no AdMob (não é segredo: fica visível dentro do APK publicado).
val releaseAdmobAppId = signingValue("admobAppId", "ADMOB_APP_ID") ?: "ca-app-pub-3972359571836563~5329846042"
val releaseInterstitialId = signingValue("admobInterstitialId", "ADMOB_INTERSTITIAL_ID") ?: testInterstitialId
if (releaseInterstitialId == testInterstitialId) {
    logger.warn("AdMob: release usando bloco intersticial de TESTE (sem receita). Configure ADMOB_INTERSTITIAL_ID.")
}

android {
    namespace = "com.asciitype.app"
    compileSdk = 36

    defaultConfig {
        // ATENÇÃO: o applicationId é permanente na Play Store. Troque antes de publicar.
        applicationId = "com.asciitype.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["admobAppId"] = testAdmobAppId
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$testInterstitialId\"")
        }
        release {
            manifestPlaceholders["admobAppId"] = releaseAdmobAppId
            buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$releaseInterstitialId\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.google.android.gms:play-services-ads:24.9.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")
}
