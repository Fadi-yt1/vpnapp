import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.tervyn.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.tervyn.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    // Xray-core is bundled as libxray.so: Android only lets apps run programs from their native-library folder.
    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("xray/jniLibs"))
    packaging {
        jniLibs {
            useLegacyPackaging = true   // extract libxray.so to disk so it can be started
        }
    }

    // One small APK per phone type, plus a universal one that works everywhere.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the APK installs right away.
            // Use your own key before publishing on Google Play.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // Report problems without failing the build; the report is uploaded by CI.
        abortOnError = false
        textReport = true
        textOutput = file("build/reports/lint.txt")
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}

// ---------------------------------------------------------------------------
// Xray-core: download the official release for each phone type and check it
// against the release's SHA-256 digest. -PrequireXray=true makes a failed
// download stop the build (CI uses it); otherwise the app is built without
// Xray and VLESS / VMess / Trojan / Shadowsocks servers are disabled.
// ---------------------------------------------------------------------------
val xrayVersion = "v26.9.30"
val xrayAssets = mapOf(
    "arm64-v8a" to "android-arm64-v8a",
    "armeabi-v7a" to "linux-arm32-v7a",
    "x86_64" to "linux-64",
    "x86" to "linux-32",
)
val xrayOut = layout.buildDirectory.dir("xray/jniLibs")
val requireXray = providers.gradleProperty("requireXray").map { it.toBoolean() }.orElse(false)

val downloadXray by tasks.registering {
    description = "Downloads Xray-core $xrayVersion and bundles it as libxray.so"
    inputs.property("version", xrayVersion)
    outputs.dir(xrayOut)
    val required = requireXray
    doLast {
        for ((abi, asset) in xrayAssets) {
            val target = xrayOut.get().file("$abi/libxray.so").asFile
            if (target.exists()) continue
            val url = "https://github.com/XTLS/Xray-core/releases/download/$xrayVersion/Xray-$asset.zip"
            try {
                val zip = URI(url).toURL().openStream().use { it.readBytes() }
                val dgst = URI("$url.dgst").toURL().openStream().use { it.readBytes().toString(Charsets.UTF_8) }
                val expected = Regex("(?i)SHA2?-?256\\s*=\\s*([0-9a-f]{64})").find(dgst)?.groupValues?.get(1)
                    ?: throw GradleException("No SHA-256 in $url.dgst")
                val actual = MessageDigest.getInstance("SHA-256").digest(zip).joinToString("") { "%02x".format(it) }
                if (!actual.equals(expected, ignoreCase = true)) throw GradleException("Checksum mismatch for $url")
                ZipInputStream(zip.inputStream()).use { z ->
                    var e = z.nextEntry
                    while (e != null && e.name != "xray") e = z.nextEntry
                    if (e == null) throw GradleException("No xray binary in $url")
                    target.parentFile.mkdirs()
                    target.outputStream().use { z.copyTo(it) }
                }
                logger.lifecycle("Xray $xrayVersion for $abi: ${target.length() / 1024} KB")
            } catch (e: Exception) {
                target.delete()
                if (required.get()) throw GradleException("Could not download Xray for $abi: ${e.message}", e)
                logger.warn("Could not download Xray for $abi (${e.message}). Building without Xray for this phone type.")
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadXray) }
