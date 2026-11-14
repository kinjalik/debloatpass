import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val appId = "xyz.kinjalik.debloatpass"

// The git tag for a release must be "v" + versionName (e.g. v1.0.0): Obtainium compares them.
val appVersionName = "1.0.0"
val appVersionCode = 1

// ---- Release signing ----
// Local: ../keystore.properties (gitignored). CI: SIGNING_* environment variables.
// With neither, the release APK is built unsigned (that's what F-Droid wants anyway).

val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun signingValue(prop: String, env: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val signingStoreFile = signingValue("storeFile", "SIGNING_STORE_FILE")?.let { rootProject.file(it) }

// ---- Deeplink schemes (edit ../deeplinks.gradle.kts, not this) ----

apply(from = rootProject.file("deeplinks.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val deeplinkSchemes = (extra["deeplinkSchemes"] as List<String>).map { it.trim().lowercase() }.distinct()

run {
    val valid = Regex("^[a-z][a-z0-9+.-]*$")
    val reserved = setOf("http", "https", "content", "file", "intent", "android-app", "market")
    require(deeplinkSchemes.isNotEmpty()) { "deeplinks.gradle.kts: the list is empty" }
    deeplinkSchemes.forEach {
        require(valid.matches(it)) { "deeplinks.gradle.kts: invalid scheme \"$it\" (only a-z 0-9 + . -, starting with a letter)" }
        require(it !in reserved) { "deeplinks.gradle.kts: scheme \"$it\" is reserved, can't intercept it" }
    }
}

// A manifest overlay with just the intent-filter; AGP merges it into ImportActivity.
val deeplinkManifest = layout.buildDirectory.file("generated/deeplinks/AndroidManifest.xml").get().asFile.also { f ->
    val data = deeplinkSchemes.joinToString("\n") { """                <data android:scheme="$it" />""" }
    val xml = """
        |<?xml version="1.0" encoding="utf-8"?>
        |<!-- Generated from deeplinks.gradle.kts, do not edit. -->
        |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
        |    <application>
        |        <activity android:name="$appId.ImportActivity">
        |            <intent-filter>
        |                <action android:name="android.intent.action.VIEW" />
        |                <category android:name="android.intent.category.DEFAULT" />
        |                <category android:name="android.intent.category.BROWSABLE" />
        |$data
        |            </intent-filter>
        |        </activity>
        |    </application>
        |</manifest>
        |""".trimMargin()
    if (!f.exists() || f.readText() != xml) {
        f.parentFile.mkdirs()
        f.writeText(xml)
    }
}

android {
    namespace = appId
    compileSdk = 35

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField(
            "String[]",
            "DEEPLINK_SCHEMES",
            deeplinkSchemes.joinToString(", ", "{", "}") { "\"$it\"" },
        )
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = signingStoreFile
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
                enableV1Signing = false // minSdk 24 -> v2+ is enough
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // No build timestamps/VCS info baked in -> easier reproducible builds later.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Build-type source sets can carry an extra manifest that gets merged over main.
    buildTypes.forEach { sourceSets.getByName(it.name).manifest.srcFile(deeplinkManifest) }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.register("printVersionName") {
    val v = appVersionName
    doLast { println(v) }
}
