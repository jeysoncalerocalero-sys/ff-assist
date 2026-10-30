package com.tu.ffassist

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                val i = Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_AIM
                    putExtra("code", res.resultCode)
                    putExtra("data", res.data)
                }
                ContextCompat.startForegroundService(this, i)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 96, 64, 64)
        }

        ll.addView(TextView(this).apply {
            text = "Sigue los pasos en orden."
            textSize = 14f
            setPadding(0, 0, 0, 40)
        })

        fun step(text: String, action: () -> Unit) {
            ll.addView(Button(this).apply {
                this.text = text
                setOnClickListener { action() }
            })
        }

        step("1. Permitir superposición") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        step("2. Activar servicio de accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        step("3. Arrancar HUD") {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        }

        step("4. Dar permiso de captura (AIM)") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }

        step("5. Detener todo") {
            stopService(Intent(this, OverlayService::class.java))
        }

        ll.addView(TextView(this).apply {
            text = "Free Fire: miras 55-65, arrastre 90-100, sin modo inmersivo forzado."
            textSize = 12f
            setPadding(0, 48, 0, 0)
        })

        setContentView(ll)
    }
}
