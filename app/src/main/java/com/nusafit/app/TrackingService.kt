package com.nusafit.app

import android.app.*
import android.content.Intent
import android.app.PendingIntent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import android.location.Location
import kotlin.math.max

class TrackingService : Service() {
    private lateinit var fused: FusedLocationProviderClient
    private val points = mutableListOf<Point>()
    private var last: Location? = null
    private var distance = 0.0
    private var start = 0L
    private var profile = Profile()
    private var name = "Aktivitas"
    private var type = "Running"
    private var started = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.locations.forEach { loc ->
                val now = System.currentTimeMillis()
                last?.let { distance += it.distanceTo(loc).toDouble() / 1000.0 }
                last = loc
                points.add(Point(loc.latitude, loc.longitude, now))
                broadcast()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fused = LocationServices.getFusedLocationProviderClient(this)
        profile = Store.profile(this)
        createChannel()
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            "START" -> startTracking(i.getStringExtra("name") ?: "Aktivitas", i.getStringExtra("type") ?: "Running")
            "STOP" -> stopTracking()
        }
        return START_STICKY
    }

    private fun startTracking(n: String, t: String) {
        if (started) return
        name = n; type = t; start = System.currentTimeMillis(); points.clear(); distance = 0.0; last = null
        startForeground(7, notification())
        started = true
        getSharedPreferences("nusafit", MODE_PRIVATE).edit().putBoolean("tracking_active", true).apply()
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateDistanceMeters(3f)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fused.requestLocationUpdates(req, callback, mainLooper)
            broadcast()
        } catch (_: SecurityException) {
            stopTracking()
        }
    }

    private fun stopTracking() {
        if (!started) { stopSelf(); return }
        runCatching { fused.removeLocationUpdates(callback) }
        val end = System.currentTimeMillis()
        val hours = max(1L, end - start) / 3600000.0
        val met = when (type.lowercase()) { "cycling" -> 8.0; "walking" -> 3.5; "hiking" -> 6.0; else -> 8.0 }
        val kcal = met * profile.weightKg * hours
        val fat = kcal * 0.11 / 9.0
        val a = ActivityRecord(java.util.UUID.randomUUID().toString(), name, type, start, end, distance, kcal, fat, points.toList(), emptyList())
        Store.addActivity(this, a)
        getSharedPreferences("nusafit", MODE_PRIVATE).edit().putBoolean("tracking_active", false).apply()
        sendBroadcast(Intent("NUSAFIT_STOPPED").setPackage(packageName).putExtra("id", a.id))
        started = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcast() {
        sendBroadcast(Intent("NUSAFIT_TRACK").setPackage(packageName).apply {
            putExtra("distance", distance)
            putExtra("duration", if (start == 0L) 0L else System.currentTimeMillis() - start)
            putExtra("lat", last?.latitude ?: 0.0)
            putExtra("lng", last?.longitude ?: 0.0)
            putExtra("points", points.size)
        })
    }

    private fun notification() = NotificationCompat.Builder(this, "tracking")
        .setContentIntent(PendingIntent.getActivity(this, 7, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setContentTitle("NusaFit • Tracking aktif")
        .setContentText("GPS tetap berjalan saat Anda kembali ke Home")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("tracking", "NusaFit Tracking", NotificationManager.IMPORTANCE_LOW).apply { description = "Notifikasi permanen saat tracking GPS aktif" }
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not stop the service when the user swipes NusaFit away from Recents.
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(i: Intent?): IBinder? = null
}
