plugins {
    alias(libs.plugins.android.application)
}

// ClaudeWatch-<claudewatch.version>.<CI build number>; the build number also orders updates.
val baseVersion = providers.gradleProperty("claudewatch.version").get()
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()

android {
    namespace = "com.vcorr.claudewatch"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vcorr.claudewatch"
        minSdk = 30
        targetSdk = 36
        versionCode = buildNumber ?: 1
        versionName = "$baseVersion.${buildNumber ?: 0}"
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
    implementation(project(":core"))
    implementation(libs.androidx.wear)
    implementation(libs.androidx.health.services)
    implementation(libs.kotlinx.coroutines.android)
}
