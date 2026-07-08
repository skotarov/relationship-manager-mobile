package com.onlineimoti.calllog

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity

/**
 * App-only text scaling. Today it exposes Normal and 2x, but the stored value is
 * a multiplier so later profiles such as 1.25x or separate screen-specific sizes
 * can reuse the same hook without rewriting every Activity.
 */
object AppFontScale {
    const val NORMAL = 1.0f
    const val LARGE = 2.0f

    fun normalize(value: Float): Float = when {
        value >= 1.75f -> LARGE
        else -> NORMAL
    }

    fun isLarge(value: Float): Boolean = normalize(value) == LARGE

    fun wrap(base: Context): Context {
        val scale = normalize(ConfigStore.load(base.applicationContext).uiFontScaleMultiplier)
        if (scale == NORMAL) return base
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
