import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    )
}

dependencies {
    implementation(project(":shared"))
    implementation("${composeDesktopJvmArtifact()}:${libs.versions.compose.multiplatform.get()}")
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.koin.core)
    implementation(libs.koin.compose)
    implementation(libs.koin.compose.viewmodel)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    runtimeOnly(libs.slf4j.simple)
}

fun composeDesktopJvmArtifact(): String {
    val osName = System.getProperty("os.name")
    val osId = when {
        osName.equals("Mac OS X", ignoreCase = true) -> "macos"
        osName.startsWith("Win", ignoreCase = true) -> "windows"
        osName.startsWith("Linux", ignoreCase = true) -> "linux"
        else -> error("Unknown OS name: $osName")
    }
    val archId = when (val arch = System.getProperty("os.arch")) {
        "x86_64", "amd64" -> "x64"
        "aarch64" -> "arm64"
        else -> error("Unsupported OS arch: $arch")
    }
    return "org.jetbrains.compose.desktop:desktop-jvm-$osId-$archId"
}

fun jdkHomeWithJpackage(directory: File): String? {
    if (!directory.isDirectory) return null
    if (hasJpackage(directory.absolutePath)) return directory.absolutePath

    val contentsHome = File(directory, "Contents/Home")
    if (hasJpackage(contentsHome.absolutePath)) return contentsHome.absolutePath

    return directory.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?.mapNotNull { child ->
            when {
                hasJpackage(child.absolutePath) -> child.absolutePath
                hasJpackage(File(child, "Contents/Home").absolutePath) -> File(child, "Contents/Home").absolutePath
                else -> null
            }
        }
        ?.firstOrNull()
}

fun hasJpackage(jdkHome: String): Boolean {
    val home = File(jdkHome.trim().trim('"'))
    val jpackageName = if (System.getProperty("os.name").lowercase().contains("win")) "jpackage.exe" else "jpackage"
    return File(home, "bin/$jpackageName").isFile
}

fun hasJniHeaders(jdkHome: String): Boolean {
    val home = File(jdkHome.trim().trim('"'))
    return File(home, "include/jni.h").isFile && File(home, "include/darwin/jni_md.h").isFile
}

fun jdkHomeWithJni(directory: File): String? {
    if (!directory.isDirectory) return null
    if (hasJniHeaders(directory.absolutePath)) return directory.absolutePath

    val contentsHome = File(directory, "Contents/Home")
    if (hasJniHeaders(contentsHome.absolutePath)) return contentsHome.absolutePath

    return directory.listFiles()
        ?.asSequence()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?.mapNotNull { child ->
            when {
                hasJniHeaders(child.absolutePath) -> child.absolutePath
                hasJniHeaders(File(child, "Contents/Home").absolutePath) -> File(child, "Contents/Home").absolutePath
                else -> null
            }
        }
        ?.firstOrNull()
}

fun discoverJniJdkHome(): String? {
    val candidates = linkedSetOf<String>()

    fun addCandidate(path: String?) {
        if (path.isNullOrBlank()) return
        candidates += path.trim().trim('"')
    }

    extensions.findByType(JavaToolchainService::class.java)?.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }?.orNull?.metadata?.installationPath?.asFile?.absolutePath?.let(::addCandidate)
    addCandidate(findProperty("desktop.jdk.home")?.toString())
    addCandidate(rootProject.findProperty("desktop.jdk.home")?.toString())
    addCandidate(readLocalProperty("desktop.jdk.home"))
    addCandidate(System.getenv("DESKTOP_JDK_HOME"))
    addCandidate(System.getenv("JAVA_HOME"))
    addCandidate(System.getProperty("java.home"))

    candidates.firstOrNull(::hasJniHeaders)?.let { return it }

    val searchRoots = listOf(
        File("/Library/Java/JavaVirtualMachines"),
        File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"),
        File(System.getProperty("user.home"), "Library/Java/JetBrainsRuntime"),
        File(System.getProperty("user.home"), ".jdks"),
        File("/opt/homebrew/opt/openjdk@21"),
        File("/opt/homebrew/opt/openjdk"),
        File("/usr/local/opt/openjdk"),
    )
    return searchRoots.firstNotNullOfOrNull(::jdkHomeWithJni)
}

fun readLocalProperty(key: String): String? {
    val localProperties = rootProject.file("local.properties")
    if (!localProperties.isFile) return null

    return Properties().apply {
        localProperties.inputStream().use { load(it) }
    }.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
}

fun discoverPackagingJdkHome(): String? {
    val candidates = linkedSetOf<String>()

    fun addCandidate(path: String?) {
        if (path.isNullOrBlank()) return
        candidates += path.trim().trim('"')
    }

    addCandidate(findProperty("desktop.jdk.home")?.toString())
    addCandidate(rootProject.findProperty("desktop.jdk.home")?.toString())
    addCandidate(rootProject.findProperty("org.gradle.java.home")?.toString())
    addCandidate(readLocalProperty("desktop.jdk.home"))
    addCandidate(System.getProperty("java.home"))
    addCandidate(System.getenv("DESKTOP_JDK_HOME"))
    addCandidate(System.getenv("JAVA_HOME"))

    extensions.findByType(JavaToolchainService::class.java)?.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }?.orNull?.metadata?.installationPath?.asFile?.absolutePath?.let(::addCandidate)

    candidates.firstOrNull(::hasJpackage)?.let { return it }

    val searchRoots = buildList {
        System.getenv("ProgramFiles")?.let { add(File(it)) }
        System.getenv("ProgramFiles(x86)")?.let { add(File(it)) }
        add(File(System.getProperty("user.home"), "AppData/Local/Programs/Eclipse Adoptium"))
        add(File(System.getProperty("user.home"), ".jdks"))
        add(File(System.getProperty("user.home"), "scoop/apps/temurin21-jdk/current"))
        add(File("/Library/Java/JavaVirtualMachines"))
        add(File(System.getProperty("user.home"), "Library/Java/JavaVirtualMachines"))
        add(File("/opt/homebrew/opt/openjdk@21"))
        add(File("/opt/homebrew/opt/openjdk"))
        add(File("/usr/local/opt/openjdk"))
    }

    val vendorDirs = listOf("Java", "Eclipse Adoptium", "Microsoft", "BellSoft", "Zulu")

    for (root in searchRoots) {
        if (!root.isDirectory) continue

        jdkHomeWithJpackage(root)?.let { return it }

        for (vendor in vendorDirs) {
            val vendorDir = if (root.name == vendor) root else File(root, vendor)
            if (!vendorDir.isDirectory) continue
            jdkHomeWithJpackage(vendorDir)?.let { return it }
        }
    }

    return null
}

val productName = rootProject.extra["productName"] as String
val productVersion = rootProject.extra["productVersion"] as String
val artifactBase = "$productName-$productVersion"

check(productVersion.matches(Regex("""\d+(\.\d+)*"""))) {
    "version.properties version must be numeric, like 1.0.0. Was: $productVersion"
}

val packagingJdkHome = discoverPackagingJdkHome()

val desktopHostOs = System.getProperty("os.name").lowercase()
val desktopIsWindows = desktopHostOs.contains("win")
val desktopIsMac = desktopHostOs.contains("mac")

compose.desktop {
    application {
        mainClass = "com.remotecontroller.desktop.MainKt"
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
            "--add-opens=java.base/java.io=ALL-UNNAMED",
            "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        )

        packagingJdkHome?.let { javaHome = it }

        nativeDistributions {
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

abstract class CompileMacVolumeTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:Internal
    abstract val cmakeBuildDir: DirectoryProperty

    @get:OutputFile
    abstract val outputLibrary: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val jniJdkHome: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        val home = jniJdkHome.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException(
            """
            Compiling Mac volume needs jni.h from a full JDK.
            Gradle is running on ${System.getProperty("java.home")}, which has no include/jni.h.
            Install Temurin 21, or set JAVA_HOME / desktop.jdk.home to a JDK that contains include/jni.h.
            """.trimIndent(),
        )
        val cmake = cmakeExecutable()
        val buildDir = cmakeBuildDir.get().asFile
        val outputDir = outputLibrary.get().asFile.parentFile
        outputDir.mkdirs()
        logger.lifecycle("Mac volume JNI headers: ${File(home, "include")}")
        execOperations.exec {
            commandLine(
                cmake,
                "-S", sourceDir.get().asFile.absolutePath,
                "-B", buildDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DJAVA_HOME=$home",
                "-DMACVOLUME_OUTPUT_DIR=${outputDir.absolutePath}",
            )
        }
        execOperations.exec {
            commandLine(cmake, "--build", buildDir.absolutePath, "--config", "Release")
        }
    }

    private fun cmakeExecutable(): String {
        val override = System.getenv("CMAKE")?.trim()?.takeIf { it.isNotEmpty() }
        if (override != null) {
            if (!File(override).canExecute()) {
                throw GradleException("CMAKE is set to $override, but that file is not executable.")
            }
            return override
        }
        val exe = if (System.getProperty("os.name").lowercase().contains("win")) "cmake.exe" else "cmake"
        val found = System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .map { File(it, exe) }
            .firstOrNull { it.canExecute() }
        return found?.absolutePath ?: throw GradleException(
            "CMake is required to build libmacvolume. Install CMake and ensure `cmake` is on PATH.",
        )
    }
}

val compileMacVolume = tasks.register<CompileMacVolumeTask>("compileMacVolume") {
    group = "build"
    description = "Build the macOS Core Audio volume library with CMake"
    onlyIf { desktopIsMac }
    sourceDir.set(layout.projectDirectory.dir("src/main/cpp"))
    cmakeBuildDir.set(layout.buildDirectory.dir("cmake"))
    outputLibrary.set(layout.buildDirectory.file("natives/libmacvolume.dylib"))
    discoverJniJdkHome()?.let { jniJdkHome.set(it) }
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

tasks.register<Sync>("copyDesktopPortable") {
    group = "distribution"
    description = "Copy the portable desktop app to artifacts/desktop"
    dependsOn("validatePackagingJdk", "createDistributable")
    mustRunAfter(cleanDesktopArtifacts)
    from(layout.buildDirectory.dir("compose/binaries/main/app"))
    into(desktopArtifactsDir.dir(artifactBase))
}

if (desktopIsWindows) {
    tasks.register<Copy>("copyDesktopExe") {
        group = "distribution"
        description = "Copy desktop .exe installer to artifacts/desktop"
        dependsOn("validatePackagingJdk", "packageExe")
        mustRunAfter(cleanDesktopArtifacts)
        from(layout.buildDirectory.dir("compose/binaries/main/exe")) {
            include("*.exe")
        }
        into(desktopArtifactsDir)
    }

    tasks.register("packageDesktop") {
        group = "distribution"
        description = "Build every Windows desktop artifact into artifacts/desktop"
        dependsOn(cleanDesktopArtifacts, "copyDesktopExe", "copyDesktopPortable")
    }
}

if (desktopIsMac) {
    tasks.register<Copy>("copyDesktopDmg") {
        group = "distribution"
        description = "Copy desktop .dmg to artifacts/desktop"
        dependsOn("validatePackagingJdk", "packageDmg")
        mustRunAfter(cleanDesktopArtifacts)
        from(layout.buildDirectory.dir("compose/binaries/main/dmg")) {
            include("*.dmg")
        }
        into(desktopArtifactsDir)
    }

    tasks.register("packageDesktop") {
        group = "distribution"
        description = "Build every macOS desktop artifact into artifacts/desktop"
        dependsOn(cleanDesktopArtifacts, "copyDesktopDmg", "copyDesktopPortable")
    }
}
