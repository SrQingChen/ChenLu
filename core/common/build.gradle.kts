plugins {
    id("chenlu.android.library")
}

android {
    namespace = "io.github.srqingchen.chenlu.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}
