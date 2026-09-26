package com.whoop5.app

import android.app.Application

/** Holds the BLE connection so it survives activity recreation (rotation, dark mode). */
class WhoopApp : Application() {
    val ble by lazy { WhoopBle(this) }
}
