package de.williserv.regattaclient

import android.app.Application

class RegattaApplication : Application() {
    private lateinit var raceSignalController: RaceSignalController

    override fun onCreate() {
        super.onCreate()
        raceSignalController = RaceSignalController(this).also { it.start() }
        TelemetryUploadScheduler.enqueueIfNeeded(this)
    }
}
