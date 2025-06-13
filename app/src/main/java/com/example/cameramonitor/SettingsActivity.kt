package com.example.cameramonitor

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val etLogSize = findViewById<EditText>(R.id.etLogSize)
        val rgTheme = findViewById<RadioGroup>(R.id.rgTheme)
        val rbLight = findViewById<RadioButton>(R.id.rbLight)
        val rbDark = findViewById<RadioButton>(R.id.rbDark)
        val rbSystem = findViewById<RadioButton>(R.id.rbSystem)
        val btnSave = findViewById<Button>(R.id.btnSaveSettings)
        val btnClearLog = findViewById<Button>(R.id.btnClearLog)

        val prefs = getSharedPreferences("camera_monitor_prefs", Context.MODE_PRIVATE)
        etLogSize.setText(prefs.getInt("log_size", 1000).toString())
        when (prefs.getString("theme_mode", "system")) {
            "light" -> rbLight.isChecked = true
            "dark" -> rbDark.isChecked = true
            else -> rbSystem.isChecked = true
        }

        btnSave.setOnClickListener {
            val logSize = etLogSize.text.toString().toIntOrNull() ?: 1000
            prefs.edit().putInt("log_size", logSize).apply()
            when (rgTheme.checkedRadioButtonId) {
                R.id.rbLight -> {
                    prefs.edit().putString("theme_mode", "light").apply()
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                R.id.rbDark -> {
                    prefs.edit().putString("theme_mode", "dark").apply()
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
                else -> {
                    prefs.edit().putString("theme_mode", "system").apply()
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                }
            }
            Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show()
            finish()
        }

        btnClearLog.setOnClickListener {
            prefs.edit().remove("log_events").apply()
            Toast.makeText(this, getString(R.string.log_cleared), Toast.LENGTH_SHORT).show()
        }
    }
}
