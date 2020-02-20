// NOTE: Precompiled script plugins are only be written in Kotlin at the moment.

plugins {
    `kotlin-dsl`
}

group = "org.gradle.sample"
version = "1.0"

repositories {
    jcenter()
    maven(url = "https://dl.bintray.com/kotlin/kotlin-eap/")
}

kotlinDslPluginOptions {
    experimentalWarning.set(false)
}
