plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "local.boseqcultra2.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "local.boseqcultra2.android"
        // 31 = Android 12: permiso BLUETOOTH_CONNECT sin los permisos de ubicación antiguos.
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // Firma de la release. Los datos van en ~/.gradle/gradle.properties (fuera del repo):
    //   BOSE_RELEASE_STORE_FILE, BOSE_RELEASE_STORE_PASSWORD, BOSE_RELEASE_KEY_ALIAS, BOSE_RELEASE_KEY_PASSWORD
    // Sin ellos, la release se genera sin firmar (cualquiera puede compilar el proyecto).
    val releaseStoreFile = providers.gradleProperty("BOSE_RELEASE_STORE_FILE").orNull
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("BOSE_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("BOSE_RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("BOSE_RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // R8: elimina código y recursos no usados y ofusca. No hay reflexión en la app.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Solo AndroidX (y JUnit para las pruebas JVM).
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")

    testImplementation("junit:junit:4.13.2")
}
