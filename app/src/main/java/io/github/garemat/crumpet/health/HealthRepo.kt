package io.github.garemat.crumpet.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.github.garemat.crumpet.data.HealthRecord
import io.github.garemat.crumpet.data.HealthSnapshot
import io.github.garemat.crumpet.data.WorkoutLine
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.reflect.KClass

/** Reads Health Connect (nutrition/body/activity/sleep) and turns it into ingest records
 *  + a today snapshot. All read-only; permissions are user-granted. */
class HealthRepo(private val context: Context) {

    val available: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient? by lazy {
        if (available) HealthConnectClient.getOrCreate(context) else null
    }

    /** Each syncable type with its read permission — the per-type sync unit. Sync runs with
     *  whatever subset is granted; an ungranted type is skipped AND named in the sync status,
     *  never a silent all-or-nothing stop (that once ate weeks of data without a word). */
    private val typePermissions: Map<String, String> = linkedMapOf(
        "Nutrition" to HealthPermission.getReadPermission(NutritionRecord::class),
        "Weight" to HealthPermission.getReadPermission(WeightRecord::class),
        "Body fat" to HealthPermission.getReadPermission(BodyFatRecord::class),
        "Steps" to HealthPermission.getReadPermission(StepsRecord::class),
        "Workouts" to HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        "Sleep" to HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    val permissions: Set<String> = typePermissions.values.toSet()

    suspend fun granted(): Set<String> =
        client?.permissionController?.getGrantedPermissions() ?: emptySet()

    suspend fun hasAllPermissions(): Boolean = granted().containsAll(permissions)

    /** At least one syncable type is readable — enough for a partial sync to be worth running. */
    suspend fun hasAnyPermission(): Boolean = granted().any { it in permissions }

    /** Labels of the types we can NOT read (for the sync status line). */
    suspend fun missingTypes(): List<String> {
        val g = granted()
        return typePermissions.filterValues { it !in g }.keys.toList()
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun day(i: Instant): String = LocalDate.ofInstant(i, zone).toString()
    private val atFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    private suspend fun <T : Record> read(type: KClass<T>, since: Instant, until: Instant): List<T> {
        val c = client ?: return emptyList()
        return runCatching {
            c.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(since, until))).records
        }.getOrDefault(emptyList())
    }

    private fun sourceApp(pkg: String): String = when {
        pkg.contains("hevy", true) -> "Hevy"
        pkg.contains("fitbit", true) -> "Fitbit"
        pkg.contains("myfitnesspal", true) -> "MyFitnessPal"
        pkg.contains("bend", true) -> "Bend"
        pkg.contains("strava", true) -> "Strava"
        pkg.contains("healthdata") || pkg.contains("healthconnect") -> "Health Connect"
        pkg.isBlank() -> "Health Connect"
        else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    /** Build the ingest batch over a rolling [days] window. The brain dedupes/upserts, so re-sending
     *  overlapping data is safe — and it avoids missing anything logged before a stale watermark.
     *  Reads only the types whose permission is granted; the rest are skipped (per-type sync). */
    suspend fun recentRecords(days: Int = 30): List<HealthRecord> {
        if (client == null) return emptyList()
        val since = Instant.now().minus(Duration.ofDays(days.toLong()))
        val until = Instant.now()
        val out = mutableListOf<HealthRecord>()
        val g = granted()
        fun canRead(type: KClass<out Record>) = HealthPermission.getReadPermission(type) in g

        // nutrition — sum per day
        val nut = HashMap<String, DoubleArray>() // [cal, protein, carbs, fat]
        if (canRead(NutritionRecord::class)) for (r in read(NutritionRecord::class, since, until)) {
            val d = nut.getOrPut(day(r.startTime)) { DoubleArray(4) }
            d[0] += r.energy?.inKilocalories ?: 0.0
            d[1] += r.protein?.inGrams ?: 0.0
            d[2] += r.totalCarbohydrate?.inGrams ?: 0.0
            d[3] += r.totalFat?.inGrams ?: 0.0
        }
        nut.forEach { (day, v) ->
            out += HealthRecord("nutrition", day = day, calories = v[0], protein = v[1], carbs = v[2], fat = v[3])
        }

        // weight / bodyfat — latest per day is fine (brain upserts on kind+day)
        if (canRead(WeightRecord::class)) for (r in read(WeightRecord::class, since, until))
            out += HealthRecord("weight", day = day(r.time), value = r.weight.inKilograms, unit = "kg")
        if (canRead(BodyFatRecord::class)) for (r in read(BodyFatRecord::class, since, until))
            out += HealthRecord("bodyfat", day = day(r.time), value = r.percentage.value)

        // steps — sum per day
        val steps = HashMap<String, Long>()
        if (canRead(StepsRecord::class)) for (r in read(StepsRecord::class, since, until))
            steps[day(r.startTime)] = (steps[day(r.startTime)] ?: 0L) + r.count
        steps.forEach { (day, n) -> out += HealthRecord("steps", day = day, value = n.toDouble()) }

        // sleep — minutes per day
        val sleep = HashMap<String, Long>()
        if (canRead(SleepSessionRecord::class)) for (r in read(SleepSessionRecord::class, since, until)) {
            val mins = Duration.between(r.startTime, r.endTime).toMinutes()
            sleep[day(r.endTime)] = (sleep[day(r.endTime)] ?: 0L) + mins
        }
        sleep.forEach { (day, m) -> out += HealthRecord("sleep", day = day, minutes = m.toDouble()) }

        // workouts — one per session, deduped on Health Connect record id
        if (canRead(ExerciseSessionRecord::class)) for (r in read(ExerciseSessionRecord::class, since, until)) {
            val mins = Duration.between(r.startTime, r.endTime).toMinutes()
            out += HealthRecord(
                "workout",
                uid = r.metadata.id,
                title = r.title ?: "Workout",
                detail = "$mins min · ${sourceApp(r.metadata.dataOrigin.packageName)}",
                at = LocalDateTime.ofInstant(r.startTime, zone).format(atFmt),
            )
        }
        return out
    }

    /** Today's glance for the Home/Health screens. */
    suspend fun todaySnapshot(): HealthSnapshot {
        if (client == null) return HealthSnapshot()
        val startOfDay = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val now = Instant.now()

        var cal = 0.0; var pro = 0.0; var carb = 0.0; var fat = 0.0
        for (r in read(NutritionRecord::class, startOfDay, now)) {
            cal += r.energy?.inKilocalories ?: 0.0
            pro += r.protein?.inGrams ?: 0.0
            carb += r.totalCarbohydrate?.inGrams ?: 0.0
            fat += r.totalFat?.inGrams ?: 0.0
        }
        val weight = read(WeightRecord::class, startOfDay.minus(Duration.ofDays(14)), now)
            .maxByOrNull { it.time }?.weight?.inKilograms
        var steps = 0L
        for (r in read(StepsRecord::class, startOfDay, now)) steps += r.count
        val sleepMin = read(SleepSessionRecord::class, startOfDay.minus(Duration.ofDays(1)), now)
            .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
        val workouts = read(ExerciseSessionRecord::class, startOfDay.minus(Duration.ofDays(3)), now)
            .sortedByDescending { it.startTime }
            .take(4)
            .map {
                val m = Duration.between(it.startTime, it.endTime).toMinutes()
                WorkoutLine(it.title ?: "Workout", "$m min", sourceApp(it.metadata.dataOrigin.packageName))
            }

        return HealthSnapshot(
            calories = if (cal > 0) cal.roundToInt() else null,
            protein = if (pro > 0) pro.roundToInt() else null,
            carbs = if (carb > 0) carb.roundToInt() else null,
            fat = if (fat > 0) fat.roundToInt() else null,
            weightKg = weight,
            steps = if (steps > 0) steps.toInt() else null,
            sleepMinutes = if (sleepMin > 0) sleepMin.toInt() else null,
            recentWorkouts = workouts,
        )
    }
}
