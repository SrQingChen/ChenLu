plugins {
    id("chenlu.android.application")
    id("chenlu.android.compose")
}

android {
    namespace = "io.github.srqingchen.chenlu.app"

    defaultConfig {
        applicationId = "io.github.srqingchen.chenlu"
        versionCode = 1
        versionName = "0.1.0-m0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":feature:home"))
    implementation(project(":service"))
    implementation(project(":engine:accessibility"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
