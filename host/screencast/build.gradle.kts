import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.remotecontroller.jvm.library)
}

abstract class CompileScreencastTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:Internal
    abstract val cmakeBuildDir: DirectoryProperty

    @get:OutputFile
    abstract val outputLibrary: RegularFileProperty

    @get:Input
    @get:Optional
    abstract val jdkHome: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        val jdk = jdkHome.orNull ?: throw GradleException("Compiling screencast needs jni.h from a full JDK 21.")
        val cmake = cmakeExecutable()
        val buildDir = cmakeBuildDir.get().asFile
        val outputDir = outputLibrary.get().asFile.parentFile
        outputDir.mkdirs()
        execOperations.exec {
            commandLine(
                cmake,
                "-S", sourceDir.get().asFile.absolutePath,
                "-B", buildDir.absolutePath,
                "-A", "x64",
                "-DSCREENCAST_JDK_HOME=$jdk",
                "-DSCREENCAST_OUTPUT_DIR=${outputDir.absolutePath}",
            )
        }
        execOperations.exec {
            commandLine(cmake, "--build", buildDir.absolutePath, "--config", "Release")
        }
    }

    /** CMake on PATH, or the copy Visual Studio's C++ workload installs. */
    private fun cmakeExecutable(): String {
        System.getenv("CMAKE")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val onPath = System.getenv("PATH").orEmpty()
            .split(File.pathSeparator)
            .map { File(it, "cmake.exe") }
            .firstOrNull { it.canExecute() }
        if (onPath != null) return onPath.absolutePath
        val bundled = visualStudioHome()
            ?.let { File(it, "Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe") }
            ?.takeIf { it.canExecute() }
        return bundled?.absolutePath ?: throw GradleException(
            "Building screencast.dll needs CMake and MSVC. Install Visual Studio with the C++ workload.",
        )
    }

    private fun visualStudioHome(): String? {
        val vswhere = File(
            System.getenv("ProgramFiles(x86)").orEmpty(),
            "Microsoft Visual Studio/Installer/vswhere.exe",
        )
        if (!vswhere.canExecute()) return null
        val output = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(vswhere.absolutePath, "-latest", "-property", "installationPath")
            standardOutput = output
        }
        return output.toString().trim().takeIf { it.isNotEmpty() }
    }
}

val hostIsWindows = System.getProperty("os.name").startsWith("Win", ignoreCase = true)

val compileScreencast = tasks.register<CompileScreencastTask>("compileScreencast") {
    group = "build"
    description = "Build screencast.dll, the Windows capture and encode library, with CMake"
    onlyIf { hostIsWindows }
    sourceDir.set(layout.projectDirectory.dir("src/main/cpp"))
    cmakeBuildDir.set(layout.buildDirectory.dir("cmake"))
    outputLibrary.set(layout.buildDirectory.file("natives/screencast.dll"))
    project.extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }.orNull?.metadata?.installationPath?.asFile?.absolutePath?.let(jdkHome::set)
}

val nativeLibrary = configurations.create("nativeLibrary") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(nativeLibrary.name, compileScreencast.flatMap { it.outputLibrary }) {
        builtBy(compileScreencast)
    }
}
