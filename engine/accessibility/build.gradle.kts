plugins {
    id("chenlu.android.library")
}

android {
    namespace = "io.github.srqingchen.chenlu.engine.accessibility"
}

dependencies {
    api(project(":engine:api"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
}
