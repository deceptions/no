package com.lagradost.shiro.utils

import java.util.Random

/**
 * Multi-step puzzles that gate sensitive settings (screen-capture protection, app name and icon). Each gate has its own puzzle.
 * Three or four rounds: arithmetic, a number sequence, a letter cipher and (identity only) a digit puzzle. Answers are
 * generated on the device, so there is nothing to look up.
 */
object Puzzle {
    enum class Kind { CAPTURE, IDENTITY }

    data class Round(val prompt: String, val answer: String)

    private val words = listOf("purple", "nekomo", "anime", "castle", "dragon", "shadow", "planet", "silver", "forest", "thunder", "lantern", "harvest")

    fun generate(kind: Kind, rnd: Random = Random()): List<Round> {
        fun n(lo: Int, hi: Int) = lo + rnd.nextInt(hi - lo + 1)
        val rounds = ArrayList<Round>()
        val a = n(12, 29); val b = n(3, 9); val c = n(11, 25); val d = n(3, 9); val e = n(10, 99)
        rounds += Round("($a \u00d7 $b) + ($c \u00d7 $d) \u2212 $e = ?", ((a * b) + (c * d) - e).toString())
        val k = n(1, 4)
        var x = n(2, 9)
        val seq = ArrayList<Int>()
        repeat(4) { seq += x; x = x * 2 + k }
        rounds += Round("${seq.joinToString(", ")}, ?   (one rule builds every term from the one before)", x.toString())
        val word = words[rnd.nextInt(words.size)]
        val shift = n(3, 9)
        val enc = word.map { ((it - 'a' + shift) % 26 + 'a'.code).toChar() }.joinToString("")
        rounds += Round("Every letter was moved $shift places forward in the alphabet. Decode: ${enc.uppercase()}", word)
        if (kind == Kind.IDENTITY) {
            val big = n(1000, 9999) * n(3, 9)
            rounds += Round("Multiply the digits of $big together. What is the result?", big.toString().map { it - '0' }.fold(1) { acc, v -> acc * v }.toString())
        }
        return rounds
    }

    fun isCorrect(round: Round, input: String) = input.trim().lowercase() == round.answer

    /** Taps on the puzzle title needed for the hidden bypass, and the window they must happen in. */
    const val BYPASS_TAPS = 7
    const val BYPASS_WINDOW_MS = 6000L

    /** Solved gates stay open for a few minutes so the same screen does not ask twice in a row. */
    private val unlockedUntil = HashMap<Kind, Long>()
    fun unlocked(kind: Kind) = (unlockedUntil[kind] ?: 0L) > System.currentTimeMillis()
    fun markUnlocked(kind: Kind) { unlockedUntil[kind] = System.currentTimeMillis() + 5 * 60_000L }
}
