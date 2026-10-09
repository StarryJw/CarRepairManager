import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

// 凭据由本机文件生成到代码，既不提交到 Git，也不需要在 App 内配置。
val ocrCredentials = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("ocr-credentials.local.properties"))
        .asText.orElse("").get().reader().use { load(it) }
}

/** 正确转义 Java 字符串，构建错误不回显凭据内容。 */
fun ocrCredentialLiteral(key: String): String {
    val value = ocrCredentials.getProperty(key, "").trim()
    require('\n' !in value && '\r' !in value) { "OCR 配置 $key 不能包含换行" }
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.example.carrepairmanager"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.carrepairmanager"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BAIDU_OCR_API_KEY", ocrCredentialLiteral("baidu.api.key"))
        buildConfigField("String", "BAIDU_OCR_SECRET_KEY", ocrCredentialLiteral("baidu.secret.key"))
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
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("ui.screenshot.dir", layout.buildDirectory.dir("reports/milestone2-ui").get().asFile.absolutePath)
            it.systemProperty("room.schema.dir", layout.projectDirectory.dir("schemas").asFile.absolutePath)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
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
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.serialization.json)
    // WebDAV 的 PROPFIND / MKCOL / MOVE 需要支持自定义 HTTP 方法的轻量客户端。
    implementation(libs.okhttp)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
