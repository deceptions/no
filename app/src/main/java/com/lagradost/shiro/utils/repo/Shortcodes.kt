package com.lagradost.shiro.utils.repo

/**
 * Private shortcodes for the components Nekomo and NEX support and have been tested with. Only SHA-256 hashes of the codes are
 * stored here (the codes themselves are never shown or published). The same table is kept in Nekomo and NEX:
 * [digest] must be identical in both apps (a unit test checks it).
 */
object Shortcodes {
    /** kind: "repo" (add a repository), "source" (install one source from [url] repo), "api" (open a built-in/metadata API). */
    data class Entry(val kind: String, val name: String, val url: String?, val id: String?, val type: String?)

    private val table: Map<String, Entry> = mapOf(
        "4f6155cfdb52577cbde480662278bb02261466b95ff3738c7f3bd49180c69920" to Entry("repo", "Nekomo Official Repository", "https://raw.githubusercontent.com/Nekomo-App/neko-source/main/repo/index.json", null, "nekomo"),
        "68c6ef1791756c47c485039c1906d8545a61457932394e47494bd48ac5d91faa" to Entry("repo", "CloudStream official extensions", "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json", null, "cloudstream"),
        "45deabd7eb409c471a718924d99731d9ae6274dab8236650dac2bded4814de98" to Entry("source", "DailymotionProvider", "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json", "DailymotionProvider", "cloudstream"),
        "2232829c9e391ccbe169e5ee6b1d78c7c90301d2c87bdd97ad39f936e830214e" to Entry("source", "InternetArchiveProvider", "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json", "InternetArchiveProvider", "cloudstream"),
        "42d6b3e2c2813f1c0ca144327e9f86d7b5c7477c58f986f257530b9aa5548ba0" to Entry("source", "InvidiousProvider", "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json", "InvidiousProvider", "cloudstream"),
        "af83ed20b55a40725f9e2bcdb2bd7dedf0e6b7e09bc2e3d15ba71a054f27dbca" to Entry("source", "TwitchProvider", "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json", "TwitchProvider", "cloudstream"),
        "44a32938db7268f69a720250c1c30f6027ecc5dc12a0e4020ec627a04132fbbc" to Entry("source", "YoutubeProvider", "https://raw.githubusercontent.com/recloudstream/extensions/master/repo.json", "YoutubeProvider", "cloudstream"),
        "3c06252acdc440a1e80d08ffe2a8ed98af09483c99ddb23b3048ad9cddc67186" to Entry("api", "AniList", "https://graphql.anilist.co", "anilist", "api"),
        "57053d9eba6a26dd77c4113ae41bab8fd34cc188a907fa42ec0895ab4c8ca73b" to Entry("api", "Kitsu", "https://kitsu.io/api/edge", "kitsu", "api"),
        "14cad306c405a81e361876105f682972b9bcc5359004de588c17cd43cd7619b3" to Entry("api", "TVMaze", "https://api.tvmaze.com", "tvmaze", "api")
    )

    private fun hash(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(input.trim().uppercase().toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun lookup(input: String): Entry? = if (input.isBlank()) null else table[hash(input)]

    val size: Int get() = table.size

    /** Fingerprint of the table, for checking that Nekomo and NEX carry the same registry. */
    val digest: String
        get() = java.security.MessageDigest.getInstance("SHA-256").digest(table.keys.sorted().joinToString(",").toByteArray())
            .joinToString("") { "%02x".format(it) }
}
