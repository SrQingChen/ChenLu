plugins {
    id("chenlu.android.library")
}

android {
    namespace = "io.github.srqingchen.chenlu.engine.shizuku"
}

dependencies {
    api(project(":engine:api"))
    implementation(project(":core:shizuku"))
    implementation(libs.kotlinx.coroutines.android)
}
