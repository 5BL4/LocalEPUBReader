// Top-level build file — plugins declared with apply false; applied per-module.
// NOTE: org.jetbrains.kotlin.android is NOT applied — AGP 9.0 has built-in Kotlin
// support (https://kotl.in/gradle/agp-built-in-kotlin). The kotlin-compose and
// kotlin-serialization compiler plugins still apply explicitly.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
