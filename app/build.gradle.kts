import java.util.Properties

plugins {
    id("com.android.application")
}

val signingProps = Properties()
val signingPropsFile = rootProject.file("signing.properties")
if (signingPropsFile.exists()) signingProps.load(signingPropsFile.inputStream())

android {
    namespace = "build.bytes.temproot"
    compileSdk = 37

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "build.bytes.temproot"
        minSdk = 32
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (signingPropsFile.exists()) {
            val ksFile = signingProps.getProperty("KEYSTORE_FILE")
            val ksPass = signingProps.getProperty("KEYSTORE_PASSWORD")
            val kAlias = signingProps.getProperty("KEY_ALIAS")
            val kPass = signingProps.getProperty("KEY_PASSWORD")
            if (ksFile != null && ksPass != null && kAlias != null && kPass != null) {
                create("release") {
                    storeFile = file(ksFile)
                    storePassword = ksPass
                    keyAlias = kAlias
                    keyPassword = kPass
                }
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        checkReleaseBuilds = false
    }

    externalNativeBuild {
        cmake {
            path("src/main/jni/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set(output.versionName.map { vn -> "TemporaryRoot_${vn}.apk" })
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.preference)
}
