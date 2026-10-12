import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "com.tavi.novelreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.tavi.novelreader"
        minSdk = 26
        targetSdk = 37
        versionCode = 13
        versionName = "2.0.0"
        // ML Kit 翻译带原生库，只打包真机架构以控制 APK 体积
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    // 发布签名：keystore.properties（或 MOYUE_SIGNING_PROPERTIES 指定的路径）或环境变量；都没有则产出未签名 release
    val signProps = Properties().apply {
        val f = (System.getenv("MOYUE_SIGNING_PROPERTIES") ?: findProperty("moyue.signing.properties") as String?)
            ?.let { file(it) } ?: rootProject.file("keystore.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    fun sign(key: String, env: String): String? = System.getenv(env) ?: signProps.getProperty(key)
    val storePath = sign("storeFile", "MOYUE_KEYSTORE_PATH")
    signingConfigs {
        if (storePath != null && file(storePath).isFile) create("release") {
            storeFile = file(storePath)
            storePassword = sign("storePassword", "MOYUE_STORE_PASSWORD")
            keyAlias = sign("keyAlias", "MOYUE_KEY_ALIAS") ?: "upload"
            keyPassword = sign("keyPassword", "MOYUE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED", "-Xmx3g")
                if (project.hasProperty("bench")) { it.systemProperty("bench", "1"); it.testLogging.showStandardStreams = true }
            }
        }
    }
}

// release 变体也跑解析类单元测试（截图测试仍只在 debug 跑）
androidComponents {
    beforeVariants(selector().withBuildType("release")) { v ->
        v.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("androidx.media3:media3-common:1.11.1")
    implementation("androidx.compose.material3.adaptive:adaptive:1.3.0")
    implementation("androidx.window:window:1.5.1")
    // 2.0：划词翻译（端侧离线模型）、PDF 文字层、桌面小组件
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // 截图测试（Robolectric + Roborazzi）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
