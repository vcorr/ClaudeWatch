plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.vcorr.claudewatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vcorr.claudewatch"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        val apiKey = project.findProperty("CLAUDE_API_KEY") as String? ?: ""
        buildConfigField("String", "CLAUDE_API_KEY", "\"$apiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.wear)
    implementation(libs.kotlinx.coroutines.android)
}
