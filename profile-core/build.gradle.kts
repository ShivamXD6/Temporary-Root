plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Vendored GhostLock profile compiler: HOCON -> GLK1 binary. Pure JVM, no
// Android APIs, so it can also be run from a host test.
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// The app compiles at Java 11; keep this module's bytecode at the same level.
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation("com.typesafe:config:1.4.3")
    testImplementation("junit:junit:4.13.2")
}
