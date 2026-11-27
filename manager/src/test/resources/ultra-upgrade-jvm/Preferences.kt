package dev.maplesu.manager.compat
import android.content.Context
fun Context.ultraPreferences(name: String, mode: Int) = getSharedPreferences(name, mode)
