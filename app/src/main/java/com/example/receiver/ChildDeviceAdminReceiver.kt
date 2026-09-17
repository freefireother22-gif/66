package com.example.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Legitimate Parental-Control Device Admin Receiver.
 * Only used for authorized parental control device administration capabilities
 * such as preventing unauthorized tamper/removal during active parental supervision.
 */
class ChildDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "Parental Protection Device Admin Enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "Parental Protection Device Admin Disabled", Toast.LENGTH_SHORT).show()
    }
}
