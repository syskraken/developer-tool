import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Signing credentials come from the environment in CI, or from a local
 * keystore.properties for desktop builds. Neither the keystore nor its
 * passwords are ever committed — see .gitignore.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("KEYSTORE_FILE", "storeFile")
val releaseStorePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias = signingValue("KEY_ALIAS", "keyAlias")
val releaseKeyPassword = signingValue("KEY_PASSWORD", "keyPassword")

// Without every credential present the release build stays unsigned rather than
// failing, so `assembleRelease` still works for anyone without the keystore.
val canSignRelease = releaseStoreFile != null &&
    releaseStorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "dev.franklin.devbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.franklin.devbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // v1 covers Android 6 and below, v2/v3 everything after.
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    val releaseSigningConfig = if (canSignRelease) signingConfigs.getByName("release") else null

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = releaseSigningConfig
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}

/** Fails the release build loudly rather than shipping an unsigned APK from CI. */
tasks.register("requireReleaseSigning") {
    doLast {
        if (!canSignRelease) {
            throw GradleException(
                "Release signing is not configured. Set KEYSTORE_FILE, KEYSTORE_PASSWORD, " +
                    "KEY_ALIAS and KEY_PASSWORD, or create keystore.properties.",
            )
        }
    }
}
