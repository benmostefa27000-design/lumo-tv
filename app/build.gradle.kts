import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val lumoProps = Properties().apply {
    val f = rootProject.file("lumo.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val syncUrl: String = (System.getenv("LUMO_SYNC_URL") ?: lumoProps.getProperty("syncUrl", "")).trim().trimEnd('/')
val repoName: String = System.getenv("GITHUB_REPOSITORY") ?: lumoProps.getProperty("repo", "")
val buildNumber: Int = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.lumo.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lumo.tv"
        minSdk = 22
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "SYNC_URL", "\"$syncUrl\"")
        buildConfigField("String", "GITHUB_REPO", "\"$repoName\"")
    }

    signingConfigs {
        create("lumo") {
            storeFile = file("lumo.keystore")
            storePassword = "lumotv2026"
            keyAlias = "lumo"
            keyPassword = "lumotv2026"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lumo")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lumo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
}
