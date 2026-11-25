package org.thisisthepy.python.multiplatform.toolchain.bundle

import org.gradle.api.GradleException
import org.thisisthepy.python.multiplatform.packpack.compile.backend.external.ndkHostTag
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * toolchain#155 (Decision 20 (a), 2026-11-25): an APK whose staged `.so` needs `libc++_shared.so` gets the
 * NDK's copy under its ABI in the jniLibs root; one whose payload does not need it gets nothing. Which
 * libraries and from where is pypackpack's `androidRuntimeLibraries` (pypackpack#172).
 */
class StageAndroidRuntimeLibrariesTaskTest {
    private val root = createTempDirectory("android-runtime-stage").toFile()

    @Test
    fun `a payload whose extension needs libc++_shared gets the NDK's copy under its ABI`() {
        val payload = payloadWith("numpy/_core/_multiarray_umath.so" to listOf("libc++_shared.so"))
        val ndk = fakeNdk()
        val jniLibs = File(root, "androidJniLibs")

        val staged = stageAndroidRuntimeLibraries(payload, "aarch64-linux-android", jniLibs, ndk)

        val copied = File(jniLibs, "arm64-v8a/libc++_shared.so")
        assertTrue(copied.isFile, "no $copied")
        assertTrue(copied.readBytes().contentEquals(File(ndkLibDir(ndk, "aarch64-linux-android"), "libc++_shared.so").readBytes()))
        assertEquals(listOf("libc++_shared.so"), staged.map { it.name })
    }

    @Test
    fun `a payload that does not need it gets nothing, and an earlier copy is removed`() {
        val payload = payloadWith("regex/_regex.so" to listOf("libc.so"))
        val jniLibs = File(root, "androidJniLibs")
        File(jniLibs, "arm64-v8a/libc++_shared.so").apply { parentFile.mkdirs() }.writeText("stale")

        val staged = stageAndroidRuntimeLibraries(payload, "aarch64-linux-android", jniLibs, ndkRoot = null)

        assertEquals(emptyList(), staged)
        assertTrue(jniLibs.isDirectory)
        assertEquals(emptyList(), jniLibs.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun `no staged android variant stages nothing`() {
        val jniLibs = File(root, "androidJniLibs")

        assertEquals(emptyList(), stageAndroidRuntimeLibraries(File(root, "missing"), null, jniLibs, ndkRoot = null))
        assertTrue(jniLibs.isDirectory)
    }

    @Test
    fun `a needed library without an NDK fails the build with pypackpack's message`() {
        val payload = payloadWith("tokenizers/tokenizers.so" to listOf("libc++_shared.so"))

        val error = assertFailsWith<GradleException> {
            stageAndroidRuntimeLibraries(payload, "aarch64-linux-android", File(root, "androidJniLibs"), ndkRoot = null)
        }
        assertTrue("tokenizers/tokenizers.so" in error.message.orEmpty(), error.message)
        assertFalse(File(root, "androidJniLibs/arm64-v8a/libc++_shared.so").exists())
    }

    private fun payloadWith(vararg extensions: Pair<String, List<String>>): File {
        val payload = File(root, "payload").apply { mkdirs() }
        extensions.forEach { (path, needed) -> File(payload, path).apply { parentFile.mkdirs() }.writeBytes(elf(needed)) }
        return payload
    }

    private fun ndkLibDir(ndk: File, triple: String) = File(ndk, "toolchains/llvm/prebuilt/${ndkHostTag()}/sysroot/usr/lib/$triple")

    private fun fakeNdk(): File {
        val ndk = File(root, "ndk")
        File(ndkLibDir(ndk, "aarch64-linux-android").apply { mkdirs() }, "libc++_shared.so").writeBytes(elf(listOf("libc.so")))
        return ndk
    }

    /** A minimal little-endian ELF64 shared object: header, `PT_LOAD` + `PT_DYNAMIC`, dynamic array, string table. */
    private fun elf(needed: List<String>): ByteArray {
        val base = 0x1000L
        val dynOffset = 64 + 2 * 56
        val dynEntries = needed.size + 2
        val strOffset = dynOffset + dynEntries * 16
        val strings = ByteArrayOutputStream().apply { write(0) }
        val offsets = needed.map { n -> strings.size().also { strings.write(n.toByteArray()); strings.write(0) } }
        val total = strOffset + strings.size()
        val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0)).position(16)
        b.putShort(3).putShort(183).putInt(1).putLong(0).putLong(64).putLong(0).putInt(0)
        b.putShort(64).putShort(56).putShort(2).putShort(64).putShort(0).putShort(0)
        b.putInt(1).putInt(5).putLong(0).putLong(base).putLong(base).putLong(total.toLong()).putLong(total.toLong()).putLong(0x1000)
        b.putInt(2).putInt(6).putLong(dynOffset.toLong()).putLong(base + dynOffset).putLong(base + dynOffset)
            .putLong(dynEntries * 16L).putLong(dynEntries * 16L).putLong(8)
        offsets.forEach { b.putLong(1).putLong(it.toLong()) }
        b.putLong(5).putLong(base + strOffset).putLong(0).putLong(0)
        b.put(strings.toByteArray())
        return b.array()
    }
}
