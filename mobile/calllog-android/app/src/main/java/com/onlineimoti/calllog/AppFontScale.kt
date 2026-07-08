package com.onlineimoti.calllog

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/** Dedicated storage so future font-size profiles can evolve separately from server settings. */
object AppFontScaleStore {
    private const val PREFS = "relationship_manager_font_scale"
    private const val KEY_MULTIPLIER = "multiplier"
    const val NORMAL = 1.0f
    const val LARGE = 2.0f

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

    fun normalize(value: Float): Float = if (value >= 1.75f) LARGE else NORMAL
    fun isLarge(context: Context): Boolean = loadMultiplier(context) == LARGE
}

/**
 * App-only text scaling. Today it exposes Normal and 2x, but the stored value is
 * a multiplier so later profiles such as 1.25x or separate screen-specific sizes
 * can reuse the same hook without rewriting every Activity.
 */
object AppFontScale {
    fun wrap(base: Context): Context {
        val scale = AppFontScaleStore.loadMultiplier(base)
        if (scale == AppFontScaleStore.NORMAL) return base
        val configuration = Configuration(base.resources.configuration).apply {
            fontScale = (fontScale * scale).coerceIn(0.5f, 3.0f)
        }
        return base.createConfigurationContext(configuration)
    }
}

open class FontScaledAppCompatActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppFontScale.wrap(newBase))
    }
}

open class FontScaledActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppFontScale.wrap(newBase))
    }
}

open class FontScaledService : Service() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppFontScale.wrap(newBase))
    }
}
