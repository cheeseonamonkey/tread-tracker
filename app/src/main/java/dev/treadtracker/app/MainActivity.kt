package dev.treadtracker.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.app.NotificationManager
import android.os.Looper
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import dev.treadtracker.app.data.StepDb
import dev.treadtracker.app.databinding.ActivityMainBinding
import dev.treadtracker.app.databinding.PageStatsBinding
import dev.treadtracker.app.sensor.StepRecorder
import dev.treadtracker.app.util.Dates
import dev.treadtracker.app.util.NumFmt
import dev.treadtracker.app.util.Prefs
import java.util.concurrent.Executors

/**
 * Main screen: Day / Week / Month tabs, each a swipeable pager of periods
 * with a bar graph and stats. Rebuilt from the original MainActivity
 * (decompiled pd/am0/zt2 cluster) without any of the ad/analytics code.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_DAY = 0
        const val TAB_WEEK = 1
        const val TAB_MONTH = 2
    }

    data class Period(
        val type: Int,
        val start: Long,
        val end: Long,
        val title: String
    )

    data class PageData(
        val steps: Long,
        val cal: Double,
        val walkTimeMs: Long,
        val distanceMeters: Double,
        val bars: FloatArray,
        val labels: Array<String>,
        val labelStride: Int,
        val highlight: Int,
        val includesToday: Boolean
    )

    private lateinit var binding: ActivityMainBinding
    private val db = lazy { StepDb(this) }
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recorder by lazy { StepRecorder(this, db.value, dbExecutor) {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                if (periods.lastOrNull()?.end?.let { it < Dates.today() } == true) reload()
                else refreshCurrentPage()
            }
        }
    } }

    private var currentTab = TAB_DAY
    private val savedPositions = IntArray(3)
    private var periods: List<Period> = emptyList()
    private val pageCache = HashMap<Period, PageData>()
    private var reloadScheduled = false

    private val pagesAdapter = PagesAdapter()

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshPermissionBanner()
        }

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.pager.adapter = pagesAdapter
        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                savedPositions[currentTab] = position
            }
        })

        val tabs = arrayOf(binding.tabDay, binding.tabWeek, binding.tabMonth)
        for (i in tabs.indices) {
            tabs[i].setOnClickListener { switchTab(i) }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnGrant.setOnClickListener { requestNeededPermissions() }

        // Clear any v1.0 foreground notification/channel after upgrading.
        getSystemService(NotificationManager::class.java)?.apply {
            cancel(1564)
            if (Build.VERSION.SDK_INT >= 26) deleteNotificationChannel("step_service")
        }

        styleTabs()
        requestNeededPermissions()
    }

    override fun onStart() {
        super.onStart()
        refreshPermissionBanner()
        if (hasRecognition()) recorder.start()
        reload()
    }

    override fun onStop() {
        recorder.stop()
        super.onStop()
    }

    override fun onDestroy() {
        dbExecutor.shutdown()
        try {
            dbExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
        if (db.isInitialized()) db.value.close()
        super.onDestroy()
    }

    // ----------------------------------------------------------------- tabs

    private fun switchTab(index: Int) {
        if (index == currentTab) return
        savedPositions[currentTab] = binding.pager.currentItem
        currentTab = index
        styleTabs()
        reload()
    }

    private fun styleTabs() {
        val tabs = arrayOf(binding.tabDay, binding.tabWeek, binding.tabMonth)
        val indicators = arrayOf(binding.indicatorDay, binding.indicatorWeek, binding.indicatorMonth)
        for (i in tabs.indices) {
            val selected = i == currentTab
            tabs[i].setTextColor(
                ContextCompat.getColor(
                    this,
                    if (selected) R.color.tab_selected else R.color.text_secondary
                )
            )
            indicators[i].setBackgroundResource(
                if (selected) R.drawable.tab_indicator else 0
            )
        }
    }

    // ------------------------------------------------------------ data load

    private fun reload() {
        if (reloadScheduled) return
        reloadScheduled = true
        val tab = currentTab
        dbExecutor.execute {
            val newPeriods = buildPeriods(tab)
            mainHandler.post {
                reloadScheduled = false
                if (tab != currentTab || isFinishing) return@post
                periods = newPeriods
                pageCache.clear()
                pagesAdapter.submitPeriods(newPeriods)
                val pos = if (savedPositions[tab] in newPeriods.indices) {
                    savedPositions[tab]
                } else {
                    newPeriods.lastIndex
                }
                binding.pager.setCurrentItem(pos, false)
            }
        }
    }

    private fun buildPeriods(tab: Int): List<Period> {
        val today = Dates.today()
        val dates = db.value.distinctDates().toMutableList()
        if (dates.isEmpty() || dates.last() != today) dates.add(today)

        return when (tab) {
            TAB_WEEK -> dates
                .map { Dates.weekStart(it, Prefs.firstDayOfWeek(this)) }
                .toSortedSet()
                .map { Period(TAB_WEEK, it, Dates.weekEnd(it), Dates.formatWeek(it)) }

            TAB_MONTH -> dates
                .map { Dates.monthStart(it) }
                .toSortedSet()
                .map { Period(TAB_MONTH, it, Dates.monthEnd(it), Dates.formatMonth(it)) }

            else -> dates.map { Period(TAB_DAY, it, it, Dates.formatDay(it)) }
        }
    }

    private fun loadPageData(period: Period): PageData {
        val today = Dates.today()
        val now = System.currentTimeMillis()
        return when (period.type) {
            TAB_WEEK -> {
                val totals = db.value.dayTotals(period.start, period.end)
                val bars = FloatArray(7) {
                    totals[Dates.addDays(period.start, it)]?.steps?.toFloat() ?: 0f
                }
                val labels = Array(7) {
                    Dates.formatWeekdayLetter(Dates.addDays(period.start, it))
                }
                var sum = 0L; var cal = 0.0; var dist = 0.0; var time = 0L
                for (t in totals.values) {
                    sum += t.steps; cal += t.cal; dist += t.distanceMeters; time += t.walkTimeMs
                }
                PageData(
                    sum, cal, time, dist, bars, labels, 1,
                    if (today in period.start..period.end) {
                        ((today - period.start) / 86_400_000L).toInt()
                    } else -1,
                    today in period.start..period.end
                )
            }

            TAB_MONTH -> {
                val n = Dates.daysInMonth(period.start)
                val totals = db.value.dayTotals(period.start, period.end)
                val bars = FloatArray(n) {
                    totals[Dates.addDays(period.start, it)]?.steps?.toFloat() ?: 0f
                }
                val labels = Array(n) { (it + 1).toString() }
                var sum = 0L; var cal = 0.0; var dist = 0.0; var time = 0L
                for (t in totals.values) {
                    sum += t.steps; cal += t.cal; dist += t.distanceMeters; time += t.walkTimeMs
                }
                PageData(
                    sum, cal, time, dist, bars, labels, 5,
                    if (today in period.start..period.end) {
                        ((today - period.start) / 86_400_000L).toInt()
                    } else -1,
                    today in period.start..period.end
                )
            }

            else -> {
                val rows = db.value.hourRows(period.start)
                val bars = FloatArray(24) { rows[it]?.steps?.toFloat() ?: 0f }
                val labels = Array(24) { it.toString() }
                val total = db.value.dayTotal(period.start)
                PageData(
                    total.steps, total.cal, total.walkTimeMs, total.distanceMeters,
                    bars, labels, 3,
                    if (period.start == today) Dates.hourOfDay(now) else -1,
                    period.start == today
                )
            }
        }
    }

    private fun refreshCurrentPage() {
        if (periods.isNotEmpty()) {
            pageCache.clear()
            val pos = binding.pager.currentItem
            if (pos in periods.indices) pagesAdapter.notifyItemChanged(pos)
        }
    }

    // ------------------------------------------------------------ permissions

    private fun hasRecognition(): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 29 && !hasRecognition()) {
            wanted.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (wanted.isNotEmpty()) {
            permLauncher.launch(wanted.toTypedArray())
        }
        refreshPermissionBanner()
    }

    private fun refreshPermissionBanner() {
        val granted = hasRecognition()
        binding.permissionBanner.visibility = if (granted) View.GONE else View.VISIBLE
        if (granted && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            recorder.start()
        }
    }

    // --------------------------------------------------------------- adapter

    private inner class PagesAdapter : RecyclerView.Adapter<PageViewHolder>() {
        private var items: List<Period> = emptyList()

        fun submitPeriods(list: List<Period>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            PageViewHolder(
                PageStatsBinding.inflate(layoutInflater, parent, false)
            )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class PageViewHolder(
        private val page: PageStatsBinding
    ) : RecyclerView.ViewHolder(page.root) {

        private var boundPeriod: Period? = null

        fun bind(period: Period) {
            boundPeriod = period
            pageCache[period]?.let { render(period, it) }
            dbExecutor.execute {
                val data = loadPageData(period)
                mainHandler.post {
                    pageCache[period] = data
                    if (boundPeriod == period) render(period, data)
                }
            }
        }

        private fun render(period: Period, data: PageData) {
            page.pageTitle.text = period.title
            page.pageSteps.text = NumFmt.group(data.steps)
            if (data.includesToday && Prefs.goal(this@MainActivity) > 0) {
                page.pageGoal.text = getString(
                    R.string.goal_caption,
                    NumFmt.percent(data.steps, Prefs.goal(this@MainActivity))
                )
                page.progressGoal.progress = ((data.steps.toDouble() / Prefs.goal(this@MainActivity)) * 1000).coerceIn(0.0, 1000.0).toInt()
                page.pageGoal.visibility = View.VISIBLE
                page.progressGoal.visibility = View.VISIBLE
            } else {
                page.pageGoal.visibility = View.GONE
                page.progressGoal.visibility = View.GONE
            }
            page.statDistance.text =
                NumFmt.distance(data.distanceMeters, Prefs.usesKilometers(this@MainActivity))
            page.statCalories.text = NumFmt.calories(data.cal)
            page.statDuration.text = Dates.formatDuration(data.walkTimeMs)
            page.graph.setData(data.bars, data.labels, data.labelStride, data.highlight)
        }
    }
}
