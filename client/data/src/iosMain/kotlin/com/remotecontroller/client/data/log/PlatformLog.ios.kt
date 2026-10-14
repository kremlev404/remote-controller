@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.remotecontroller.client.data.log

import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import platform.Foundation.NSLog

internal actual fun logInfo(tag: String, message: String) {
    nslog("[$tag] $message")
}

internal actual fun logWarn(tag: String, message: String, error: Throwable?) {
    val detail = error?.let { " (${it::class.simpleName}: ${it.message})" }.orEmpty()
    nslog("[$tag] $message$detail")
}

private fun nslog(line: String) {
    // NSLog is variadic, so a Kotlin String is not bridged to an object.
    // Passing it with %@ makes NSLog dereference the character bytes (EXC_BAD_ACCESS).
    memScoped {
        NSLog("%s", line.cstr.ptr)
    }
}
