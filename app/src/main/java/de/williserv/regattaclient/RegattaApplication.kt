package de.williserv.regattaclient

import android.app.Application

class RegattaApplication : Application() {
    private lateinit var raceSignalController: RaceSignalController

    lateinit var regattaLinkConnectionManager: RegattaLinkConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        regattaLinkConnectionManager = RegattaLinkConnectionManager(this)
        raceSignalController = RaceSignalController(this).also { it.start() }
        TelemetryUploadScheduler.enqueueRecoveryIfNeeded(this)
    }
}
