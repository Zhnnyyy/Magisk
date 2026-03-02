plugins {
    id("com.android.application")
    id("org.lsposed.lsparanoid")
}

lsparanoid {
    seed = if (RAND_SEED != 0) RAND_SEED else null
    includeDependencies = true
    classFilter = { true }
}

android {
    namespace = "com.topjohnwu.magisk"

    val canary = !Config.version.contains(".")
    val base = "https://github.com/topjohnwu/Magisk/releases/download/"
    val customUrl = Config.stubApkUrl
    val defaultUrl = base + "v${Config.version}/Magisk-v${Config.version}.apk"
    val url = customUrl ?: defaultUrl
    val canaryUrl = base + "canary-${Config.versionCode}/"

    defaultConfig {
        applicationId = "com.topjohnwu.magisk"
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "APK_URL", "\"$url\"")
        buildConfigField("int", "STUB_VERSION", Config.stubVersion)
    }

    buildTypes {
        release {
            if (canary && customUrl == null) {
                buildConfigField("String", "APK_URL", "\"${canaryUrl}app-release.apk\"")
            }
            proguardFiles("proguard-rules.pro")
            isMinifyEnabled = true
            isShrinkResources = false
        }
        debug {
            if (canary && customUrl == null) {
                buildConfigField("String", "APK_URL", "\"${canaryUrl}app-debug.apk\"")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

setupStubApk()

dependencies {
    implementation(project(":shared"))
}
