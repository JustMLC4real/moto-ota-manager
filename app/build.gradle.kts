import groovy.json.JsonOutput
import java.security.MessageDigest

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Values never enter source, BuildConfig, resources or task logging. Debug signing
// remains Android's existing local debug key and is never a release fallback.
val releaseSigningNames = listOf("MOTO_RELEASE_STORE_FILE", "MOTO_RELEASE_STORE_PASSWORD",
    "MOTO_RELEASE_KEY_ALIAS", "MOTO_RELEASE_KEY_PASSWORD")
val releaseSigningValues = releaseSigningNames.associateWith { providers.environmentVariable(it).orNull }
val releaseSigningConfigured = releaseSigningValues.values.all { !it.isNullOrBlank() }
if (releaseSigningValues.values.any { !it.isNullOrBlank() } && !releaseSigningConfigured) {
    throw GradleException("Release signing is incomplete. Set all four MOTO_RELEASE_* signing variables or none; values are not logged.")
}
val minifyRelease = providers.gradleProperty("moto.minifyRelease").map {
    it.toBooleanStrictOrNull() ?: throw GradleException("moto.minifyRelease must be true or false")
}.getOrElse(false)

android {
    namespace = "nl.moto.ota"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "nl.moto.ota"
        minSdk = 33
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        if (releaseSigningConfigured) {
            create("privateRelease") {
                val supplied = File(releaseSigningValues.getValue("MOTO_RELEASE_STORE_FILE")!!)
                require(supplied.isAbsolute && !supplied.canonicalFile.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
                    "Release keystore must use an absolute path outside the checkout"
                }
                storeFile = supplied
                storePassword = releaseSigningValues.getValue("MOTO_RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("MOTO_RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("MOTO_RELEASE_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
            }
        }
    }
    buildTypes {
        getByName("debug") { isDebuggable = true; isMinifyEnabled = false }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("test-proguard-rules.pro")
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("privateRelease")
        }
    }
    testBuildType = providers.gradleProperty("moto.testBuildType").getOrElse("debug").also {
        require(it in setOf("debug", "release")) { "moto.testBuildType must be debug or release" }
    }
    buildFeatures { compose = true; buildConfig = true; aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    packaging { jniLibs.useLegacyPackaging = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Require private release signing; never create or use a debug fallback."
    doLast {
        check(releaseSigningConfigured) { "Set MOTO_RELEASE_STORE_FILE, MOTO_RELEASE_STORE_PASSWORD, MOTO_RELEASE_KEY_ALIAS and MOTO_RELEASE_KEY_PASSWORD for a signed release." }
        check(File(releaseSigningValues.getValue("MOTO_RELEASE_STORE_FILE")!!).isFile) { "Configured release keystore is unavailable" }
    }
}
tasks.register("assembleSignedRelease") {
    group = "build"; description = "Build a non-debuggable APK using explicitly supplied private release signing."
    dependsOn(verifyReleaseSigning, "assembleRelease")
}
tasks.matching { it.name in setOf("packageRelease", "validateSigningRelease") }.configureEach {
    mustRunAfter(verifyReleaseSigning)
}
tasks.register("writeReleaseDependencyInventory") {
    group = "verification"; description = "Export resolved runtime coordinates and artifact hashes without local paths."
    val destination = layout.buildDirectory.file("reports/dependencies/release-runtime.json")
    outputs.file(destination)
    doLast {
        val configuration = configurations.getByName("releaseRuntimeClasspath")
        val modules = configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
            (component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let {
                mapOf("group" to it.group, "name" to it.module, "version" to it.version)
            }
        }.distinct().sortedWith(compareBy({ it["group"] }, { it["name"] }, { it["version"] }))
        val artifacts = configuration.resolvedConfiguration.resolvedArtifacts.map { artifact ->
            val hash = MessageDigest.getInstance("SHA-256")
            artifact.file.inputStream().use { input -> val buffer = ByteArray(65_536)
                while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            mapOf("coordinate" to artifact.moduleVersion.id.toString(), "file" to artifact.file.name,
                "sha256" to hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }.sortedBy { it.getValue("coordinate") }
        destination.get().asFile.apply { parentFile.mkdirs(); writeText(JsonOutput.prettyPrint(JsonOutput.toJson(
            mapOf("schema" to 1, "configuration" to "releaseRuntimeClasspath", "modules" to modules, "artifacts" to artifacts)))) }
    }
}
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.work:work-testing:2.12.0")
    // AndroidX Test references these CLASS-retained annotations. Supply their
    // definitions when shrinking the release instrumentation APK as well.
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
