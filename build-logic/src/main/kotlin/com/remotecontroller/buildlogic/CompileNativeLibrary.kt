package com.remotecontroller.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/** Builds a JNI library with CMake. The project gets the JDK and the output folder through the two named variables. */
abstract class CompileNativeLibrary : DefaultTask() {
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

    @get:Input
    abstract val jdkVariable: Property<String>

    @get:Input
    abstract val outputVariable: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun compile() {
        val jdk = jdkHome.orNull?.takeIf { it.isNotBlank() } ?: throw GradleException(
            """
            Compiling ${outputLibrary.get().asFile.name} needs jni.h from a full JDK.
            Gradle is running on ${System.getProperty("java.home")}, which may have no include/jni.h.
            Install Temurin 21, or set JAVA_HOME / desktop.jdk.home to a JDK that contains include/jni.h.
            """.trimIndent(),
        )
        val cmake = cmakeExecutable()
        val buildDir = cmakeBuildDir.get().asFile
        val outputDir = outputLibrary.get().asFile.parentFile
        outputDir.mkdirs()
        // Visual Studio picks the build type at build time, the other generators here.
        val platform = if (hostOs == HostOs.WINDOWS) listOf("-A", "x64") else listOf("-DCMAKE_BUILD_TYPE=Release")
        execOperations.exec {
            commandLine(
                listOf(cmake, "-S", sourceDir.get().asFile.absolutePath, "-B", buildDir.absolutePath) +
                    platform +
                    listOf("-D${jdkVariable.get()}=$jdk", "-D${outputVariable.get()}=${outputDir.absolutePath}"),
            )
        }
        execOperations.exec {
            commandLine(cmake, "--build", buildDir.absolutePath, "--config", "Release")
        }
    }

    /** $CMAKE, CMake on PATH, or the copy Visual Studio's C++ workload installs. */
    private fun cmakeExecutable(): String {
        System.getenv("CMAKE")?.trim()?.takeIf { it.isNotEmpty() }?.let { override ->
            if (!File(override).canExecute()) throw GradleException("CMAKE is set to $override, but that file is not executable.")
            return override
        }
        val windows = hostOs == HostOs.WINDOWS
        val name = if (windows) "cmake.exe" else "cmake"
        // An IDE started from the Dock does not get the shell's PATH.
        val folders = System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin")
        val onPath = folders.map { File(it, name) }.firstOrNull { it.canExecute() }
        if (onPath != null) return onPath.absolutePath
        if (!windows) throw GradleException("Building native libraries needs CMake on PATH.")
        val bundled = visualStudioHome()
            ?.let { File(it, "Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe") }
            ?.takeIf { it.canExecute() }
        return bundled?.absolutePath ?: throw GradleException(
            "Building native libraries needs CMake and MSVC. Install Visual Studio with the C++ workload.",
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
