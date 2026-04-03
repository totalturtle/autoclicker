package com.autoclicker

enum class GestureType {
    TAP,
    LONG_PRESS,
    SWIPE;

    fun toJsonKey(): String = when (this) {
        TAP -> "tap"
        LONG_PRESS -> "long"
        SWIPE -> "swipe"
    }

    companion object {
        fun fromJsonKey(s: String?): GestureType = when (s) {
            "long" -> LONG_PRESS
            "swipe" -> SWIPE
            else -> TAP
        }
    }
}
