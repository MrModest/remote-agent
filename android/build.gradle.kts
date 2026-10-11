// Every plugin is declared here once, so the Kotlin Gradle plugin the catalog names
// (not the older one AGP depends on) is the one both modules get.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
