plugins {
    kotlin("multiplatform") version "2.4.10"
    `maven-publish`
}

group = "gg.sona"
version = providers.gradleProperty("mslVersion").getOrElse("1.0.0")

repositories {
    mavenCentral()
}

val lwjgl = "3.4.3"
val lwjglNatives = System.getProperty("os.name").lowercase().let { os ->
    val arm = System.getProperty("os.arch").contains("aarch64")
    when {
        "windows" in os -> "natives-windows"
        "mac" in os -> if (arm) "natives-macos-arm64" else "natives-macos"
        else -> if (arm) "natives-linux-arm64" else "natives-linux"
    }
}

kotlin {
    jvmToolchain(25)

    jvm {
        testRuns.named("test") {
            executionTask.configure {
                useJUnitPlatform()
            }
        }
    }
    mingwX64()
    linuxX64()
    linuxArm64()
    macosArm64()

    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation("org.lwjgl:lwjgl:$lwjgl")
            implementation("org.lwjgl:lwjgl-vulkan:$lwjgl")
            runtimeOnly("org.lwjgl:lwjgl:$lwjgl:$lwjglNatives")
        }
    }
}

fun setting(property: String, environment: String): String? =
    (providers.gradleProperty(property).orNull ?: providers.environmentVariable(environment).orNull)?.takeIf { it.isNotBlank() }

publishing {
    repositories {
        maven {
            name = "Clover"
            url = uri("https://maven.cloverclient.com/releases")
            credentials {
                username = setting("cloverUsername", "CLOVER_MAVEN_USERNAME")
                password = setting("cloverPassword", "CLOVER_MAVEN_PASSWORD")
            }
        }
    }
}
