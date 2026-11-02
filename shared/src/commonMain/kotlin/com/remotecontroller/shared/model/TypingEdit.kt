package com.remotecontroller.shared.model

/**
 * What to press on the PC so the text typed so far changes from [before] to [after].
 * Works for keyboards that rewrite whole words. Null when nothing changed.
 */
fun typingEdit(before: String, after: String): RemoteCommand.TypeText? {
    if (before == after) return null
    val kept = before.commonPrefixWith(after).length
    val removed = before.substring(kept)
    return RemoteCommand.TypeText(
        text = after.substring(kept),
        backspaces = removed.length - removed.count { it.isLowSurrogate() },
    )
}
