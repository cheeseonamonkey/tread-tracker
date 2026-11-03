package dev.treadtracker.app

import android.app.DatePickerDialog
import android.widget.Toast
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import dev.treadtracker.app.databinding.ActivitySettingsBinding
import dev.treadtracker.app.util.Dates
import dev.treadtracker.app.util.Prefs
import dev.treadtracker.app.widget.StepsWidget
import dev.treadtracker.app.ui.HistoryEditor
import dev.treadtracker.app.data.HistoryCsv
import dev.treadtracker.app.data.StepDb
import dev.treadtracker.app.service.StepService
import java.util.Locale
import java.util.Calendar
import java.util.concurrent.Executors
import java.nio.charset.StandardCharsets

/**
 * Settings. Plain views instead of the original androidx.preference screen:
 * fewer dependencies, and the preference keys stay the same.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val historyExecutor = Executors.newSingleThreadExecutor()

    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@registerForActivityResult
        historyExecutor.execute {
            try {
                val totals = StepDb(this).use { db ->
                    val dates = db.distinctDates()
                    if (dates.isEmpty()) emptyMap() else
                        db.dayTotals(dates.first(), dates.last())
                            .mapValues { it.value.steps }
                }
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.writer(StandardCharsets.UTF_8).use { HistoryCsv.write(it, totals) }
                } ?: error("Could not open selected destination")
                runOnUiThread { notifyHistory(getString(R.string.export_complete, totals.size)) }
            } catch (e: Exception) { historyFailure(e) }
        }
    }

    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        historyExecutor.execute {
            try {
                val rows = contentResolver.openInputStream(uri)?.use { input ->
                    HistoryCsv.parse(input.reader(StandardCharsets.UTF_8))
                } ?: error("Could not read selected file")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    AlertDialog.Builder(this)
                        .setTitle(R.string.import_csv)
                        .setMessage(getString(R.string.import_confirm, rows.size))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.save) { _, _ ->
                            historyExecutor.execute {
                                try {
                                    StepDb(this).use { it.setDailyTotals(rows) }
                                    StepsWidget.refresh(this)
                                    runOnUiThread { notifyHistory(getString(R.string.import_complete, rows.size)) }
                                } catch (e: Exception) { historyFailure(e) }
                            }
                        }
                        .show()
                }
            } catch (e: Exception) { historyFailure(e) }
        }
    }

    private fun notifyHistory(message: String) {
        if (!isFinishing && !isDestroyed)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun historyFailure(e: Exception) {
        runOnUiThread { notifyHistory(e.message ?: getString(R.string.history_error)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        // ---- goal
        binding.goal.setText(Prefs.goal(this).toString())

        // ---- counting
        binding.switchCounting.isChecked = Prefs.isCountingEnabled(this)
        binding.switchCounting.setOnCheckedChangeListener { _, checked ->
            Prefs.setCountingEnabled(this, checked)
            if (checked) StepService.start(this) else StepService.stop(this)
        }

        // ---- weight
        binding.weight.setText(
            String.format(Locale.getDefault(), "%.0f", Prefs.weightDisplay(this))
        )
        binding.weightUnit.isChecked = Prefs.usesKg(this)
        binding.weightUnit.setOnCheckedChangeListener { _, checked ->
            convertField(binding.weight, checked, 0.45359237)
        }

        // ---- step length
        binding.stepLength.setText(
            String.format(Locale.getDefault(), "%.0f", Prefs.stepLengthDisplay(this))
        )
        binding.stepUnit.isChecked = Prefs.usesCm(this)
        binding.stepUnit.setOnCheckedChangeListener { _, checked ->
            convertField(binding.stepLength, checked, 2.54)
        }

        // ---- distance display unit
        binding.switchMiles.isChecked = !Prefs.usesKilometers(this)
        binding.switchMiles.setOnCheckedChangeListener { _, checked ->
            Prefs.setUsesKilometers(this, !checked)
        }

        // ---- first day of week
        val firstDay = Prefs.firstDayOfWeek(this)
        binding.radioFirstDay.check(
            when (firstDay) {
                Dates.FIRST_DAY_MONDAY -> binding.radioMonday.id
                Dates.FIRST_DAY_SATURDAY -> binding.radioSaturday.id
                else -> binding.radioSunday.id
            }
        )
        binding.radioFirstDay.setOnCheckedChangeListener { _, _ ->
            Prefs.setFirstDayOfWeek(
                this,
                when (binding.radioFirstDay.checkedRadioButtonId) {
                    binding.radioMonday.id -> Dates.FIRST_DAY_MONDAY
                    binding.radioSaturday.id -> Dates.FIRST_DAY_SATURDAY
                    else -> Dates.FIRST_DAY_SUNDAY
                }
            )
        }

        // ---- sensitivity (accelerometer fallback only)
        binding.sensitivity.progress = Prefs.sensitivity(this) - 10
        binding.sensitivityValue.text = Prefs.sensitivity(this).toString()
        binding.sensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                binding.sensitivityValue.text = (value + 10).toString()
            }

            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })

        // ---- local history controls and storage-access-framework pickers
        binding.btnEditHistory.setOnClickListener {
            val today = Calendar.getInstance()
            DatePickerDialog(this, { _, year, month, day ->
                val chosen = Calendar.getInstance().apply {
                    set(year, month, day, 12, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                HistoryEditor.show(this, Dates.dayStart(chosen.timeInMillis), historyExecutor) {
                    StepsWidget.refresh(this)
                }
            }, today.get(Calendar.YEAR), today.get(Calendar.MONTH),
                today.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        binding.btnExportCsv.setOnClickListener { exportPicker.launch("TreadTracker-steps.csv") }
        binding.btnImportCsv.setOnClickListener { importPicker.launch(arrayOf("*/*")) }

        // ---- reliability: official OS settings; no notification bypasses.
        val sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        binding.sensorMode.setText(when {
            sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null -> R.string.sensor_hardware_counter
            sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null -> R.string.sensor_hardware_detector
            sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null -> R.string.sensor_accelerometer
            else -> R.string.sensor_unavailable
        })
        binding.btnBatterySettings.setOnClickListener {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        }
        binding.btnNotificationSettings.setOnClickListener {
            val intent = if (Build.VERSION.SDK_INT >= 26) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, StepService.CHANNEL)
                }
            } else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null))
            openSettings(intent)
        }

        // ---- about: visible in the installed app, not just the repository.
        val credit = SpannableString(getString(R.string.credit_development))
        val handle = "@cheeseonamonkey"
        credit.setSpan(URLSpan("https://github.com/cheeseonamonkey"),
            credit.indexOf(handle), credit.indexOf(handle) + handle.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.creditsDeveloper.text = credit
        binding.creditsDeveloper.movementMethod = LinkMovementMethod.getInstance()
        binding.aboutBody.text = getString(
            R.string.about_body,
            BuildConfig_VERSION_NAME
        )
    }

    override fun onResume() {
        super.onResume()
        val power = getSystemService(POWER_SERVICE) as PowerManager
        binding.batteryStatus.setText(if (power.isIgnoringBatteryOptimizations(packageName))
            R.string.battery_unrestricted else R.string.battery_optimized)
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)))
        }
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    override fun onDestroy() {
        persist()
        historyExecutor.shutdown()
        super.onDestroy()
    }

    private fun persist() {
        binding.goal.text.toString().toIntOrNull()?.let { Prefs.setGoal(this, it) }

        binding.weight.text.toString().toDoubleOrNull()?.let {
            Prefs.setWeight(this, it, binding.weightUnit.isChecked)
        }
        binding.stepLength.text.toString().toDoubleOrNull()?.let {
            Prefs.setStepLength(this, it, binding.stepUnit.isChecked)
        }

        Prefs.setSensitivity(this, binding.sensitivity.progress + 10)
        StepsWidget.refresh(this)
    }

    /** Convert an EditText number between metric and imperial on unit toggle. */
    private fun convertField(field: android.widget.EditText, toMetric: Boolean, ratio: Double) {
        val value = field.text.toString().toDoubleOrNull() ?: return
        val converted = if (toMetric) value * ratio else value / ratio
        field.setText(String.format(Locale.getDefault(), "%.0f", converted))
    }

    companion object {
        private const val BuildConfig_VERSION_NAME = "1.0.5"
    }
}
