plugins {
    id("com.android.application")
}

android {
    namespace = "com.vaulttasks"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vaulttasks"
        // RemoteViews.RemoteCollectionItems (widget list without a RemoteViewsService) is API 31+.
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.0.1"
    }
}

dependencies {
    // Material 3 views with Material You dynamic colour, for the app's own screens (Apache-2.0).
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}
