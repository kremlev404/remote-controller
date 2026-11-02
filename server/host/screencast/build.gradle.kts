import com.remotecontroller.buildlogic.CompileNativeLibrary
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.remotecontroller.jvm.library)
}

val compileScreencast = tasks.register<CompileNativeLibrary>("compileScreencast") {
    group = "build"
    description = "Build the screencast capture and encode library for this OS with CMake"
    sourceDir.set(layout.projectDirectory.dir("src/main/cpp"))
    cmakeBuildDir.set(layout.buildDirectory.dir("cmake"))
    outputLibrary.set(layout.buildDirectory.file("natives/${System.mapLibraryName("screencast")}"))
    jdkVariable.set("SCREENCAST_JDK_HOME")
    outputVariable.set("SCREENCAST_OUTPUT_DIR")
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
