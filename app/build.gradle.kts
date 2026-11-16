plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xiaojiwei.memao"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.xiaojiwei.memao"
        minSdk = 34
        targetSdk = 37
        versionCode = 28
        versionName = "0.1.27"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Release signing stays off unless the CI keystore and passwords are present,
// so local builds still configure without those environment variables.
run {
    val keystoreFile = System.getenv("MEMAO_STORE_FILE")
        ?.takeIf { it.isNotBlank() }
        ?.let { path -> file(path).takeIf { it.isFile } }
    val envStorePassword = System.getenv("MEMAO_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
    val envKeyAlias = System.getenv("MEMAO_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    val envKeyPassword = System.getenv("MEMAO_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    if (keystoreFile != null && envStorePassword != null && envKeyAlias != null && envKeyPassword != null) {
        val configuredKeystore = keystoreFile
        val configuredStorePassword = envStorePassword
        val configuredKeyAlias = envKeyAlias
        val configuredKeyPassword = envKeyPassword
        android {
            signingConfigs {
                create("release") {
                    storeFile = configuredKeystore
                    storePassword = configuredStorePassword
                    keyAlias = configuredKeyAlias
                    keyPassword = configuredKeyPassword
                    storeType = "PKCS12"
                }
            }
            buildTypes {
                release {
                    signingConfig = signingConfigs.getByName("release")
                }
            }
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer)
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20250517")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
