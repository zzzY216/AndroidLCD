plugins {
    alias(libs.plugins.android.application)
    // 注意：AGP 9 已内置 Kotlin 支持，不能再显式应用 kotlin-android，
    // 否则报 "Cannot add extension with name 'kotlin'"（扩展重复注册）。
    alias(libs.plugins.kotlin.compose)
    // Hilt：需要 gradle.properties 里的 android.disallowKotlinSourceSets=false
    // 否则 KSP/Hilt 使用 kotlin.sourceSets 时会被 AGP 9 内置 Kotlin 拒绝。
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.loostone.olcd"
    // 注意：androidx.core 1.19.1 和 lifecycle 2.11.0 要求
    // compileSdk >= 37，用 36 会报 AAR metadata 检查失败。
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.loostone.olcd"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        // AGP 9 + Kotlin 2.2 需要 Java 17
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Hilt 依赖注入
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // 协程
    implementation(libs.kotlinx.coroutines.android)

    // 权限请求
    implementation(libs.accompanist.permissions)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
