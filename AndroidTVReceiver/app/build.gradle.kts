plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val signingPath = providers.environmentVariable("MIRRORSHARE_KEYSTORE_PATH").orNull
val signingPassword = providers.environmentVariable("MIRRORSHARE_KEYSTORE_PASSWORD").orNull
val signingAlias = providers.environmentVariable("MIRRORSHARE_KEY_ALIAS").orElse("mirrorshare").get()

android {
    namespace = "com.mirrorshare.receiver"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mirrorshare.receiver"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    if (!signingPath.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        signingConfigs {
            create("mirrorShareRelease") {
                storeFile = file(signingPath)
                storePassword = signingPassword
                keyAlias = signingAlias
                keyPassword = signingPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("mirrorShareRelease")
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

val verifyReleaseSigning by tasks.registering {
    doLast {
        if (signingPath.isNullOrBlank() || signingPassword.isNullOrBlank()) {
            throw GradleException("Release signing is not configured. Run Build-MirrorShare.ps1 from the source root.")
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
