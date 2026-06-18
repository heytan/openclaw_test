plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openclaw.car"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "com.openclaw.car"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        ndkVersion = "27.3.13750724"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    kotlinOptions {
        jvmTarget = "11"
    }

    packagingOptions {
        pickFirst("lib/arm64-v8a/libc++_shared.so")
    }
}

dependencies {
    // AGenUI SDK
    implementation(files("libs/AGenUI-Client-Android-release.aar"))

    // AGenUI transitive dependencies
    implementation("com.squareup.picasso:picasso:2.8")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.cardview:cardview:1.0.0")

    // Material 3
    implementation("com.google.android.material:material:1.9.0")

    // AndroidX core
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // ViewPager2
    implementation("androidx.viewpager2:viewpager2:1.0.0")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Fragment
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // OkHttp (TTS adapter API calls)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
