package com.lagradost.shiro

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** Every concrete Activity in the sources must be declared in a manifest, or opening it crashes the app. */
class ManifestTest {
    private val main = File("src/main")

    @Test
    fun allActivitiesAreDeclared() {
        val manifests = main.parentFile.listFiles { f -> f.isDirectory }!!
            .map { File(it, "AndroidManifest.xml") }.filter { it.exists() }
        val declared = manifests.flatMap { m ->
            Regex("""<activity[^>]*?android:name="([^"]+)"""").findAll(m.readText()).map { it.groupValues[1] }.toList()
        }.map { it.substringAfterLast('.') }.toSet()

        val classes = File(main, "java").walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            Regex("""^(?:open |abstract )?class (\w+)\s*(?:\([^)]*\))?\s*:\s*[^\n{]*Activity\b""", RegexOption.MULTILINE)
                .findAll(f.readText()).map { it.groupValues[1] }
        }.toSet()

        assertThat(classes).isNotEmpty()
        val missing = classes.filter { it !in declared && it != "BaseActivity" }
        assertThat(missing).isEmpty()
    }
}
