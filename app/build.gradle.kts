import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息从 keystore.properties 读取（该文件与 keystore.jks 均已在 .gitignore 中排除）。
// 文件不存在时 release 产出未签名 APK，仍可正常构建，方便他人克隆后直接编译。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasSigningConfig = keystoreProps.getProperty("storePassword") != null &&
    keystoreProps.getProperty("keyAlias") != null

android {
    namespace = "com.gotify.lite"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gotify.lite"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "3.8"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile", "keystore.jks"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

/**
 * 出包自检：CHANGELOG.md 里必须有当前 versionName 的小节。
 *
 * 目的只有一个——**不许忘记升版本**。升了版本却没写变更、或者改了代码却没升版本，
 * 都会在 assembleRelease 时直接失败，而不是产出一个“装了两份但分不清先后”的包。
 * 升版本用 tools/bump-version.sh。
 */
val verifyVersion = tasks.register("verifyVersion") {
    val changelogFile = rootProject.file("CHANGELOG.md")
    val currentVersion = android.defaultConfig.versionName ?: ""
    doLast {
        if (!changelogFile.exists()) {
            throw GradleException("缺少 CHANGELOG.md，无法判断版本是否已登记")
        }
        val text = changelogFile.readText()
        if (!text.contains("## $currentVersion")) {
            throw GradleException(
                "CHANGELOG.md 里没有 $currentVersion 的条目：" +
                    "请先运行 tools/bump-version.sh 升版本，并补写这一版的变更记录"
            )
        }
        logger.lifecycle("版本自检通过：$currentVersion（versionCode ${android.defaultConfig.versionCode}）")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(verifyVersion) }

dependencies {
    // 唯一第三方依赖：WebSocket + HTTP。无 androidx / play-services / 图片加载 / markdown 等重库。
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    // 单元测试里模拟 Android 平台自带的 org.json
    testImplementation("org.json:json:20240303")
}
