import com.remotecontroller.buildlogic.CompileNativeLibrary
import com.remotecontroller.buildlogic.HostOs
import com.remotecontroller.buildlogic.composeDesktopJvmArtifact
import com.remotecontroller.buildlogic.discoverJniJdkHome
import com.remotecontroller.buildlogic.discoverPackagingJdkHome
import com.remotecontroller.buildlogic.dropJarSignature
import com.remotecontroller.buildlogic.hasJpackage
import com.remotecontroller.buildlogic.hostOs
import com.remotecontroller.buildlogic.product
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    alias(libs.plugins.remotecontroller.jvm.server)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// Compose's folder for this OS inside appResources. Also where a Gradle run loads the library from.
val screencastFolder = hostOs.folder

val appJvmArgs = arrayOf(
    "--enable-native-access=ALL-UNNAMED",
    "--add-opens=java.base/java.io=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
)

tasks.withType<JavaExec>().configureEach {
    jvmArgs(*appJvmArgs)
    // The IDE starts MainKt with its own JavaExec task, which Compose knows nothing about.
    dependsOn("collectScreencast")
    systemProperty("screencast.library.dir", layout.buildDirectory.dir("appResources/$screencastFolder").get().asFile.absolutePath)
}

val screencastLibrary = configurations.create("screencastLibrary") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    implementation(project(":server:host:screencast"))
    screencastLibrary(project(path = ":server:host:screencast", configuration = "nativeLibrary"))
    implementation("${composeDesktopJvmArtifact()}:${libs.versions.compose.multiplatform.get()}")
    implementation(libs.compose.material3)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.bouncycastle.pkix)
}

val productName = product.name
val productVersion = product.version
val artifactBase = "$productName-$productVersion"

check(productVersion.matches(Regex("""\d+(\.\d+)*"""))) {
    "version.properties version must be numeric, like 1.0.0. Was: $productVersion"
}

val packagingJdkHome = discoverPackagingJdkHome()

val desktopIsWindows = hostOs == HostOs.WINDOWS
val desktopIsMac = hostOs == HostOs.MACOS
val desktopIsLinux = hostOs == HostOs.LINUX

// Release builds go through ProGuard (shrinking only). -Pdesktop.proguard=false skips it,
// for example to check whether a crash in the release app comes from a missing keep rule.
val useProguard = (findProperty("desktop.proguard") as String?)?.toBoolean() ?: true

// Release files are portable apps, named remote-controller-server-<version>-<os>-<arch>.<ext>.
val portableBase = "remote-controller-server-$productVersion"
val portableArch = when (System.getProperty("os.arch")) {
    "aarch64", "arm64" -> "arm64"
    else -> "x64"
}

compose.desktop {
    application {
        mainClass = "com.remotecontroller.desktop.MainKt"
        jvmArgs(*appJvmArgs)

        packagingJdkHome?.let { javaHome = it }

        buildTypes.release.proguard {
            isEnabled.set(useProguard)
            obfuscate.set(false)
            optimize.set(false)
            // Separate jars keep each library's META-INF/services file intact for ServiceLoader.
            joinOutputJars.set(false)
            // No maxHeapSize: Compose 1.12 passes it as "-Xmx:<value>", which the JVM rejects.
            // ProGuard's JVM then gets the default heap (a quarter of RAM), which is enough here.
            configurationFiles.from(project.file("proguard-rules.pro"))
        }

        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            when {
                desktopIsWindows -> targetFormats(TargetFormat.Exe)
                desktopIsMac -> targetFormats(TargetFormat.Dmg)
            }
            packageName = productName
            packageVersion = productVersion
            description = "PC server for Remote Controller"
            vendor = "Remote Controller"
            // Keep the JDK HTTP module in the runtime image. Dropping it has
            // previously made the packaged launcher fail with "Failed to launch JVM".
            modules(
                "java.net.http",
                "java.management",
                "java.naming",
                "java.instrument",
                "jdk.unsupported",
                "jdk.jfr",
            )
            if (desktopIsWindows) {
                windows {
                    iconFile.set(project.file("src/main/resources/icon.png"))
                }
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}

private val desktopArtifactsDir = rootProject.layout.projectDirectory.dir("artifacts/desktop")

val generateAppVersion = tasks.register("generateAppVersion") {
    group = "build"
    description = "Write AppVersion from version.properties"
    val output = layout.buildDirectory.file("generated/appVersion/kotlin/com/remotecontroller/desktop/AppVersion.kt")
    inputs.file(rootProject.file("version.properties"))
    outputs.file(output)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        val name = productName.replace("\\", "\\\\").replace("\"", "\\\"")
        val version = productVersion.replace("\\", "\\\\").replace("\"", "\\\"")
        file.writeText(
            """
            package com.remotecontroller.desktop

            internal object AppVersion {
                const val NAME = "$name"
                const val VERSION = "$version"
            }
            """.trimIndent() + "\n",
        )
    }
}

kotlin.sourceSets.named("main").configure {
    kotlin.srcDir(layout.buildDirectory.dir("generated/appVersion/kotlin"))
}

val compileMacVolume = tasks.register<CompileNativeLibrary>("compileMacVolume") {
    group = "build"
    description = "Build the macOS Core Audio volume library with CMake"
    onlyIf { desktopIsMac }
    sourceDir.set(layout.projectDirectory.dir("src/main/cpp"))
    cmakeBuildDir.set(layout.buildDirectory.dir("cmake"))
    outputLibrary.set(layout.buildDirectory.file("natives/libmacvolume.dylib"))
    jdkVariable.set("JAVA_HOME")
    outputVariable.set("MACVOLUME_OUTPUT_DIR")
    discoverJniJdkHome()?.let { jdkHome.set(it) }
}

tasks.named<ProcessResources>("processResources") {
    if (desktopIsMac) {
        dependsOn(compileMacVolume)
        from(layout.buildDirectory.dir("natives")) {
            include("libmacvolume.dylib")
            into("natives")
        }
    }
}

tasks.named("compileKotlin").configure {
    dependsOn(generateAppVersion)
}

// The screencast library goes in the app's resources folder, next to the runtime, and loads from there by full path.
val collectScreencast = tasks.register<Sync>("collectScreencast") {
    group = "build"
    description = "Copy the screencast library into the app resources"
    from(screencastLibrary)
    into(layout.buildDirectory.dir("appResources/$screencastFolder"))
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(collectScreencast)
}

tasks.register("validatePackagingJdk") {
    group = "distribution"
    description = "Verify that a full JDK with jpackage is configured for desktop packaging"

    doLast {
        val jdkHome = discoverPackagingJdkHome()
        if (jdkHome == null || !hasJpackage(jdkHome)) {
            val gradleJavaHome = System.getProperty("java.home")
            throw GradleException(
                """
                Desktop packaging requires a full JDK 17+ with jpackage.
                Android Studio JBR does not include jpackage.

                Gradle is currently running on: $gradleJavaHome
                jpackage present: ${gradleJavaHome?.let(::hasJpackage) ?: false}

                Fix (pick one):
                1. Android Studio → Settings → Build, Execution, Deployment → Build Tools → Gradle
                   → Gradle JDK → Temurin 21 (not "jbr").
                2. Add to local.properties:
                   Windows: desktop.jdk.home=C\:\\Program Files\\Eclipse Adoptium\\jdk-21.x-hotspot
                   macOS:   desktop.jdk.home=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
                3. Set org.gradle.java.home in gradle.properties to your Temurin JDK path.

                Then run :packageDesktop again.
                """.trimIndent(),
            )
        }
        logger.lifecycle("Packaging JDK: $jdkHome")
    }
}

val cleanDesktopArtifacts = tasks.register<Delete>("cleanDesktopArtifacts") {
    delete(desktopArtifactsDir)
}

tasks.matching { it.name == "proguardReleaseJars" }.configureEach {
    doLast {
        outputs.files.asFileTree.matching { include("**/*.jar") }.forEach { dropJarSignature(it, logger) }
    }
}

// One release file per OS: a portable app on Windows and Linux (unpack and start), a .dmg on macOS.
// createReleaseDistributable makes the app folder (own Java runtime, ProGuard-shrunk jars).
// Compose registers its tasks after this script runs, so everything about it is looked up lazily.
val releaseDistributable = "createReleaseDistributable"
val portableAppDir: Provider<Directory> = provider {
    tasks.named<AbstractJPackageTask>(releaseDistributable).get().destinationDir.get()
}

when {
    desktopIsWindows -> tasks.register<Zip>("packagePortable") {
        group = "distribution"
        description = "Zip the portable Windows app into artifacts/desktop"
        dependsOn("validatePackagingJdk", releaseDistributable)
        mustRunAfter(cleanDesktopArtifacts)
        from(portableAppDir)
        archiveFileName.set("$portableBase-windows-$portableArch.zip")
        destinationDirectory.set(desktopArtifactsDir)
    }

    // macOS ships as a .dmg (Apple Silicon): packageReleaseDmg builds it from the ProGuard-shrunk app.
    desktopIsMac -> tasks.register<Copy>("packagePortable") {
        group = "distribution"
        description = "Copy the release macOS .dmg into artifacts/desktop"
        dependsOn("validatePackagingJdk", "packageReleaseDmg")
        mustRunAfter(cleanDesktopArtifacts)
        from(provider { tasks.named<AbstractJPackageTask>("packageReleaseDmg").get().destinationDir.get() }) {
            include("*.dmg")
            rename { "$portableBase-macos-$portableArch.dmg" }
        }
        into(desktopArtifactsDir)
    }

    // xz -9e comes out about 12% smaller than gzip -9 for this app (measured: 90 MB vs 102 MB).
    desktopIsLinux -> tasks.register<Exec>("packagePortable") {
        group = "distribution"
        description = "Pack the portable Linux app as .tar.xz into artifacts/desktop"
        dependsOn("validatePackagingJdk", releaseDistributable)
        mustRunAfter(cleanDesktopArtifacts)
        val archive = desktopArtifactsDir.file("$portableBase-linux-$portableArch.tar.xz").asFile
        environment("XZ_OPT", "-9e -T0")
        doFirst {
            archive.parentFile.mkdirs()
            commandLine("tar", "-C", portableAppDir.get().asFile.absolutePath, "-cJf", archive.absolutePath, productName)
        }
    }
}

tasks.register("packageDesktop") {
    group = "distribution"
    description = "Build the portable app for this OS into artifacts/desktop"
    dependsOn(cleanDesktopArtifacts, "packagePortable")
}
