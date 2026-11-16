plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val targetSdkOverride = providers.gradleProperty("javahost.targetSdk").orNull?.toInt() ?: 28

android {
    namespace = "dev.javahost"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.javahost"
        minSdk = 26
        // See gradle.properties: API 28 is intentional so the downloaded JRE can
        // be exec()'d from app-private storage on Android 10+ (W^X).
        targetSdk = targetSdkOverride
        versionCode = 12
        versionName = "1.4.1"

        vectorDrawables { useSupportLibrary = true }

        buildConfigField("boolean", "ENABLE_SILENT_MEDIA_HACK", "true")
        buildConfigField(
            "String",
            "TERMUX_PRIMARY_MIRROR",
            "\"https://packages.termux.dev/apt/termux-main\""
        )
        buildConfigField(
            "String",
            "TERMUX_FALLBACK_MIRROR",
            "\"https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main\""
        )
        buildConfigField(
            "String",
            "MODRINTH_USER_AGENT",
            "\"JavaHost/1.1.6 (Android; general-purpose Java server host)\""
        )
        buildConfigField("int", "DEFAULT_JRE_MAJOR", "21")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
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
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Release builds on this project are sideloaded; keep lint as a report
        // rather than a hard gate so CI images without all SDK bits still build.
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
