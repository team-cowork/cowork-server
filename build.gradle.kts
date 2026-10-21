plugins {
    // Load build-logic once so Boot and Kotlin share a classpath and Boot can align Kotlin dependency versions.
    id("cowork.spring-boot-conventions") apply false
}
