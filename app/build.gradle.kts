plugins {
    id("com.android.application")
}

val automaticBuildNumber = (System.currentTimeMillis() / 1000L).toInt()
val releaseKeystorePath = providers.environmentVariable("PLAYFETCH_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("PLAYFETCH_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("PLAYFETCH_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("PLAYFETCH_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.vibe.playfetch"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vibe.playfetch"
        minSdk = 26
        targetSdk = 37
        versionCode = automaticBuildNumber
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(checkNotNull(releaseKeystorePath))
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.auroraoss:gplayapi:3.6.4")
    implementation("com.android.tools.build:apksig:9.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
