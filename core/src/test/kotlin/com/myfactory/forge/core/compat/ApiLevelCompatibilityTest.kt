package com.myfactory.forge.core.compat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Android 7 floor.
 *
 * :core is a plain JVM module, so nothing stops someone reaching for
 * java.time or java.nio.file, which compile fine here and then throw
 * NoClassDefFoundError on an API 24 device. Desugaring is deliberately off, so
 * this test scans the compiled classes for those references instead.
 *
 * If this fails, the fix is to use java.io.File and epoch millis, not to add
 * desugaring: the 32-bit low-end build is the reason the constraint exists.
 */
class ApiLevelCompatibilityTest {

    /** Packages that do not exist on API 24 without desugaring. */
    private val forbidden = listOf(
        "java/time/",
        "java/nio/file/",
        "java/util/Optional",
        "java/util/stream/",
        "java/util/function/",
    )

    @Test
    fun `core does not reference APIs that are missing on Android 7`() {
        val classesDir = findClassesDir()
        val offenders = mutableListOf<String>()

        classesDir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { file ->
                val bytes = file.readBytes()
                // Class-file constant pool entries are modified UTF-8, so a
                // plain ISO-8859-1 read is enough to find these ASCII names.
                val text = String(bytes, Charsets.ISO_8859_1)
                for (name in forbidden) {
                    if (text.contains(name)) {
                        offenders += "${file.name} references $name"
                    }
                }
            }

        assertTrue(
            "These would crash on Android 7:" + NEWLINE + offenders.joinToString(NEWLINE),
            offenders.isEmpty(),
        )
    }

    private fun findClassesDir(): File {
        // Works whether the test runs from the module directory or the root.
        val candidates = listOf(
            File("build/classes/kotlin/main"),
            File("core/build/classes/kotlin/main"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate the compiled core classes; looked in $candidates")
    }

    private val NEWLINE: String = 10.toChar().toString()
}
