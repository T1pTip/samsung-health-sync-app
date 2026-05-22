package com.moran.healthsync

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    // ---- Supabase config (publishable key, same one the PWA already uses) ----
    private val supabaseUrl = "https://pvufishqhvuhulcmlvqr.supabase.co"
    private val supabaseKey = "sb_publishable_2fjgmZhjjUuTfvp1KC35cg_eFzxE06S"
    private val table = "health_daily"
    private val daysBack = 7
    // Samsung Health's Health Connect package - filter to it so values match the Samsung app.
    private val samsungPkg = "com.sec.android.app.shealth"
    // Samsung's Steps screen DERIVES distance & active-calories from step count using fixed
    // personal coefficients. It does NOT push ActiveCalories to Health Connect, and its
    // DistanceRecord is partial - so to mirror the Steps screen 1:1 we derive both from steps.
    // Coefficients verified against two Samsung snapshots:
    //   distance: 8.83/11571 = 11.98/15711 = 0.000763 km/step (stride ~0.763 m)
    //   calories: 466/11571  = 633/15711   = 0.0403 kcal/step
    private val kmPerStep = 0.000763
    private val kcalPerStep = 0.0403

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    )

    private lateinit var status: TextView
    private val http = OkHttpClient()

    // Health Connect permission request launcher
    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(permissions)) {
                runSync()
            } else {
                setStatus("Permissions denied. Open Health Connect and grant read access, then try again.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText)
        findViewById<Button>(R.id.syncButton).setOnClickListener { onSyncClicked() }
        if (savedInstanceState == null) {
            onSyncClicked()
        }
    }

    private fun onSyncClicked() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                setStatus("Health Connect is not available on this device.")
                return
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                setStatus("Please update the Health Connect app, then try again.")
                return
            }
        }

        setStatus("Checking permissions...")
        lifecycleScope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val grantedPerms = client.permissionController.getGrantedPermissions()
                if (grantedPerms.containsAll(permissions)) {
                    runSync()
                } else {
                    requestPermissions.launch(permissions)
                }
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
            }
        }
    }

    private fun runSync() {
        setStatus("Reading Health Connect...")
        lifecycleScope.launch {
            try {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val zone = ZoneId.systemDefault()
                val rows = JSONArray()
                val preview = StringBuilder()

                for (i in (daysBack - 1) downTo 0) {
                    val date = LocalDate.now(zone).minusDays(i.toLong())
                    val start = date.atStartOfDay()
                    val end = date.plusDays(1).atStartOfDay()

                    val metricSet = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                    )
                    // Filter to Samsung Health only so totals match the Samsung app (no multi-source over-count).
                    var resp = client.aggregate(
                        AggregateRequest(
                            metrics = metricSet,
                            timeRangeFilter = TimeRangeFilter.between(start, end),
                            dataOriginFilter = setOf(DataOrigin(samsungPkg))
                        )
                    )
                    // Fallback: if Samsung wrote nothing for this day, read all sources instead.
                    val sHasData = (resp[StepsRecord.COUNT_TOTAL] ?: 0L) > 0L ||
                        (resp[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0) > 0.0
                    if (!sHasData) {
                        resp = client.aggregate(
                            AggregateRequest(
                                metrics = metricSet,
                                timeRangeFilter = TimeRangeFilter.between(start, end)
                            )
                        )
                    }

                    val steps = resp[StepsRecord.COUNT_TOTAL] ?: 0L
                    // Mirror the Samsung Steps screen exactly: derive distance & calories from steps
                    // (same as Samsung's own screen logic). Snapshot reflects Samsung at sync moment.
                    val distKm = steps * kmPerStep
                    val calories = (steps * kcalPerStep).roundToInt()
                    val distRounded = (distKm * 100).roundToInt() / 100.0

                    rows.put(
                        JSONObject()
                            .put("day", date.toString())
                            .put("steps", steps)
                            .put("distance_km", distRounded)
                            .put("calories", calories)
                    )
                    preview.append("${date}: ${steps} steps, ${distRounded} km, ${calories} kcal\n")
                }

                setStatus("Uploading to Supabase...")
                val result = upload(rows)
                setStatus("Done.\n\n$preview\nServer: $result")
            } catch (e: Exception) {
                setStatus("Error: ${e.message}")
            }
        }
    }

    private suspend fun upload(rows: JSONArray): String = withContext(Dispatchers.IO) {
        val body = rows.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/$table?on_conflict=day")
            .addHeader("apikey", supabaseKey)
            .addHeader("Authorization", "Bearer $supabaseKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(body)
            .build()
        http.newCall(request).execute().use { r ->
            if (r.isSuccessful) "OK (${r.code})" else "FAILED (${r.code}) ${r.body?.string()?.take(200)}"
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { status.text = text }
    }
}


