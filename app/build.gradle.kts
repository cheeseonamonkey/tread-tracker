plugins {
    id("com.android.application")
}

// Release signing is opt-in. Never ship a build signed by an ephemeral CI debug key.
val keystorePath = providers.environmentVariable("TREAD_KEYSTORE_PATH").orNull
val keystorePassword = providers.environmentVariable("TREAD_KEYSTORE_PASSWORD").orNull
val keyAlias = providers.environmentVariable("TREAD_KEY_ALIAS").orNull
val keyPassword = providers.environmentVariable("TREAD_KEY_PASSWORD").orNull
val canSignRelease = listOf(keystorePath, keystorePassword, keyAlias, keyPassword).all { !it.isNullOrBlank() }

android {
    signingConfigs {
        if (canSignRelease) {
            create("release") {
                storeFile = file(requireNotNull(keystorePath))
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }
    namespace = "dev.treadtracker.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.treadtracker.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    // Deliberately minimal: no Material, no ConstraintLayout, no Fragments,
    // no WorkManager, no Firebase, no ad SDKs, no native libraries.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    testImplementation("junit:junit:4.13.2")
}
