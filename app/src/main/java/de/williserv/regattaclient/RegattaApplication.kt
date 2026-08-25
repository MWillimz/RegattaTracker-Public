package de.williserv.regattaclient

import android.app.Application

class RegattaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TelemetryUploadScheduler.enqueueIfNeeded(this)
    }
}
