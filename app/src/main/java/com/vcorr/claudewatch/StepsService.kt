package com.vcorr.claudewatch

import android.Manifest
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.resume

/**
 * Receives today's step count from Health Services in the background, in batches, and keeps the
 * latest so the get_steps tool can answer at once. There is no one-off "steps today" query on
 * Wear OS; this passive feed is the documented way to get it.
 */
class StepsService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val latest = dataPoints.getData(DataType.STEPS_DAILY).maxByOrNull { it.endDurationFromBoot } ?: return
        val at = latest.getEndInstant(bootInstant).toEpochMilli()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Batches can arrive out of order; never let an older count replace a newer one.
        if (at < prefs.getLong(KEY_AT, 0)) return
        prefs.edit().putLong(KEY_STEPS, latest.value).putLong(KEY_AT, at).apply()
    }

    companion object {
        const val PREFS = "steps"
        const val KEY_STEPS = "daily"
        const val KEY_AT = "at"
        private const val FLUSH_TIMEOUT_MS = 3_000L

        /**
         * Asks Health Services to send daily steps to this service. Registrations don't survive a
         * reboot, so this runs on every launch; repeating it is harmless.
         */
        fun register(context: Context) {
            val app = context.applicationContext
            if (!app.hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) return
            try {
                val config = PassiveListenerConfig.builder()
                    .setDataTypes(setOf(DataType.STEPS_DAILY))
                    .build()
                val result = HealthServices.getClient(app).passiveMonitoringClient
                    .setPassiveListenerServiceAsync(StepsService::class.java, config)
                // A watch that can't supply daily steps refuses here; note it rather than fail silently.
                result.addListener(
                    { runCatching { result.get() }.onFailure { Log.d(TAG, "steps registration refused: ${it.javaClass.simpleName}") } },
                    app.mainExecutor,
                )
            } catch (e: Exception) {
                Log.d(TAG, "steps registration failed: ${e.javaClass.simpleName}")
            }
        }

        /**
         * Asks Health Services to deliver any steps it is holding back, rather than at the next
         * batch, and waits (up to a few seconds) until it has done so. True if the flush went through.
         */
        suspend fun flush(context: Context): Boolean = try {
            val future = HealthServices.getClient(context.applicationContext).passiveMonitoringClient.flushAsync()
            withTimeoutOrNull(FLUSH_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    future.addListener(
                        { if (cont.isActive) cont.resume(runCatching { future.get() }.isSuccess) },
                        context.applicationContext.mainExecutor,
                    )
                    cont.invokeOnCancellation { future.cancel(false) }
                }
            } ?: false
        } catch (e: Exception) {
            Log.d(TAG, "steps flush failed: ${e.javaClass.simpleName}")
            false
        }
    }
}
