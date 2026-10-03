package com.vcorr.claudewatch

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.resume

/**
 * Receives today's activity totals (steps, and distance, calories and floors where the watch
 * offers them) from Health Services in the background, in batches, and keeps the latest of each so
 * the get_activity_today tool can answer at once. There is no one-off "today so far" query on
 * Wear OS; this passive feed is the documented way to get it.
 */
class StepsService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val bootInstant = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val edit = prefs.edit()
        save(prefs, edit, dataPoints, DataType.STEPS_DAILY, KEY_STEPS, KEY_AT, bootInstant)
        save(prefs, edit, dataPoints, DataType.DISTANCE_DAILY, KEY_DISTANCE, KEY_DISTANCE + AT, bootInstant)
        save(prefs, edit, dataPoints, DataType.CALORIES_DAILY, KEY_CALORIES, KEY_CALORIES + AT, bootInstant)
        save(prefs, edit, dataPoints, DataType.FLOORS_DAILY, KEY_FLOORS, KEY_FLOORS + AT, bootInstant)
        edit.apply()
    }

    /** Keeps the newest total of one kind; batches can arrive out of order, so an older one never wins. */
    private fun <T : Number> save(
        prefs: SharedPreferences,
        edit: SharedPreferences.Editor,
        dataPoints: DataPointContainer,
        type: DeltaDataType<T, IntervalDataPoint<T>>,
        key: String,
        atKey: String,
        bootInstant: Instant,
    ) {
        val latest = dataPoints.getData(type).maxByOrNull { it.endDurationFromBoot } ?: return
        val at = latest.getEndInstant(bootInstant).toEpochMilli()
        if (at < prefs.getLong(atKey, 0)) return
        // Steps stay a whole number, as before; the rest are fractional.
        if (key == KEY_STEPS) edit.putLong(key, latest.value.toLong()) else edit.putFloat(key, latest.value.toFloat())
        edit.putLong(atKey, at)
    }

    companion object {
        const val PREFS = "steps"
        const val KEY_STEPS = "daily"
        const val KEY_AT = "at"
        const val KEY_DISTANCE = "distance"
        const val KEY_CALORIES = "calories"
        const val KEY_FLOORS = "floors"
        const val AT = "_at"
        private const val FLUSH_TIMEOUT_MS = 3_000L

        private val DAILY_TYPES = setOf(DataType.STEPS_DAILY, DataType.DISTANCE_DAILY, DataType.CALORIES_DAILY, DataType.FLOORS_DAILY)

        /**
         * Asks Health Services to send today's totals to this service, those this watch supports.
         * Registrations don't survive a reboot, so this runs on every launch; repeating it is harmless.
         */
        fun register(context: Context) {
            val app = context.applicationContext
            if (!app.hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) return
            try {
                val client = HealthServices.getClient(app).passiveMonitoringClient
                val capabilities = client.getCapabilitiesAsync()
                capabilities.addListener(
                    {
                        // Asking for a type the watch lacks would refuse the lot; unknown support asks for all.
                        val supported = runCatching { capabilities.get().supportedDataTypesPassiveMonitoring }.getOrNull()
                        val types = DAILY_TYPES.filter { supported == null || it in supported }.toSet()
                        if (types.isEmpty()) return@addListener
                        val config = PassiveListenerConfig.builder().setDataTypes(types).build()
                        val result = client.setPassiveListenerServiceAsync(StepsService::class.java, config)
                        // A watch that can't supply these refuses here; note it rather than fail silently.
                        result.addListener(
                            { runCatching { result.get() }.onFailure { Log.d(TAG, "activity registration refused: ${it.javaClass.simpleName}") } },
                            app.mainExecutor,
                        )
                    },
                    app.mainExecutor,
                )
            } catch (e: Exception) {
                Log.d(TAG, "activity registration failed: ${e.javaClass.simpleName}")
            }
        }

        /**
         * Asks Health Services to deliver any totals it is holding back, rather than at the next
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
            Log.d(TAG, "activity flush failed: ${e.javaClass.simpleName}")
            false
        }
    }
}
