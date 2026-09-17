package com.example.monitoring

import android.app.usage.UsageStatsManager
import android.content.Context
import com.example.model.AppUsageItem
import java.util.Calendar

class UsageStatsCollector(private val context: Context) {
    fun collectToday(limit: Int = 30): List<AppUsageItem> {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyList()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = System.currentTimeMillis()
        return manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            .orEmpty()
            .filter { it.totalTimeInForeground > 0 && it.packageName != context.packageName }
            .map { stat ->
                val label = runCatching {
                    val info = context.packageManager.getApplicationInfo(stat.packageName, 0)
                    context.packageManager.getApplicationLabel(info).toString()
                }.getOrDefault(stat.packageName.substringAfterLast('.'))
                AppUsageItem(stat.packageName, label, stat.totalTimeInForeground, stat.lastTimeUsed)
            }
            .sortedByDescending { it.foregroundMillis }
            .take(limit)
    }
}
