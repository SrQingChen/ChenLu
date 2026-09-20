plugins {
    id("chenlu.android.application")
    id("chenlu.android.compose")
}

android {
    namespace = "io.github.srqingchen.chenlu.app"

    defaultConfig {
        applicationId = "io.github.srqingchen.chenlu"
        versionCode = 2
        versionName = "0.2.0-diag"
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

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(project(":feature:home"))
    implementation(project(":service"))
    implementation(project(":engine:accessibility"))
    implementation(project(":engine:shizuku"))
    implementation(project(":core:shizuku"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
