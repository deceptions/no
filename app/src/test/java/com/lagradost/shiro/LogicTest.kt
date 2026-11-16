package com.lagradost.shiro

import com.google.common.truth.Truth.assertThat
import com.lagradost.shiro.utils.InAppUpdater
import com.lagradost.shiro.utils.SourceHealth
import com.lagradost.shiro.utils.home.HomeFilters
import org.junit.Test

class LogicTest {
    @Test
    fun versionsCompareNumerically() {
        val v = { s: String -> InAppUpdater.parseVersion(s)!! }
        assertThat(InAppUpdater.compareVersions(v("2.10.0"), v("2.9.9"))).isGreaterThan(0)
        assertThat(InAppUpdater.compareVersions(v("2.8.1"), v("2.8.1"))).isEqualTo(0)
        assertThat(InAppUpdater.compareVersions(v("2.8"), v("2.8.1"))).isLessThan(0)
        assertThat(InAppUpdater.parseVersion("v2.8.1-beta")).containsExactly(2, 8, 1).inOrder()
        assertThat(InAppUpdater.parseVersion("no version here")).isNull()
    }

    @Test
    fun homeBlocklistHidesSazaeSan() {
        assertThat(HomeFilters.isBlocked("Sazae-san")).isTrue()
        assertThat(HomeFilters.isBlocked(null, "SAZAE SAN")).isTrue()
        assertThat(HomeFilters.isBlocked("サザエさん")).isTrue()
        assertThat(HomeFilters.isBlocked("Sousou no Frieren", null)).isFalse()
    }

    @Test
    fun sourceHealthNeedsTwoFailuresInARow() {
        val key = "cs:Test"
        SourceHealth.ok(key)
        SourceHealth.fail(key)
        assertThat(SourceHealth.isFailing(key)).isFalse()
        SourceHealth.fail(key)
        assertThat(SourceHealth.isFailing(key)).isTrue()
        assertThat(SourceHealth.label("Test", key)).contains("not responding")
        SourceHealth.ok(key)
        assertThat(SourceHealth.isFailing(key)).isFalse()
    }
}

class ApiHealthTest {
    @org.junit.Test
    fun rateLimitPausesAndResets() {
        val id = "test-api"
        com.lagradost.shiro.utils.meta.ApiHealth.reset()
        com.google.common.truth.Truth.assertThat(com.lagradost.shiro.utils.meta.ApiHealth.blocked(id)).isFalse()
        com.lagradost.shiro.utils.meta.ApiHealth.rateLimited(id, 60)
        com.google.common.truth.Truth.assertThat(com.lagradost.shiro.utils.meta.ApiHealth.blocked(id)).isTrue()
        com.google.common.truth.Truth.assertThat(com.lagradost.shiro.utils.meta.ApiHealth.status(id)).contains("Paused")
        com.lagradost.shiro.utils.meta.ApiHealth.reset()
        com.google.common.truth.Truth.assertThat(com.lagradost.shiro.utils.meta.ApiHealth.status(id)).isEqualTo("Available")
    }

    @org.junit.Test
    fun threeFailuresInARowPause() {
        val id = "test-api-2"
        com.lagradost.shiro.utils.meta.ApiHealth.reset()
        repeat(2) { com.lagradost.shiro.utils.meta.ApiHealth.failure(id) }
        com.google.common.truth.Truth.assertThat(com.lagradost.shiro.utils.meta.ApiHealth.blocked(id)).isFalse()
        com.lagradost.shiro.utils.meta.ApiHealth.failure(id)
        com.google.common.truth.Truth.assertThat(com.lagradost.shiro.utils.meta.ApiHealth.blocked(id)).isTrue()
        com.lagradost.shiro.utils.meta.ApiHealth.success(id)
    }
}

class NekomoRepoTest {
    @org.junit.Test
    fun urlsResolveAndUnknownCodesDoNot() {
        val r = com.lagradost.shiro.utils.repo.NekomoRepo
        com.google.common.truth.Truth.assertThat(r.resolve("https://example.com/index.json")).isEqualTo("https://example.com/index.json")
        com.google.common.truth.Truth.assertThat(r.resolve("some-other-name")).isNull()
        com.google.common.truth.Truth.assertThat(r.resolve("nekomo")).isNull()
        com.google.common.truth.Truth.assertThat(r.resolve("ftp://x")).isNull()
        com.google.common.truth.Truth.assertThat(r.isNickname("")).isFalse()
    }
}

class FastestFirstTest {
    @org.junit.Test
    fun fastestApiComesFirstAndPausedLast() {
        val h = com.lagradost.shiro.utils.meta.ApiHealth
        h.reset()
        h.record("t_a", 900); h.record("t_b", 120); h.record("t_c", 400)
        com.google.common.truth.Truth.assertThat(h.fastest(listOf("t_a", "t_b", "t_c"))).containsExactly("t_b", "t_c", "t_a").inOrder()
        h.simulateOutage("t_b", 5)
        com.google.common.truth.Truth.assertThat(h.fastest(listOf("t_a", "t_b", "t_c")).last()).isEqualTo("t_b")
        h.reset()
    }

    @org.junit.Test
    fun raceStartsBackupWhenPrimaryIsSlow() {
        val start = System.currentTimeMillis()
        val r = com.lagradost.shiro.utils.meta.Race.first<String>(100, listOf({ Thread.sleep(3000); "slow" }, { "fast" }))
        com.google.common.truth.Truth.assertThat(r).isEqualTo("fast")
        com.google.common.truth.Truth.assertThat(System.currentTimeMillis() - start).isLessThan(1500L)
    }

    @org.junit.Test
    fun raceFallsThroughFailuresAndEmptyResults() {
        val r = com.lagradost.shiro.utils.meta.Race.first<List<Int>>(1000, listOf({ throw RuntimeException("down") }, { emptyList() }, { listOf(1) }), ok = { it.isNotEmpty() })
        com.google.common.truth.Truth.assertThat(r).containsExactly(1)
        com.google.common.truth.Truth.assertThat(com.lagradost.shiro.utils.meta.Race.first<String>(50, listOf({ null }, { null }))).isNull()
    }
}

class ReleaseNotesTest {
    @org.junit.Test
    fun markdownBecomesPlainText() {
        val md = "### Downloads\n| a | b |\n|---|---|\n**Beta**: `Nekomo-phone-beta.apk` and [NEX](https://github.com/Nekomo-App/NEX)\n\n\n\nDone"
        val out = com.lagradost.shiro.utils.InAppUpdater.plainNotes(md)
        com.google.common.truth.Truth.assertThat(out).isEqualTo("Downloads\nBeta: Nekomo-phone-beta.apk and NEX\n\nDone")
        com.google.common.truth.Truth.assertThat(com.lagradost.shiro.utils.InAppUpdater.plainNotes(null)).isEmpty()
    }
}

class ShortcodeRegistryTest {
    @org.junit.Test
    fun registryMatchesNexAndRejectsUnknownCodes() {
        val sc = com.lagradost.shiro.utils.repo.Shortcodes
        com.google.common.truth.Truth.assertThat(sc.size).isEqualTo(10)
        com.google.common.truth.Truth.assertThat(sc.digest).isEqualTo("c7d89d5973291b4a5f65ccb23e9083ded998bcf09307de3f574d250437661a19")
        com.google.common.truth.Truth.assertThat(sc.lookup("")).isNull()
        com.google.common.truth.Truth.assertThat(sc.lookup("not-a-code")).isNull()
        com.google.common.truth.Truth.assertThat(sc.lookup("nekomo")).isNull()
    }
}

class PuzzleTest {
    @org.junit.Test
    fun everyRoundHasACorrectAnswerAndRejectsWrongOnes() {
        val p = com.lagradost.shiro.utils.Puzzle
        for (kind in com.lagradost.shiro.utils.Puzzle.Kind.values()) repeat(60) { seed ->
            val rounds = p.generate(kind, java.util.Random(seed.toLong()))
            com.google.common.truth.Truth.assertThat(rounds.size).isEqualTo(if (kind == com.lagradost.shiro.utils.Puzzle.Kind.IDENTITY) 4 else 3)
            rounds.forEach { r ->
                com.google.common.truth.Truth.assertThat(p.isCorrect(r, "  " + r.answer.uppercase() + " ")).isTrue()
                com.google.common.truth.Truth.assertThat(p.isCorrect(r, r.answer + "1")).isFalse()
            }
        }
    }
}
