plugins {
    id("chenlu.android.library")
}

android {
    namespace = "io.github.srqingchen.chenlu.core.data"
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
}
