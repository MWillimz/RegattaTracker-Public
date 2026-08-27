import java.io.FileInputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val buildZone = ZoneId.of("Europe/Berlin")
val buildIdentifierFormatter = DateTimeFormatter.ofPattern("yy.MM.dd-HHmm", Locale.ROOT)
val supportedBuildChannels = setOf("dev", "staging", "production")

fun resolveBuildChannel(rawChannel: String?): String {
    if (rawChannel == null) {
        return "dev"
    }

    val channel = rawChannel.trim()
    if (channel !in supportedBuildChannels) {
        throw org.gradle.api.GradleException(
            "Unsupported regattaBuildChannel '$rawChannel'. " +
                "Expected one of: ${supportedBuildChannels.joinToString(", ")}"
        )
    }

    return channel
}

fun createBuildIdentifier(timestamp: ZonedDateTime, channel: String): String {
    val baseIdentifier = timestamp
        .withZoneSameInstant(buildZone)
        .format(buildIdentifierFormatter)

    return when (channel) {
        "dev" -> "$baseIdentifier-dev"
        "staging" -> "$baseIdentifier-staging"
        "production" -> baseIdentifier
        else -> throw org.gradle.api.GradleException(
            "Unsupported build channel '$channel' while creating build identifier"
        )
    }
}

val buildTimestamp = ZonedDateTime.now(buildZone)
val buildChannel = resolveBuildChannel(
    providers.gradleProperty("regattaBuildChannel").orNull
)
val appBuildIdentifier = createBuildIdentifier(buildTimestamp, buildChannel)

val keystorePropertiesFile = rootProject.file("key.properties")
val keystoreProperties = Properties()

if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "de.williserv.regattaclient"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "de.williserv.regattaclient"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = appBuildIdentifier

        buildConfigField("String", "APP_VERSION_NAME", "\"$appBuildIdentifier\"")
        buildConfigField("String", "BUILD_DATE", "\"${buildTimestamp.toLocalDate()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

tasks.register("verifyBuildIdentifier") {
    group = "verification"
    description = "Verifies build identifier format, channel mapping and default behavior."

    doLast {
        val fixedTimestamp = ZonedDateTime.of(
            2026,
            8,
            27,
            9,
            53,
            0,
            0,
            buildZone
        )

        check(resolveBuildChannel(null) == "dev")
        check(createBuildIdentifier(fixedTimestamp, resolveBuildChannel("dev")) == "26.08.27-0953-dev")
        check(createBuildIdentifier(fixedTimestamp, resolveBuildChannel("staging")) == "26.08.27-0953-staging")
        check(createBuildIdentifier(fixedTimestamp, resolveBuildChannel("production")) == "26.08.27-0953")

        val invalidChannelFailure = runCatching {
            resolveBuildChannel("feature/test")
        }.exceptionOrNull()
        check(invalidChannelFailure is org.gradle.api.GradleException)

        val blankChannelFailure = runCatching {
            resolveBuildChannel("   ")
        }.exceptionOrNull()
        check(blankChannelFailure is org.gradle.api.GradleException)
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
    implementation("androidx.work:work-runtime:2.11.2")
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("com.google.zxing:core:3.5.4")

    implementation("androidx.camera:camera-core:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
}
