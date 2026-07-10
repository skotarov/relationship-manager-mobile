package com.onlineimoti.calllog

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Dedicated storage so future font-size profiles can evolve separately from server settings. */
object AppFontScaleStore {
    private const val PREFS = "relationship_manager_font_scale"
    private const val KEY_MULTIPLIER = "multiplier"
    const val SMALL = 1.0f
    const val NORMAL = 1.15f
    const val LARGE = 1.3f
    /** Legacy aliases retained so older code paths and stored values remain safe. */
    const val LARGER = NORMAL
    const val LARGEST = LARGE

    fun loadMultiplier(context: Context): Float {
        return normalize(context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_MULTIPLIER, NORMAL))
    }

    fun saveMultiplier(context: Context, multiplier: Float) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_MULTIPLIER, normalize(multiplier))
            .apply()
    }

    fun normalize(value: Float): Float = when {
        value >= 1.225f -> LARGE
        value > 1.075f -> NORMAL
        else -> SMALL
    }
}

/**
 * App-only text scaling. It exposes Small, Normal, and Large. Normal is the
 * middle/default profile installed with the app, while Small maps to the phone's
 * unmodified text size.
 */
object AppFontScale {
    fun wrap(base: Context): Context {
        val scale = AppFontScaleStore.loadMultiplier(base)
        if (scale == AppFontScaleStore.SMALL) return base
        val configuration = Configuration(base.resources.configuration).apply {
            fontScale = (fontScale * scale).coerceIn(0.5f, AppFontScaleStore.LARGE)
        }
        return base.createConfigurationContext(configuration)
    }
}

open class FontScaledAppCompatActivity : AppCompatActivity() {
    private var appliedScale = AppFontScaleStore.NORMAL

    override fun attachBaseContext(newBase: Context) {
        appliedScale = AppFontScaleStore.loadMultiplier(newBase)
        super.attachBaseContext(AppFontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedScale = AppFontScaleStore.loadMultiplier(this)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        recreateIfFontScaleChanged()
    }

    private fun recreateIfFontScaleChanged() {
        val current = AppFontScaleStore.loadMultiplier(this)
        if (current == appliedScale) return
        appliedScale = current
        recreate()
    }
}

open class FontScaledActivity : Activity() {
    private var appliedScale = AppFontScaleStore.NORMAL

    override fun attachBaseContext(newBase: Context) {
        appliedScale = AppFontScaleStore.loadMultiplier(newBase)
        super.attachBaseContext(AppFontScale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appliedScale = AppFontScaleStore.loadMultiplier(this)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        recreateIfFontScaleChanged()
    }

    private fun recreateIfFontScaleChanged() {
        val current = AppFontScaleStore.loadMultiplier(this)
        if (current == appliedScale) return
        appliedScale = current
        recreate()
    }
}

abstract class FontScaledService : Service() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppFontScale.wrap(newBase))
    }
}
