package org.thisisthepy.python.multiplatform.tcl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers `run`'s argument handling and exit codes without touching the network -- unlike
 * [InstallerTest], which deliberately does hit real `uv`/PyPI, these pass a canned `install`
 * lambda so a bad command line never even has the chance to call it (asserted explicitly below by
 * failing loudly if it is invoked).
 */
class CliArgsTest {
    private val anyDir = File(".")
    private val mustNotInstall: (String, File) -> Result<String> = { _, _ ->
        error("run() must not call the installer for this input")
    }

    @Test
    fun `no arguments prints usage and exits non-zero`() {
        val errors = mutableListOf<String>()

        val code = execute(emptyArray(), anyDir, install = mustNotInstall, err = errors::add)

        assertEquals(1, code)
        assertTrue(errors.any { it.contains("Usage") })
    }

    @Test
    fun `install without a package name is an error and never calls the installer`() {
        val errors = mutableListOf<String>()

        val code = execute(arrayOf("install"), anyDir, install = mustNotInstall, err = errors::add)

        assertEquals(1, code)
        assertTrue(errors.any { it.contains("missing", ignoreCase = true) })
    }

    @Test
    fun `unknown command is an error`() {
        val errors = mutableListOf<String>()

        val code = execute(arrayOf("bogus"), anyDir, install = mustNotInstall, err = errors::add)

        assertEquals(1, code)
        assertTrue(errors.any { it.contains("unknown", ignoreCase = true) })
    }

    @Test
    fun `install delegates to the installer and prints its output on success`() {
        val out = mutableListOf<String>()

        val code = execute(arrayOf("install", "somepkg"), anyDir, install = { pkg, _ -> Result.success("added $pkg") }, out = out::add)

        assertEquals(0, code)
        assertTrue(out.any { it.contains("added somepkg") })
    }

    @Test
    fun `install surfaces a failure from the installer as a non-zero exit`() {
        val errors = mutableListOf<String>()

        val code =
            execute(
                arrayOf("install", "somepkg"),
                anyDir,
                install = { _, _ -> Result.failure(RuntimeException("network down")) },
                err = errors::add,
            )

        assertEquals(1, code)
        assertTrue(errors.any { it.contains("network down") })
    }
}
