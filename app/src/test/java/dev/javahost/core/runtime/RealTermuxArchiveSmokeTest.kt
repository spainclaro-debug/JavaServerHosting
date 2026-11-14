package dev.javahost.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * Optional, non-hermetic smoke test against the *real* Termux packages.
 *
 * It is skipped in normal unit-test runs. To execute it, point
 * `JAVAHOST_REAL_JRE_DEBS` at a directory containing the downloaded debs:
 *
 * ```
 * JAVAHOST_REAL_JRE_DEBS=/path/to/debs gradle :app:testDebugUnitTest \
 *     --tests "dev.javahost.core.runtime.RealTermuxArchiveSmokeTest"
 * ```
 *
 * It extracts the real openjdk-21 package + native library packages +
 * ca-certificates-java into a scratch directory, then executes the extracted
 * `bin/java -version`, proving that the extraction/trimming/cacerts overlay
 * rules produce a runnable runtime. This is how the JRE engine was validated
 * against the live mirror; regular CI never needs network access.
 */
class RealTermuxArchiveSmokeTest {

    @get:Rule
    var tmp = TemporaryFolder()

    private class JvmFileSystemOps : FileSystemOps {
        override fun chmod(file: File, mode: Int) {
            val perms = if (mode and 0b001_001_001 != 0) {
                PosixFilePermissions.fromString("rwxr-xr-x")
            } else {
                PosixFilePermissions.fromString("rw-r--r--")
            }
            runCatching { Files.setPosixFilePermissions(file.toPath(), perms) }
        }

        override fun createSymbolicLink(link: File, target: String) {
            val parent = link.absoluteFile.parentFile ?: return
            parent.mkdirs()
            link.delete()
            Files.createSymbolicLink(link.toPath(), parent.toPath().resolve(target))
        }

        override fun isExecutable(file: File): Boolean = Files.isExecutable(file.toPath())
    }

    @Test
    fun installsRealTermuxOpenJdkAndRunsJava() {
        val debsDir = System.getenv("JAVAHOST_REAL_JRE_DEBS")?.let(::File)
        Assume.assumeTrue(
            "Set JAVAHOST_REAL_JRE_DEBS to a directory containing Termux debs",
            debsDir?.isDirectory == true
        )
        val debs = debsDir!!.listFiles()?.filter { it.extension == "deb" }.orEmpty()
        val openJdk = debs.firstOrNull { it.name.startsWith("openjdk-21_") }
            ?: debs.firstOrNull { it.name.startsWith("openjdk-21") }
        Assume.assumeNotNull(openJdk)
        val cacerts = debs.firstOrNull { it.name.startsWith("ca-certificates-java") }
        Assume.assumeNotNull(cacerts)
        val libraries = debs.filter {
            !it.name.startsWith("openjdk-") && !it.name.startsWith("ca-certificates")
        }

        val home = File(tmp.root, "jre21").apply { mkdirs() }
        val fs = JvmFileSystemOps()
        val extractor = DebExtractor(fs)

        val startedAt = System.nanoTime()
        extractor.extract(openJdk!!, PackageExtraction.OpenJdk(21), home) { _, _ -> }
        extractor.extract(cacerts!!, PackageExtraction.JavaCacerts, home) { _, _ -> }
        for (lib in libraries) {
            extractor.extract(lib, PackageExtraction.SharedLibraries, home) { _, _ -> }
        }
        JrePermissions.chmodTree(fs, home)
        val extractionMs = (System.nanoTime() - startedAt) / 1_000_000

        // --- Structural assertions -----------------------------------------
        val java = File(home, "bin/java")
        assertTrue("bin/java must exist", java.isFile)
        assertTrue("bin/java must be executable", fs.isExecutable(java))
        assertTrue("release file must exist", File(home, "release").isFile)
        assertTrue("libjvm.so must exist", File(home, "lib/server/libjvm.so").isFile)
        val trustStore = File(home, "lib/security/cacerts")
        assertTrue("cacerts must be a real file", trustStore.isFile)
        assertTrue("cacerts must be non-empty", trustStore.length() > 10_000)
        assertTrue("cacerts must not be a symlink", !JrePermissions.isSymbolicLink(trustStore))
        assertTrue("jmods must be trimmed", !File(home, "jmods").exists())
        assertTrue("demo must be trimmed", !File(home, "demo").exists())
        assertTrue("include must be trimmed", !File(home, "include").exists())
        assertTrue("man must be trimmed", !File(home, "man").exists())
        assertTrue("lib/ct.sym must be trimmed", !File(home, "lib/ct.sym").exists())
        assertTrue("libz.so.1 must be overlaid", File(home, "lib/libz.so.1").exists() ||
            File(home, "lib/libz.so.1.4").exists())

        val label = ReleaseFile.versionLabel(home)
        assertTrue("release must declare a version, was $label", label?.startsWith("21") == true)
        val installedBytes = JrePermissions.installedBytes(home)

        // --- Execute the extracted runtime ---------------------------------
        val process = ProcessBuilder(java.absolutePath, "-version")
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment()["LD_LIBRARY_PATH"] =
                    "${home.absolutePath}/lib:${home.absolutePath}/lib/server"
            }
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertEquals("java -version must exit 0; output was:\n$output", 0, process.exitValue())
        assertTrue("java -version output was:\n$output", output.contains("version \"$label\"") || output.contains("version \"21"))

        println(
            "REAL JRE SMOKE OK: label=$label installedBytes=$installedBytes " +
                "(${installedBytes / (1024 * 1024)} MB) extractionMs=$extractionMs " +
                "javaVersion=${output.lineSequence().firstOrNull()}"
        )
    }
}
