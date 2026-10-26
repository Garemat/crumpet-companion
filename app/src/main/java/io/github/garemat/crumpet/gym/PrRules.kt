package io.github.garemat.crumpet.gym

import java.math.BigDecimal
import java.math.RoundingMode

/** The live mid-session PR check — a line-for-line mirror of the brain's
 *  crumpet/core/progression.py bests/new_prs/absorb, so the badge works offline and agrees
 *  with what the brain re-checks on ingest. Change one, change both.
 *
 *  Rules: warm-ups and drops never count; a first-ever set is not a PR (nothing to beat);
 *  assisted lifts only track reps-at-weight (more assistance = easier, so "heaviest" would
 *  be backwards). Kinds: weight | e1rm | volume | reps. */
object PrRules {
    private val SKIP = setOf("warmup", "drop")

    fun epley(weight: Double, reps: Int): Double =
        if (reps > 1) round1(weight * (1 + reps / 30.0)) else weight

    /** Python's `f"{round(w, 2):g}"` — "60", "62.5", "20.4"; "bw" for bodyweight. */
    fun weightKey(weight: Double?): String {
        if (weight == null || weight == 0.0) return "bw"
        return BigDecimal(weight.toString()).setScale(2, RoundingMode.HALF_EVEN)
            .stripTrailingZeros().toPlainString()
    }

    private fun counts(type: String, reps: Int) = type !in SKIP && reps > 0

    private fun round1(x: Double) = Math.rint(x * 10) / 10

    fun bests(sets: List<LastSet>, assisted: Boolean = false): Bests =
        sets.fold(Bests()) { b, s -> absorb(b, s.weight, s.reps, s.type, assisted) }

    /** Which bests one set beats. Compares on the same 0.1 grid the brain rounds to. */
    fun newPrs(best: Bests, weight: Double?, reps: Int, type: String, assisted: Boolean): List<String> {
        if (!counts(type, reps)) return emptyList()
        val kinds = mutableListOf<String>()
        val prevReps = best.repsAt[weightKey(weight)]
        if (prevReps != null && reps > prevReps) kinds += "reps"
        val w = weight?.takeIf { it > 0 }
        if (w != null && !assisted && best.maxWeight != null) {
            if (w > best.maxWeight) kinds += "weight"
            if (epley(w, reps) > (best.bestE1rm ?: 0.0)) kinds += "e1rm"
            if (round1(w * reps) > (best.bestVolume ?: 0.0)) kinds += "volume"
        }
        return kinds
    }

    /** [best] updated with one more set — so PRs within a session compound. */
    fun absorb(best: Bests, weight: Double?, reps: Int, type: String, assisted: Boolean): Bests {
        if (!counts(type, reps)) return best
        val key = weightKey(weight)
        val repsAt = best.repsAt + (key to maxOf(best.repsAt[key] ?: 0, reps))
        val w = weight?.takeIf { it > 0 }
        if (w == null || assisted) return best.copy(repsAt = repsAt)
        return Bests(
            maxWeight = maxOf(best.maxWeight ?: 0.0, w),
            bestE1rm = maxOf(best.bestE1rm ?: 0.0, epley(w, reps)),
            bestVolume = maxOf(best.bestVolume ?: 0.0, round1(w * reps)),
            repsAt = repsAt,
        )
    }

    /** No history at all → nothing to beat (the brain's "first-ever isn't a PR"). */
    fun isEmpty(best: Bests) = best.maxWeight == null && best.repsAt.isEmpty()
}
