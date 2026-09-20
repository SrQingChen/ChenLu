plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "chenlu.android.application"
            implementationClass = "io.github.srqingchen.chenlu.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "chenlu.android.library"
            implementationClass = "io.github.srqingchen.chenlu.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "chenlu.android.compose"
            implementationClass = "io.github.srqingchen.chenlu.buildlogic.AndroidComposeConventionPlugin"
        }
    }
}
