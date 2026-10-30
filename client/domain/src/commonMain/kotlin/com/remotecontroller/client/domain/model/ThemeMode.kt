package com.remotecontroller.client.domain.model

enum class ThemeMode(val stored: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.stored == value } ?: System
    }
}
