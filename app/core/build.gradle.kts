plugins {
    alias(libs.plugins.android.library)
    kotlin("plugin.parcelize")
    alias(libs.plugins.moshix)
    alias(libs.plugins.ksp)
    alias(libs.plugins.wire)
}

setupCoreLib()

ksp {
    arg("room.generateKotlin", "true")
}

wire {
    kotlin {}
}

android {
    namespace = "com.topjohnwu.magisk.core"

    defaultConfig {
        buildConfigField("String", "APP_PACKAGE_NAME", "\"io.github.mimivex.tanuki\"")
        buildConfigField("int", "APP_VERSION_CODE", "${Config.versionCode}")
        buildConfigField("String", "APP_VERSION_NAME", "\"${Config.version}\"")
        consumerProguardFile("proguard-rules.pro")
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.jdk.libs)

    api(libs.timber)
    api(libs.markwon.core)
    implementation(libs.bcpkix)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.wire.runtime)

    api(libs.libsu.core)
    api(libs.libsu.service)
    api(libs.libsu.nio)
    implementation(libs.libsu.io)

    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.retrofit.scalars)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.dnsoverhttps)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.core.splashscreen)
    implementation(libs.core.ktx)
    implementation(libs.activity)
    implementation(libs.androidx.webkit)
    implementation(libs.collection.ktx)
    implementation(libs.profileinstaller)

    // We also implement all our tests in this module.
    // However, we don't want to bundle test dependencies.
    // That's why we make it compileOnly.
    compileOnly(libs.test.junit)
    compileOnly(libs.test.uiautomator)

    // JVM unit tests
    testImplementation(libs.junit)
    // org.json for the JVM tests: the Android stub throws "not mocked"
    testImplementation(libs.json)
}

// The real-dex gate needs a built manager APK: -Ptanuki.test.apk=<apk>
tasks.withType<Test>().configureEach {
    providers.gradleProperty("tanuki.test.apk").orNull?.let {
        systemProperty("tanuki.test.apk", File(it).absolutePath)
    }
}
