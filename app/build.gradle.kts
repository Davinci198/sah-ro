plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFilePath = providers.environmentVariable("SAH_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("SAH_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("SAH_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("SAH_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.sah"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sah"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(checkNotNull(releaseStoreFilePath))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(files("libs/dragonbones-release.aar"))
    testImplementation("junit:junit:4.13.2")
}
