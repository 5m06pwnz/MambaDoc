package com.mambadoc.app

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import com.mambadoc.app.data.db.DocDatabase
import com.mambadoc.app.data.model.AppSettings
import com.mambadoc.app.data.model.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MambaDocApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: DocDatabase
        private set

    lateinit var settingsRepo: SettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = DocDatabase.getInstance(this)
        settingsRepo = SettingsRepository(this)

        // Pre-warm memory info
        applicationScope.launch {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            // Available for Low-RAM decisions later
        }
    }

    companion object {
        lateinit var instance: MambaDocApp
            private set
    }
}
