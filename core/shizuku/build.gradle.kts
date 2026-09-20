plugins {
    id("chenlu.android.library")
}

android {
    namespace = "io.github.srqingchen.chenlu.core.shizuku"

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(libs.shizuku.api)
    implementation(project(":core:common"))
    implementation(libs.shizuku.provider)
    implementation(libs.androidx.annotation)
    api(libs.kotlinx.coroutines.core)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
