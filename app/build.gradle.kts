import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.firebaseCrashlytics)
    // https://developers.google.com/android/guides/google-services-plugin#processing_the_json_file
//    alias(libs.plugins.googleServices)
    alias(libs.plugins.kotlinAndroid)
    id("kotlin-parcelize")
}

android {
    namespace = "be.mygod.reactmap"
    compileSdk = 34

    defaultConfig {
        applicationId = extra["reactmap.packageName"] as String?
        minSdk = 26
        targetSdk = 34
        versionCode = (extra["reactmap.versionCode"] as String?)?.toInt()
        versionName = extra["reactmap.versionName"] as String?

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        if (extra.has("reactmap.appName")) resValue("string", "app_name", extra["reactmap.appName"] as String)
        extra["reactmap.defaultDomain"]!!.let { defaultDomain ->
            manifestPlaceholders["defaultDomain"] = defaultDomain
            buildConfigField("String", "DEFAULT_DOMAIN", "\"$defaultDomain\"")
        }
        buildConfigField("String", "GITHUB_RELEASES", if (extra.has("reactmap.githubReleases")) {
            extra["reactmap.githubReleases"] as String
        } else "null")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isPseudoLocalesEnabled = true
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!pluginManager.hasPlugin("com.google.gms.google-services")) {
                the<CrashlyticsExtension>().mappingFileUploadEnabled = false
            }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures.buildConfig = true
    val javaVersion = JavaVersion.VERSION_11
    compileOptions {
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    kotlinOptions.jvmTarget = javaVersion.toString()
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    coreLibraryDesugaring(libs.desugar)
    implementation(platform(libs.firebase.bom))
    implementation(libs.activity)
    implementation(libs.browser)
    implementation(libs.core.ktx)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.fragment.ktx)
    implementation(libs.play.services.location)
    implementation(libs.lifecycle.common)
    implementation(libs.timber)
    implementation(libs.work.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}