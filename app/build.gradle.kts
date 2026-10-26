import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from keystore.properties (git-ignored) or PHONEMCP_* environment variables.
// Without either, release builds use the standard Android debug key so a fresh clone still builds.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)
val releaseKeystore: File? = signingValue("storeFile", "PHONEMCP_STORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }
if (releaseKeystore == null) logger.warn("Phone MCP: no release keystore configured; release builds are signed with the debug key.")

android {
    namespace = "app.phonemcp"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.phonemcp"
        minSdk = 30
        targetSdk = 36
        versionCode = 7
        versionName = "1.2.1"
        // cloudflared is only bundled for arm64 (every current phone).
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = signingValue("storePassword", "PHONEMCP_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "PHONEMCP_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "PHONEMCP_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }

    packaging {
        // cloudflared must exist as a real file in nativeLibraryDir so it can be executed.
        jniLibs { useLegacyPackaging = true }
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
    lint { abortOnError = false }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.09.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// The bundled cloudflared must be the official release documented in THIRD_PARTY_NOTICES.md.
val CLOUDFLARED_SHA256 = "2b7203b4fb0ba28a0c1e12f8855ce2c25f76d266a8d5edafac5540b6812acff6"
val verifyCloudflared by tasks.registering {
    val bin = file("src/main/jniLibs/arm64-v8a/libcloudflared.so")
    inputs.file(bin)
    doLast {
        val digest = MessageDigest.getInstance("SHA-256")
        bin.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == CLOUDFLARED_SHA256) { "libcloudflared.so SHA-256 is $actual, expected $CLOUDFLARED_SHA256 (see THIRD_PARTY_NOTICES.md)" }
    }
}
tasks.named("preBuild") { dependsOn(verifyCloudflared) }
