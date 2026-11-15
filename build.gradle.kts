plugins {
    // AGP 9+ compiles Kotlin itself (built-in Kotlin), no org.jetbrains.kotlin.android needed.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ktlint) apply false
}
