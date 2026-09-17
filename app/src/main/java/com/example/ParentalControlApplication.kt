package com.example

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth

class ParentalControlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Ensure clean state on fresh launch / reset build
        val prefs = getSharedPreferences("parental_control_prefs", MODE_PRIVATE)
        val resetVersion = prefs.getInt("fresh_installation_reset_v3", 0)
        if (resetVersion < 3) {
            prefs.edit().clear().putInt("fresh_installation_reset_v3", 3).commit()
            runCatching { cacheDir.deleteRecursively() }
            runCatching {
                if (FirebaseApp.getApps(this).isNotEmpty()) {
                    FirebaseAuth.getInstance().signOut()
                }
            }
        }
    }
}
