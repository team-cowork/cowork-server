plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    // Keep Boot and Kotlin on the same classpath so Boot can align Kotlin dependency versions.
    id("cowork.kotlin-conventions") apply false
}
