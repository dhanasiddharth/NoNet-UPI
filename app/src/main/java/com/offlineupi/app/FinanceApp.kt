package com.offlineupi.app

import android.app.Application
import com.offlineupi.app.util.ThemePref

/** Applies the saved appearance (System/Light/Dark) before any activity shows. */
class FinanceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePref.apply(this)
    }
}
