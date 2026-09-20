plugins {
    id("chenlu.android.library")
    id("chenlu.android.compose")
}

android {
    namespace = "io.github.srqingchen.chenlu.feature.home"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:shizuku"))
    implementation(project(":engine:api"))
    implementation(project(":service"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
}
