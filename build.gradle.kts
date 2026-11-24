import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Match the Kotlin runtime bundled with the oldest supported IDE.
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
        // Use the platform's Java default methods instead of generating bridges.
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        // Optional integrations (both are bundled with CLion).
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.github")
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        // Offline: dependencies of other bundled plugins are not downloaded from the Marketplace.
        freeArgs = listOf("-offline")
        // The optional GitHub-account source uses the GitHub plugin's account manager,
        // which has no public API; everything else must be clean.
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
        )
        ides {
            // Oldest supported and current CLion.
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.CLion, providers.gradleProperty("platformVersion"))
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.CLion, "2026.3")
        }
    }
    buildSearchableOptions = false
    // Pure Kotlin, no GUI-designer forms: bytecode instrumentation is not needed.
    instrumentCode = false
}

tasks {
    test {
        useJUnit()
    }
}

// Manual testing in IntelliJ IDEA Community (same platform, no license dialog):
// ./gradlew runIdeForUiTests
intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            type = org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaCommunity
            version = "2025.2.6.4"
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Didea.trust.all.projects=true",
                        "-Djb.consents.confirmation.enabled=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Didea.initially.ask.config=never",
                        "-Dide.show.tips.on.startup.default.value=false",
                        "-Dsun.java2d.uiScale=1",
                        // Containers often run as root, where Chromium needs its sandbox off.
                        "-Dide.browser.jcef.sandbox.enable=false",
                    )
                }
                argumentProviders += CommandLineArgumentProvider { listOfNotNull(providers.gradleProperty("testProject").orNull) }
            }
        }
    }
}
