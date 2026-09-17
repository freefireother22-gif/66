package com.example.monitoring

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.example.model.SmsItem

class SmsCollector(private val context: Context) {
    fun collectRecent(limit: Int = 100): List<SmsItem> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val output = mutableListOf<SmsItem>()
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
            Telephony.Sms.DATE, Telephony.Sms.TYPE
        )
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, projection, null, null,
                "${Telephony.Sms.DATE} DESC LIMIT $limit"
            )?.use { c ->
                val idIx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                while (c.moveToNext()) {
                    output += SmsItem(
                        id = c.getString(idIx),
                        address = c.getString(addressIx) ?: "Unknown",
                        body = c.getString(bodyIx) ?: "",
                        timestamp = c.getLong(dateIx),
                        direction = if (c.getInt(typeIx) == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else "received"
                    )
                }
            }
        }
        return output
    }
}
