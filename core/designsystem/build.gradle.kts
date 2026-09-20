plugins {
    id("chenlu.android.library")
    id("chenlu.android.compose")
}

android {
    namespace = "io.github.srqingchen.chenlu.core.designsystem"
}

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.core)
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
