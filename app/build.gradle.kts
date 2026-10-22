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
val resolvedVersionCode = providers.environmentVariable("SAH_VERSION_CODE").orNull
    ?.toIntOrNull()
    ?.takeIf { it > 0 }
    ?: 1
val resolvedVersionName = providers.environmentVariable("SAH_VERSION_NAME").orNull
    ?.takeIf { it.isNotBlank() }
    ?: "1.0"

android {
    namespace = "com.sah"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sah"
        minSdk = 26
        targetSdk = 34
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

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

    packaging {
        jniLibs {
            // The bundled Stockfish must land extracted (and executable) in
            // nativeLibraryDir: Android refuses exec() from app-writable dirs.
            useLegacyPackaging = true
            keepDebugSymbols.add("**/libstockfish.so")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Temporary: show the full failure message on CI, not just the exception type.
tasks.withType<Test>().configureEach {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
