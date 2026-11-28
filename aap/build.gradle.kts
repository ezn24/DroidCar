// The Android Auto Projection protocol, phone side: framing, TLS, control channel and service channels.
// Ported from AARE (https://github.com/prototux/AARE, GPL-3.0), protocol buffers from aasdk (GPL-3.0-or-later,
// see src/main/proto/NOTICE.md). Pure JVM so it can be unit-tested against a simulated head unit.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.google.protobuf") version "0.9.4"
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.36.2" }
    generateProtoTasks {
        all().configureEach {
            builtins {
                named("java") { option("lite") }
            }
        }
    }
}

dependencies {
    api("com.google.protobuf:protobuf-javalite:4.36.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
