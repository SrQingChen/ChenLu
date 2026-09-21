plugins {
    id("chenlu.android.library")
}

android {
    namespace = "io.github.srqingchen.chenlu.service"
}

dependencies {
    api(project(":core:model"))
    api(project(":engine:api"))
    implementation(project(":core:common"))
    implementation(project(":core:shizuku"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.mlkit.text.chinese)
}
