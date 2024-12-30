package com.example.cameratesting

import android.os.Bundle
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val sharedPreferences = getSharedPreferences("app_settings", MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        val serverIPAddressTextBox = findViewById<EditText>(R.id.serverIPAddress)
        val serverPortNumberTextBox = findViewById<EditText>(R.id.serverPortNumber)
        val photosPerCaptureTextBox = findViewById<EditText>(R.id.photosPerCapture)

        serverIPAddressTextBox.setText(sharedPreferences.getString("server_ip_address", getString(R.string.server_default_ip_address)) ?: getString(R.string.server_default_ip_address))
        serverPortNumberTextBox.setText(sharedPreferences.getString("server_port_number", getString(R.string.server_default_port_number)) ?: getString(R.string.server_default_port_number))
        photosPerCaptureTextBox.setText(sharedPreferences.getString("photos_per_capture", getString(R.string.default_photos_per_capture)) ?: getString(R.string.default_photos_per_capture))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                editor.putString("server_ip_address", serverIPAddressTextBox.text.toString())
                editor.putString("server_port_number", serverPortNumberTextBox.text.toString())
                editor.putString("photos_per_capture", photosPerCaptureTextBox.text.toString())
                editor.apply()
                finish()
            }
        })
    }
}
