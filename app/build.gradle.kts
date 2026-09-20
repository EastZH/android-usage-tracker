import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// 发布签名从仓库外的 keystore.properties 读（该文件已 gitignore）。
// 没有这个文件时退回 debug 签名 —— 这样别人 clone 下来不配任何东西也能构建出可安装的包，
// 只是签名不同、无法与官方发布版互相覆盖安装。
//
// 注意：这几行必须放在 plugins {} **之后** —— Gradle 要求 plugins 块是脚本里的第一个语句。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystorePropsFile.exists()

android {
    namespace = "com.east.time"

    // 本机 SDK 只装了 android-36，所以 compileSdk 只能是 36
    compileSdk = 36

    defaultConfig {
        applicationId = "com.east.time"
        // UsageEvents.Event.getInstanceId() / getTaskRootPackageName()
        // 与 UsageStats.getTotalTimeVisible() / getAppLaunchCount() 都是 API 29 起，
        // 定在 29 可以省掉一堆 Build.VERSION 分支
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 不开混淆：Room 等库的 keep 规则虽然自带，但为了一个个人工具不值得冒
            // 上线后才发现某处被混淆掉的风险
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // 推导逻辑是纯函数，在 JVM 上直接跑测试，不需要设备
    testImplementation("junit:junit:4.13.2")
}
