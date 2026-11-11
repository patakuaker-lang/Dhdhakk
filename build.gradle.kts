plugins {
    id("com.android.application") version "8.1.1"
    id("org.jetbrains.kotlin.android") version "1.9.0"
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "com.elixir.tracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.elixir.tracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
}
