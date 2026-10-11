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
        versionCode = 9
        versionName = "1.7.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
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

    // 截图测试（Robolectric + Roborazzi）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.76.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.76.0")
    testImplementation(bom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
