package com.example.binancevolumealert

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity() {

    // ── View-uri 1H ──────────────────────────────────────────────────────────
    private lateinit var content1h:   View
    private lateinit var btnToggle:   MaterialButton
    private lateinit var btnTest:     MaterialButton
    private lateinit var tvStatus:    TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var rvAlerts1h:  RecyclerView
    private lateinit var empty1h:     View

    // ── View-uri 5M ──────────────────────────────────────────────────────────
    private lateinit var content5m:     View
    private lateinit var btnToggle5m:   MaterialButton
    private lateinit var btnTest5m:     MaterialButton
    private lateinit var tvStatus5m:    TextView
    private lateinit var progressBar5m: ProgressBar
    private lateinit var rvAlerts5m:    RecyclerView
    private lateinit var empty5m:       View

    // ── Comune ────────────────────────────────────────────────────────────────
    private lateinit var tabLayout:  TabLayout
    private lateinit var adapter1h:  AlertAdapter
    private lateinit var adapter5m:  AlertAdapter

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "alerts_list") refreshAlerts()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        NotificationHelper.createNotificationChannel(this)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

        bindViews()
        setupAdapters()
        setupTabs()
        setupWorkManagerObservers()
        setupClickListeners()
        updateToggleUi1h()
        updateToggleUi5m()
    }

    private fun bindViews() {
        tabLayout    = findViewById(R.id.tabLayout)

        // 1H
        content1h    = findViewById(R.id.content1h)
        btnToggle    = findViewById(R.id.btnToggle)
        btnTest      = findViewById(R.id.btnTest)
        tvStatus     = findViewById(R.id.tvStatus)
        progressBar  = findViewById(R.id.progressBar)
        rvAlerts1h   = findViewById(R.id.rvAlerts1h)
        empty1h      = findViewById(R.id.empty1h)

        // 5M
        content5m    = findViewById(R.id.content5m)
        btnToggle5m  = findViewById(R.id.btnToggle5m)
        btnTest5m    = findViewById(R.id.btnTest5m)
        tvStatus5m   = findViewById(R.id.tvStatus5m)
        progressBar5m = findViewById(R.id.progressBar5m)
        rvAlerts5m   = findViewById(R.id.rvAlerts5m)
        empty5m      = findViewById(R.id.empty5m)
    }

    private fun setupAdapters() {
        adapter1h = AlertAdapter("1H")
        adapter5m = AlertAdapter("5M")

        rvAlerts1h.layoutManager = LinearLayoutManager(this)
        rvAlerts1h.adapter = adapter1h

        rvAlerts5m.layoutManager = LinearLayoutManager(this)
        rvAlerts5m.adapter = adapter5m
    }

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("1H"))
        tabLayout.addTab(tabLayout.newTab().setText("5M"))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                content1h.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                content5m.visibility = if (tab.position == 1) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun setupWorkManagerObservers() {
        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(VolumeCheckWorker.WORK_TAG)
            .observe(this) { infos ->
                val running = infos.any {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                progressBar.visibility = if (running) View.VISIBLE else View.GONE
            }

        WorkManager.getInstance(this)
            .getWorkInfosByTagLiveData(VolumeCheck5mWorker.WORK_TAG)
            .observe(this) { infos ->
                val running = infos.any {
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
                }
                progressBar5m.visibility = if (running) View.VISIBLE else View.GONE
            }
    }

    private fun setupClickListeners() {
        btnToggle.setOnClickListener   { onToggle1hClicked() }
        btnTest.setOnClickListener     { runWorkerNow<VolumeCheckWorker>(VolumeCheckWorker.WORK_TAG) }
        btnToggle5m.setOnClickListener { onToggle5mClicked() }
        btnTest5m.setOnClickListener   { runWorkerNow<VolumeCheck5mWorker>(VolumeCheck5mWorker.WORK_TAG) }
    }

    override fun onResume() {
        super.onResume()
        getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
        refreshAlerts()
        updateToggleUi1h()
        updateToggleUi5m()
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // ── Toggle 1H ────────────────────────────────────────────────────────────
    private fun onToggle1hClicked() {
        if (AlarmScheduler.isActive(this)) {
            AlarmScheduler.cancel(this); updateToggleUi1h(); return
        }
        if (!AlarmScheduler.canScheduleExact(this)) {
            openExactAlarmSettings()
            tvStatus.text = "Activează \"Alarme și mementouri\", apoi apasă PORNIRE"
            return
        }
        AlarmScheduler.scheduleNext(this)
        updateToggleUi1h()
    }

    // ── Toggle 5M ────────────────────────────────────────────────────────────
    private fun onToggle5mClicked() {
        if (AlarmScheduler5m.isActive(this)) {
            AlarmScheduler5m.cancel(this); updateToggleUi5m(); return
        }
        if (!AlarmScheduler5m.canScheduleExact(this)) {
            openExactAlarmSettings()
            tvStatus5m.text = "Activează \"Alarme și mementouri\", apoi apasă PORNIRE"
            return
        }
        AlarmScheduler5m.scheduleNext(this)
        updateToggleUi5m()
    }

    // ── UI state 1H ───────────────────────────────────────────────────────────
    private fun updateToggleUi1h() {
        val active = AlarmScheduler.isActive(this)
        btnToggle.text = if (active) "OPRIRE" else "PORNIRE"
        btnToggle.icon = ContextCompat.getDrawable(this,
            if (active) R.drawable.ic_stop else R.drawable.ic_play)
        btnToggle.backgroundTintList = ContextCompat.getColorStateList(this,
            if (active) R.color.accent_red else R.color.accent_green)
        tvStatus.text = if (active) "Activ · verificare la oră fixă" else "Monitorizare oprită"
    }

    // ── UI state 5M ───────────────────────────────────────────────────────────
    private fun updateToggleUi5m() {
        val active = AlarmScheduler5m.isActive(this)
        btnToggle5m.text = if (active) "OPRIRE" else "PORNIRE"
        btnToggle5m.icon = ContextCompat.getDrawable(this,
            if (active) R.drawable.ic_stop else R.drawable.ic_play)
        btnToggle5m.backgroundTintList = ContextCompat.getColorStateList(this,
            if (active) R.color.accent_red else R.color.accent_green)
        tvStatus5m.text = if (active) "Activ · verificare la fiecare 5 minute" else "Monitorizare oprită"
    }

    // ── Refresh alerte ────────────────────────────────────────────────────────
    private fun refreshAlerts() {
        AlertStorage.pruneOldAlerts(this)
        AlertStorage.pruneOldAlerts5m(this)
        val allAlerts = AlertStorage.getAlerts(this)

        val count1h = adapter1h.submitAlerts(allAlerts)
        val count5m = adapter5m.submitAlerts(allAlerts)

        // Vizibilitate 1H
        rvAlerts1h.visibility = if (count1h > 0) View.VISIBLE else View.GONE
        empty1h.visibility    = if (count1h > 0) View.GONE    else View.VISIBLE

        // Vizibilitate 5M
        rvAlerts5m.visibility = if (count5m > 0) View.VISIBLE else View.GONE
        empty5m.visibility    = if (count5m > 0) View.GONE    else View.VISIBLE

        // Badge pe tab cu numărul de alerte din fiecare
        updateTabBadge(0, count1h)
        updateTabBadge(1, count5m)
    }

    private fun updateTabBadge(tabIndex: Int, count: Int) {
        val tab = tabLayout.getTabAt(tabIndex) ?: return
        if (count > 0) {
            val badge = tab.orCreateBadge
            badge.number = count
            badge.backgroundColor = if (tabIndex == 0)
                getColor(R.color.accent_green)
            else
                0xFFFF8C00.toInt()
        } else {
            tab.removeBadge()
        }
    }

    // ── Worker manual ─────────────────────────────────────────────────────────
    private inline fun <reified T : androidx.work.ListenableWorker> runWorkerNow(tag: String) {
        val request = OneTimeWorkRequestBuilder<T>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .addTag(tag)
            .build()
        WorkManager.getInstance(this).enqueue(request)
    }

    private fun openExactAlarmSettings() {
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:$packageName")
        })
    }
}