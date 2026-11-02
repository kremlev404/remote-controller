package com.remotecontroller.buildlogic

import org.gradle.api.logging.Logger
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ProGuard rewrites class files, so a signed jar (BouncyCastle) no longer matches its signature and
 * the JVM refuses to load it ("SHA-256 digest error"). The bundled OpenJDK accepts an unsigned
 * BouncyCastle for what this app does with it, so the stale signature files are dropped.
 */
fun dropJarSignature(jar: File, logger: Logger) {
    val signature = Regex("""META-INF/[^/]+\.(SF|RSA|DSA|EC)""", RegexOption.IGNORE_CASE)
    val signed = ZipInputStream(jar.inputStream()).use { input ->
        generateSequence { input.nextEntry }.any { signature.matches(it.name) }
    }
    if (!signed) return
    val stripped = File(jar.parentFile, jar.name + ".unsigned")
    ZipInputStream(jar.inputStream()).use { input ->
        ZipOutputStream(stripped.outputStream()).use { output ->
            generateSequence { input.nextEntry }
                .filterNot { signature.matches(it.name) }
                .forEach { entry ->
                    output.putNextEntry(ZipEntry(entry.name).apply { time = entry.time })
                    input.copyTo(output)
                    output.closeEntry()
                }
        }
    }
    jar.delete()
    stripped.renameTo(jar)
    logger.lifecycle("Dropped the jar signature from ${jar.name} (ProGuard changed its classes)")
}
