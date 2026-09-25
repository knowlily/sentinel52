import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

/**
 * release 的签名配置。密钥只放在仓库根的 `keystore.properties`（不进版本库），
 * 没有这个文件时退回 debug 签名——这样 clone 下来也能直接 `assembleRelease` 出可安装的包，
 * 不会卡在一个「配置缺失」的报错上。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKey = keystoreProperties.getProperty("storeFile") != null

android {
    // 代码里的包结构（namespace）与安装到手机上的包名（applicationId）是两件事：
    // 这里只改后者——`pm list packages`、桌面标签、Provider authority 都跟着它走。
    namespace = "com.fiftytwo.sentinel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.miui.calculator"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            // 路径相对**仓库根**（不是 app/ 模块目录）
            storeFile = rootProject.file(
                keystoreProperties.getProperty("storeFile") ?: "keystore/sentinel-release.jks",
            )
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("没找到 keystore.properties，release 用 debug 签名（仅供本机测试）")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        // 与 Dhizuku 服务进程通信要用我们自己的 AIDL（src/main/aidl）
        aidl = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
            )
        }
    }

    testOptions {
        unitTests {
            // android.jar 桩返回默认值：判定逻辑一律放在 core/ 下的纯 Kotlin 里（有单测），
            // 只有 shizuku/、service/、ui/ 这些薄包装层碰 Android API。
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        checkDependencies = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // 特权能力来自 Stellar（Shizuku 分支，见仓库根 INTEGRATION_GUIDE.md）
    implementation(libs.stellar.api)
    // 设备所有者能力来自 Dhizuku（它自己是 DO，把管理权限借给应用）
    implementation(libs.dhizuku.api)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
