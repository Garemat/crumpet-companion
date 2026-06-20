package io.github.garemat.crumpet.health

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import io.github.garemat.crumpet.data.AgendaItem

/** Reads the system calendar (which DAVx5 syncs from Radicale) for the agenda strip / "Up next".
 *  Read-only — adds still flow through the brain → Radicale → DAVx5. */
class CalendarRepo(private val context: Context) {

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.READ_CALENDAR,
    ) == PackageManager.PERMISSION_GRANTED

    fun upcoming(days: Int = 7): List<AgendaItem> {
        if (!hasPermission()) return emptyList()
        val begin = System.currentTimeMillis()
        val end = begin + days * 86_400_000L
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, begin)
        ContentUris.appendId(builder, end)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
        )
        val out = mutableListOf<AgendaItem>()
        runCatching {
            context.contentResolver.query(
                builder.build(), projection, null, null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: continue
                    out += AgendaItem(title, c.getLong(1), c.getInt(2) == 1)
                }
            }
        }
        return out
    }
}
