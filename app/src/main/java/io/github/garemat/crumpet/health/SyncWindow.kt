package io.github.garemat.crumpet.health

/** How far back each health sync reads. A fixed 30-day window silently lost everything older
 *  when the brain was down for longer (2026-07-26 → late Oct: two months of sleep/steps/workouts
 *  the brain never saw), so the window grows to cover the time since the last SUCCESSFUL sync.
 *  The brain upserts, so the overlap costs nothing; the cap keeps a stale watermark sane. */
object SyncWindow {
    const val MIN_DAYS = 30
    const val MAX_DAYS = 365
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Days to read: since [lastSuccessMillis] plus a 2-day margin, within [MIN_DAYS, MAX_DAYS].
     *  0 = never synced → the minimum (a first pairing doesn't need a year of history). */
    fun days(lastSuccessMillis: Long, nowMillis: Long = System.currentTimeMillis()): Int {
        if (lastSuccessMillis <= 0L || lastSuccessMillis > nowMillis) return MIN_DAYS
        val gap = ((nowMillis - lastSuccessMillis + DAY_MS - 1) / DAY_MS).toInt() + 2
        return gap.coerceIn(MIN_DAYS, MAX_DAYS)
    }
}
