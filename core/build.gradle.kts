// Plain Kotlin, no Android, so its logic is unit-tested on the JVM in CI. The plugin has no version
// here because the root buildscript already puts the Kotlin Gradle plugin on the classpath.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
}
