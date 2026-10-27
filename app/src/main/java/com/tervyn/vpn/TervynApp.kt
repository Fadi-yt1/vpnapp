package com.tervyn.vpn

import android.app.Application
import com.tervyn.vpn.core.Core

class TervynApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }
}
