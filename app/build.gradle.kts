import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Versioning: single source of truth is version.properties in the project root
val ver = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val vMajor = ver.getProperty("VERSION_MAJOR").toInt()
val vMinor = ver.getProperty("VERSION_MINOR").toInt()
val vPatch = ver.getProperty("VERSION_PATCH").toInt()

android {
    namespace = "app.simgate"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.ismsgw"
        minSdk = 26 // Android 8.0+
        targetSdk = 36
        versionCode = vMajor * 10000 + vMinor * 100 + vPatch
        versionName = "$vMajor.$vMinor.$vPatch"
    }

    // The upload key is kept outside the sources: keystore.properties (in .gitignore)
    val ksProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            if (ksProps.isNotEmpty()) {
                storeFile = rootProject.file(ksProps.getProperty("storeFile"))
                storePassword = ksProps.getProperty("storePassword")
                keyAlias = ksProps.getProperty("keyAlias")
                keyPassword = ksProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

// Output file names: iSMS-GW-1.0.0-release.apk / .aab
base { archivesName.set("iSMS-GW-$vMajor.$vMinor.$vPatch") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
