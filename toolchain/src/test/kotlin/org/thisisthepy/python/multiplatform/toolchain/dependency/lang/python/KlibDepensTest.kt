package org.thisisthepy.python.multiplatform.toolchain.dependency.lang.python

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** docs/SPEC.md §1.10: `integration()` packages are checked for a `KLIBDEPENS` file in their dist-info. */
class KlibDepensTest {
    private fun sitePackages(): File = createTempDirectory("klibdepens").toFile()

    private fun File.distInfo(dir: String, withKlibDepens: Boolean) {
        val d = File(this, dir).apply { mkdirs() }
        File(d, "METADATA").writeText("Metadata-Version: 2.1\n")
        if (withKlibDepens) File(d, "KLIBDEPENS").writeText("")
    }

    @Test
    fun `a wheel with KLIBDEPENS passes and one without is reported`() {
        val sp = sitePackages()
        sp.distInfo("good-1.0.dist-info", true)
        sp.distInfo("bad-1.0.dist-info", false)

        assertEquals(listOf("bad"), findIntegrationsWithoutKlibDepens(sp, listOf("good", "bad")))
    }

    @Test
    fun `names are matched across case hyphen underscore and dot`() {
        val sp = sitePackages()
        sp.distInfo("My_Pkg-2.0.dist-info", true)
        sp.distInfo("other_pkg-1.0.dist-info", false)

        val missing = findIntegrationsWithoutKlibDepens(sp, listOf("my-pkg", "MY.PKG", "Other-Pkg", "other.pkg"))

        assertEquals(listOf("Other-Pkg", "other.pkg"), missing)
    }

    @Test
    fun `version specifiers extras and markers do not hide the name`() {
        val sp = sitePackages()
        sp.distInfo("pycomposeui-1.2.dist-info", true)

        assertEquals(
            emptyList(),
            findIntegrationsWithoutKlibDepens(
                sp,
                listOf("pycomposeui>=1.0", "pycomposeui[x]==1.2; python_version>'3'", "pycomposeui @ https://e.com/a.whl"),
            ),
        )
    }

    @Test
    fun `a package that is not installed is reported`() {
        assertEquals(listOf("ghost"), findIntegrationsWithoutKlibDepens(sitePackages(), listOf("ghost")))
    }

    @Test
    fun `a similarly named distribution does not satisfy the check`() {
        val sp = sitePackages()
        sp.distInfo("pkg_extra-1.0.dist-info", true)

        assertEquals(listOf("pkg"), findIntegrationsWithoutKlibDepens(sp, listOf("pkg")))
    }

    @Test
    fun `no integrations reports nothing`() {
        assertEquals(emptyList(), findIntegrationsWithoutKlibDepens(sitePackages(), emptyList()))
    }

    @Test
    fun `the warning names the package and suggests implementation`() {
        val message = klibDepensWarning("pycomposeui")
        assertTrue("pycomposeui" in message && "implementation(\"pycomposeui\")" in message)
    }

    @Test
    fun `site-packages is found in a unix venv`() {
        val venv = createTempDirectory("venv").toFile()
        val sp = File(venv, "lib/python3.13/site-packages").apply { mkdirs() }

        assertEquals(sp, findSitePackages(venv))
    }

    @Test
    fun `site-packages is found in a windows venv`() {
        val venv = createTempDirectory("venv").toFile()
        val sp = File(venv, "Lib/site-packages").apply { mkdirs() }

        assertEquals(sp, findSitePackages(venv))
    }

    @Test
    fun `a missing venv has no site-packages`() {
        assertNull(findSitePackages(File(createTempDirectory("venv").toFile(), ".venv")))
    }
}
