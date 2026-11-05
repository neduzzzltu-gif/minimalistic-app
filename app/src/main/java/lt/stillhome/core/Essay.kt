package lt.stillhome.core

import lt.stillhome.App
import lt.stillhome.data.Store
import java.time.LocalDate

/** The daily essay: once per "essay day" (resets 04:00), at least [MIN_WORDS] real words before Home. */
object Essay {
    const val MIN_WORDS = 200

    /** Essay day = date of (now - 4 h), so writing at 01:00 still counts for the day before. */
    fun day(now: Long = Clock.now()): String = Clock.dateTime(now).minusHours(4).toLocalDate().toString()   // local time: DST-safe
    fun on() = Store.str("essay", "1") != "0"
    fun due(): Boolean = on() && Store.bool("setupDone", false) && !Guard.paused() && App.db.dao().essayFor(day()) == null

    private val prompts = listOf(
        "What will you do today and why?",
        "What is the one thing that would make today a good day?",
        "What did you avoid yesterday, and what will you do about it today?",
        "Who do you want to be in a year, and what does today have to do with it?",
        "What are you grateful for right now, and why?",
        "What is worrying you, and what can you actually do about it?",
        "What did you learn this week?",
        "Describe your perfect evening today. What has to happen before it?",
        "What would you do today if your phone didn't exist?",
        "What habit do you want to build, and what is the first small step today?",
        "What went well yesterday and what didn't?",
        "Which person do you want to spend time with this week, and why?",
        "What is one goal for your business or school this month, and the next step?",
        "Write about something you are proud of.",
    )
    fun prompt(day: String = day()): String = prompts[Math.floorMod(LocalDate.parse(day).toEpochDay(), prompts.size.toLong()).toInt()]

    private val wordRx = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*")
    fun words(text: String): List<String> = wordRx.findAll(text).map { it.value }.toList()

    // Lithuanian + English (+ common accented) vowels; y counts (LT "y", EN "rhythm").
    private const val VOWELS = "aeiouyąęėįųūåäöüéèêáàâíìîóòôúùûæœ"
    private fun isVowel(c: Char) = c in VOWELS

    /** 4-letter runs along a keyboard row (either way). The top row needs 5: "erty" is in "property", "trew" in "strewn". */
    private val rowRuns: Set<String> = buildSet {
        listOf("qwertyuiop" to 5, "asdfghjkl" to 4, "zxcvbnm" to 4).forEach { (row, n) ->
            listOf(row, row.reversed()).forEach { r -> r.windowed(n).forEach { add(it) } }
        }
    }
    private fun rowRun(w: String) = rowRuns.any { it in w }
    private fun sameRun(w: String, n: Int) = (0..w.length - n).any { i -> w.substring(i, i + n).all { it == w[i] } }
    private fun maxConsonants(w: String): Int { var m = 0; var c = 0; for (ch in w) { c = if (isVowel(ch)) 0 else c + 1; m = maxOf(m, c) }; return m }
    /** "asdasd", "hahaha": one chunk repeated. */
    private fun repeated(w: String) = w.length >= 6 && (1..w.length / 2).any { k -> w.length % k == 0 && w.take(k).repeat(w.length / k) == w }

    /** Plausible word in LT or EN. Short words (ir, o, į, nr, pvz...) always pass. */
    private fun looksReal(w: String): Boolean {
        if (w.length <= 3) return true
        val v = w.count(::isVowel).toDouble() / w.length
        return v in 0.15..0.85 && !sameRun(w, 3) && !rowRun(w) && w.length <= 22 && maxConsonants(w) <= 5 && !repeated(w)
    }

    /** Null = accepted, else the reason it was rejected (shown to the user). */
    fun check(text: String): String? {
        val all = words(text)
        if (all.size < MIN_WORDS) return "${MIN_WORDS - all.size} more words"
        val lower = all.map { it.lowercase() }
        // Worst 200-word stretch, so 200 good words can't carry 300 copied ones.
        val unique = (if (lower.size <= MIN_WORDS) listOf(lower) else lower.windowed(MIN_WORDS, 100) + listOf(lower.takeLast(MIN_WORDS)))
            .minOf { w -> w.toSet().size.toDouble() / w.size }
        if (unique < 0.5) return "Too many repeated words: ${(unique * 100).toInt()}% are different, needs 50%."
        if (Regex("(\\p{L})\\1{5,}").containsMatchIn(text)) return "Long runs of the same character. Write real sentences."
        val ws = lower.map { w -> w.filter { it.isLetter() } }.filter { it.isNotEmpty() }
        if (ws.isEmpty()) return "Write words, not numbers."
        val n = ws.size.toDouble()
        if (ws.count { it.length >= 4 && it.none(::isVowel) } / n > 0.08) return "Too many words without vowels. Looks like keyboard mashing."
        if (ws.count(::rowRun).let { it >= 3 || it / n > 0.03 }) return "Looks like keyboard mashing (asdf, qwert…)."
        if (ws.any { it.length > 35 } || ws.count { it.length > 22 } / n > 0.03) return "Some words are far too long."
        val real = ws.count(::looksReal) / n
        if (real < 0.85) return "Too many words don't look real (${((1 - real) * 100).toInt()}%). Write in normal Lithuanian or English."
        return null
    }
}
