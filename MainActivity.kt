package com.elixir.tracker

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        l.addView(TextView(this).apply {
            textSize = 16f
            text = "Elixir Tracker + IA de cartas\n\n" +
                "1) Dá el permiso de superposición.\n" +
                "2) Tocá Iniciar y aceptá la captura de pantalla.\n" +
                "3) Abrí Clash Royale.\n\n" +
                "La app resta elixir solo cuando detecta una carta del rival.\n" +
                "Los botones 1-7 siguen disponibles para corregir a mano.\n\n" +
                "Si no arranca solo, tocá el texto de la ventanita."
        })
        fun btn(t: String, f: () -> Unit) {
            l.addView(Button(this).apply { text = t; setOnClickListener { f() } })
        }
        btn("1. Permiso de superposición") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("2. Iniciar") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Primero dá el permiso de superposición", Toast.LENGTH_LONG).show()
            } else {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                val m = getSystemService(MediaProjectionManager::class.java)
                startActivityForResult(m.createScreenCaptureIntent(), 42)
            }
        }
        btn("Detener") { stopService(Intent(this, CaptureService::class.java)) }
        setContentView(l)
    }

    @Deprecated("usa API clásica a propósito")
    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) {
        super.onActivityResult(rq, rc, d)
        if (rq == 42 && rc == RESULT_OK && d != null) {
            val i = Intent(this, CaptureService::class.java).putExtra("code", rc).putExtra("data", d)
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
