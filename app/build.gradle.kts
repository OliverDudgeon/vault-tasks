plugins {
    id("com.android.application")
}

val releaseKeystorePath = providers.environmentVariable("SIGNING_KEYSTORE_PATH")
    .orElse(providers.gradleProperty("signing.keystorePath"))
val releaseKeystorePassword = providers.environmentVariable("SIGNING_KEYSTORE_PASSWORD")
    .orElse(providers.gradleProperty("signing.keystorePassword"))
val releaseKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS")
    .orElse(providers.gradleProperty("signing.keyAlias"))
val releaseKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD")
    .orElse(providers.gradleProperty("signing.keyPassword"))

android {
    namespace = "com.vaulttasks"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vaulttasks"
        // RemoteViews.RemoteCollectionItems (widget list without a RemoteViewsService) is API 31+.
        minSdk = 31
        targetSdk = 37
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("0.0.1")
    }

    signingConfigs {
        if (releaseKeystorePath.isPresent) {
            create("release") {
                storeFile = file(releaseKeystorePath.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    dependenciesInfo {
        // Keep the APK free of Google Play's encrypted dependency metadata for FOSS / Obtainium builds.
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Material 3 views with Material You dynamic colour, for the app's own screens (Apache-2.0).
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}
