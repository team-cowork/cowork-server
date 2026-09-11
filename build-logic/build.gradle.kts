import org.gradle.plugin.use.PluginDependency

plugins {
    `kotlin-dsl`
}

// Precompiled convention plugins resolve external plugins through their marker artifacts.
fun Provider<PluginDependency>.marker() = map {
    "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}"
}

dependencies {
    implementation(libs.plugins.kotlin.jvm.marker())
    implementation(libs.plugins.kotlin.spring.marker())
    implementation(libs.plugins.kotlin.jpa.marker())
    implementation(libs.plugins.ktlint.marker())
}
