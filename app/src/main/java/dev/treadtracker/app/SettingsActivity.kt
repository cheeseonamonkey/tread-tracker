package dev.treadtracker.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.treadtracker.app.databinding.ActivitySettingsBinding
import dev.treadtracker.app.service.StepService
import dev.treadtracker.app.util.Dates
import dev.treadtracker.app.util.Prefs
import java.util.Locale

/**
 * Settings. Plain views instead of the original androidx.preference screen:
 * fewer dependencies, and the preference keys stay the same.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

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
            if (checked) {
                StepService.start(this)
            } else {
                StepService.stop(this)
            }
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

        // ---- battery
        binding.btnBattery.setOnClickListener {
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            } catch (e: Exception) {
                Toast.makeText(this, R.string.battery_unavailable, Toast.LENGTH_SHORT).show()
            }
        }

        // ---- about
        binding.aboutBody.text = getString(
            R.string.about_body,
            BuildConfig_VERSION_NAME
        )
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryStatus()
    }

    override fun onPause() {
        super.onPause()
        persist()
    }

    override fun onDestroy() {
        persist()
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
    }

    private fun refreshBatteryStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        binding.batteryStatus.setText(
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                R.string.battery_ok
            } else {
                R.string.battery_not_ok
            }
        )
    }

    /** Convert an EditText number between metric and imperial on unit toggle. */
    private fun convertField(field: android.widget.EditText, toMetric: Boolean, ratio: Double) {
        val value = field.text.toString().toDoubleOrNull() ?: return
        val converted = if (toMetric) value * ratio else value / ratio
        field.setText(String.format(Locale.getDefault(), "%.0f", converted))
    }

    companion object {
        private const val BuildConfig_VERSION_NAME = "1.0.0"
    }
}
