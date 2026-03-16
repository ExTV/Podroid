/*
 * VirtuDroid — Top-level build file
 *
 * Do not place application-specific dependencies here; they belong in the
 * app-level build.gradle.kts.
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.ksp) apply false
    alias(libs.plugins.hilt.android) apply false
}
