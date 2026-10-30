package com.tu.ffassist

import android.content.Context

object Prefs {
    private const val NAME = "ffassist"

    fun getInt(c: Context, key: String, def: Int) =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt(key, def)

    fun setInt(c: Context, key: String, v: Int) =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt(key, v).apply()

    fun getLong(c: Context, key: String, def: Long) =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getLong(key, def)

    fun setLong(c: Context, key: String, v: Long) =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putLong(key, v).apply()

    const val K_STRENGTH = "strength"
    const val K_INTERVAL = "interval"
    const val K_RADIUS = "radius"
    const val K_COLOR = "color"
}
