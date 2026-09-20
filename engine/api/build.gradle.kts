plugins {
    id("chenlu.android.library")
}

android {
    namespace = "io.github.srqingchen.chenlu.engine.api"
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
}
