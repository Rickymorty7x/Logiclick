plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.logiclick.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.logiclick.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"
        buildConfigField("String", "DEFAULT_API_URL", "\"http://100.93.193.74:8765\"")
        buildConfigField("String", "DEFAULT_PUBLIC_KEY", "\"MCowBQYDK2VwAyEAxV7g7UKG5SI9IhK58Z8UIfIQAMokhsJ9hx8NQpYK/0o=\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
