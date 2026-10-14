import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
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

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
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
}

fun hasJpackage(jdkHome: String): Boolean {
    val home = File(jdkHome.trim().trim('"'))
    val jpackageName = if (System.getProperty("os.name").lowercase().contains("win")) "jpackage.exe" else "jpackage"
    return File(home, "bin/$jpackageName").isFile
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
    }

    val vendorDirs = listOf("Java", "Eclipse Adoptium", "Microsoft", "BellSoft", "Zulu")

    for (root in searchRoots) {
        if (!root.isDirectory) continue

        if (root.name == ".jdks" || hasJpackage(root.absolutePath)) {
            if (hasJpackage(root.absolutePath)) return root.absolutePath
            root.listFiles()?.asSequence()
                ?.filter { it.isDirectory && hasJpackage(it.absolutePath) }
                ?.sortedByDescending { it.name }
                ?.firstOrNull()
                ?.let { return it.absolutePath }
        }

        for (vendor in vendorDirs) {
            val vendorDir = if (root.name == vendor) root else File(root, vendor)
            if (!vendorDir.isDirectory) continue
            vendorDir.listFiles()?.asSequence()
                ?.filter { it.isDirectory && hasJpackage(it.absolutePath) }
                ?.sortedByDescending { it.name }
                ?.firstOrNull()
                ?.let { return it.absolutePath }
        }
    }

    return null
}

val packagingJdkHome = discoverPackagingJdkHome()

compose.desktop {
    application {
        mainClass = "com.remotecontroller.desktop.MainKt"

        packagingJdkHome?.let { javaHome = it }

        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "Remote Controller Server"
            packageVersion = "1.0.0"
            description = "PC server for Remote Controller"
            vendor = "Remote Controller"
            // jlink's default image has no java.net.http. CommandServer's health
            // probe uses java.net.http.HttpClient, so the packaged launcher dies
            // during startup and reports it as "Failed to launch JVM".
            modules(
                "java.net.http",
                "java.management",
                "java.naming",
                "java.instrument",
                "jdk.unsupported",
                "jdk.jfr",
            )
            windows {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}

private val desktopPackageName = "Remote Controller Server"
private val desktopArtifactsDir = rootProject.layout.projectDirectory.dir("artifacts/desktop")

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
                   desktop.jdk.home=C\:\\Program Files\\Eclipse Adoptium\\jdk-21.x-hotspot
                3. Set org.gradle.java.home in gradle.properties to your Temurin JDK path.

                Then run :packageDesktop again.
                """.trimIndent(),
            )
        }
        logger.lifecycle("Packaging JDK: $jdkHome")
    }
}

tasks.register<Sync>("copyDesktopPortable") {
    group = "distribution"
    description = "Copy portable desktop app to artifacts/desktop/portable"
    dependsOn("validatePackagingJdk", "createDistributable")
    from(layout.buildDirectory.dir("compose/binaries/main/app/$desktopPackageName"))
    into(desktopArtifactsDir.dir("portable"))
}

tasks.register<Copy>("copyDesktopExe") {
    group = "distribution"
    description = "Copy desktop .exe installer to artifacts/desktop"
    dependsOn("validatePackagingJdk", "packageExe")
    from(layout.buildDirectory.dir("compose/binaries/main/exe")) {
        include("*.exe")
    }
    into(desktopArtifactsDir)
}

tasks.register("packageDesktop") {
    group = "distribution"
    description = "Build Windows .exe and copy outputs to artifacts/desktop"
    dependsOn("copyDesktopExe", "copyDesktopPortable")
}
