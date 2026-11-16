package com.mambadoc.app.data.rag

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Optional foreground service stub for long-running indexing.
 * The current implementation uses coroutines inside the UI layer;
 * this service is declared in the manifest for future WorkManager / FGS use.
 */
class IndexingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Real work is currently performed by DocumentIngester in a coroutine.
        stopSelf()
        return START_NOT_STICKY
    }
}
