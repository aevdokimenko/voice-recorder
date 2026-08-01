plugins {
    alias(libs.plugins.android).apply(false)
    alias(libs.plugins.ksp).apply(false)
    alias(libs.plugins.detekt).apply(false)
    // required by the vendored :commons module
    alias(libs.plugins.library).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.kotlinSerialization).apply(false)
    alias(libs.plugins.parcelize).apply(false)
}
