plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

val sdkGroup = providers.gradleProperty("GROUP").orElse("dev.justask").get()
val sdkVersion = providers.gradleProperty("VERSION_NAME").orElse("0.0.0-SNAPSHOT").get()
group = sdkGroup
version = sdkVersion

android {
    namespace = "dev.justask.sdk"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = sdkGroup
            artifactId = "sdk"
            version = sdkVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("Just Ask SDK")
                description.set("Boot-time permission / intent orchestration for Android")
                url.set("https://github.com/mikenrafter/just-ask")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                scm {
                    url.set("https://github.com/mikenrafter/just-ask")
                    connection.set("scm:git:git://github.com/mikenrafter/just-ask.git")
                    developerConnection.set("scm:git:ssh://git@github.com/mikenrafter/just-ask.git")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/mikenrafter/just-ask")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR")
                    .orElse(providers.gradleProperty("gpr.user"))
                    .orElse("mikenrafter")
                    .get()
                password = providers.environmentVariable("GITHUB_TOKEN")
                    .orElse(providers.gradleProperty("gpr.key"))
                    .orElse("")
                    .get()
            }
        }
    }
}
