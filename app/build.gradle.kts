import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
}

val appId = "xyz.kinjalik.debloatpass"

// The version lives in ../version.txt; release-please bumps it in its release PR, and the
// release tag is "v" + versionName (e.g. v1.2.3), which is what Obtainium compares.
val appVersionName = rootProject.file("version.txt").readText().trim()

// versionCode is derived so it can never be forgotten: 1.2.3 -> 10203 (minor/patch < 100).
val appVersionCode = run {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)$""").matchEntire(appVersionName)
        ?: error("version.txt: \"$appVersionName\" is not MAJOR.MINOR.PATCH")
    val (major, minor, patch) = m.destructured.toList().map { it.toInt() }
    require(minor < 100 && patch < 100) { "version.txt: minor and patch must be < 100" }
    major * 10_000 + minor * 100 + patch
}

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
        require(valid.matches(it)) {
            "deeplinks.gradle.kts: invalid scheme \"$it\" (only a-z 0-9 + . -, starting with a letter)"
        }
        require(it !in reserved) { "deeplinks.gradle.kts: scheme \"$it\" is reserved, can't intercept it" }
    }
}

/**
 * Writes a manifest overlay with just the deeplink intent-filter; AGP merges it into
 * ImportActivity. A real task (not code run while Gradle reads this file), so `clean` can't
 * delete it from under the build. The debug variant additionally lets instrumented tests talk to
 * a cleartext server on localhost (src/debug/res/xml/network_security_config.xml) and see the
 * test APK's fake wallet even while it is disabled (WalletAppsTest); release gets neither.
 */
abstract class DeeplinkManifestTask : DefaultTask() {
    @get:Input abstract val appId: Property<String>

    @get:Input abstract val schemes: ListProperty<String>

    @get:Input abstract val debug: Property<Boolean>

    @get:OutputFile abstract val manifest: RegularFileProperty

    @TaskAction
    fun write() {
        val data = schemes.get().joinToString("\n") { """                <data android:scheme="$it" />""" }
        val testPkg = "${appId.get()}.test"
        val queries = if (debug.get()) """    <queries><package android:name="$testPkg" /></queries>""" else ""
        val appAttrs = if (debug.get()) """ android:networkSecurityConfig="@xml/network_security_config"""" else ""
        manifest.get().asFile.writeText(
            """
            |<?xml version="1.0" encoding="utf-8"?>
            |<!-- Generated from deeplinks.gradle.kts, do not edit. -->
            |<manifest xmlns:android="http://schemas.android.com/apk/res/android">
            |$queries
            |    <application$appAttrs>
            |        <activity android:name="${appId.get()}.ImportActivity" android:exported="true">
            |            <intent-filter>
            |                <action android:name="android.intent.action.VIEW" />
            |                <category android:name="android.intent.category.DEFAULT" />
            |                <category android:name="android.intent.category.BROWSABLE" />
            |$data
            |            </intent-filter>
            |        </activity>
            |    </application>
            |</manifest>
            |
            """.trimMargin(),
        )
    }
}

/** Fails the build if the merged manifest lost any deeplink scheme (e.g. the overlay went missing). */
abstract class VerifyDeeplinkManifestTask : DefaultTask() {
    @get:InputFile abstract val mergedManifest: RegularFileProperty

    @get:Input abstract val schemes: ListProperty<String>

    @get:OutputFile abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val xml = mergedManifest.get().asFile.readText()
        val missing = schemes.get().filter { """android:scheme="$it"""" !in xml }
        if (missing.isNotEmpty()) {
            throw GradleException("Merged manifest has no deeplink filter for: ${missing.joinToString()}")
        }
        marker.get().asFile.writeText("ok: ${schemes.get().joinToString()}\n")
    }
}

val applicationIdForTasks = appId // the task's own `appId` property shadows the script's

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val generate = tasks.register<DeeplinkManifestTask>("generate${name}DeeplinkManifest") {
            appId.set(applicationIdForTasks)
            schemes.set(deeplinkSchemes)
            debug.set(variant.buildType == "debug")
        }
        variant.sources.manifests.addGeneratedManifestFile(generate, DeeplinkManifestTask::manifest)

        val verify = tasks.register<VerifyDeeplinkManifestTask>("verify${name}DeeplinkManifest") {
            mergedManifest.set(variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST))
            schemes.set(deeplinkSchemes)
            marker.set(layout.buildDirectory.file("intermediates/verifyDeeplinkManifest/${variant.name}.txt"))
        }
        // Every build of the variant checks it, not only CI.
        tasks.matching { it.name == "assemble$name" || it.name == "check" }.configureEach { dependsOn(verify) }
    }
}

android {
    namespace = appId
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String[]",
            "DEEPLINK_SCHEMES",
            deeplinkSchemes.joinToString(", ", "{", "}") { "\"$it\"" },
        )
    }

    buildFeatures {
        buildConfig = true
    }

    // Per-app language picker on Android 13+ (en default, ru); locales come from res/values-*.
    androidResources {
        generateLocaleConfig = true
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
            // No git commit hash in META-INF: the APK stays byte-identical even when built
            // from a source tarball without .git (reproducible builds).
            vcsInfo.include = false
        }
    }

    lint {
        abortOnError = true // errors fail CI; warnings are only reported
        // Version bumps are Dependabot's job (and targetSdk is bumped deliberately, not by lint).
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        sarifReport = true
    }

    // Kotlin metadata only kotlin-reflect needs; ~40 KB of a ~45 KB APK otherwise.
    packaging {
        resources.excludes += "kotlin/**.kotlin_builtins"
    }

    // No build timestamps/VCS info baked in -> easier reproducible builds later.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Test helpers (tiny HTTP server, pkpass fixtures) used by both unit and instrumented tests.
    // (AGP 9 built-in Kotlin only picks up Kotlin from `kotlin.directories`, not `java.srcDir`.)
    sourceSets.getByName("test").kotlin.directories += "src/sharedTest/java"
    sourceSets.getByName("androidTest").kotlin.directories += "src/sharedTest/java"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Test-only dependencies; the app itself has none.
dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric) // real android.net.Uri for PassLink
    testImplementation(libs.orgjson) // android.jar only has org.json stubs

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
}

tasks.register("printVersionName") {
    val v = appVersionName
    doLast { println(v) }
}

// Kotlin code style (android_studio, see .editorconfig). `./gradlew ktlintFormat` fixes most of it.
ktlint {
    version.set(libs.versions.ktlint.cli)
    android.set(true)
}
