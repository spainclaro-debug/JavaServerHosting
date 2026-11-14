package dev.javahost.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DependencyResolverTest {

    private fun pkg(
        name: String,
        version: String = "1.0",
        depends: List<List<String>> = emptyList(),
        provides: List<String> = emptyList()
    ) = TermuxPackage(
        name = name,
        version = version,
        filename = "pool/$name.deb",
        sha256 = "aa",
        size = 100,
        depends = depends.map { group -> group.map { DependencyAlternative(it) } },
        provides = provides
    )

    private fun openJdkDeps() = listOf(
        listOf("ca-certificates-java"),
        listOf("libandroid-shmem"),
        listOf("libandroid-spawn"),
        listOf("libiconv"),
        listOf("libjpeg-turbo"),
        listOf("zlib"),
        listOf("littlecms"),
        listOf("alsa-plugins")
    )

    private fun fullRepo() = listOf(
        pkg("openjdk-21", "21.0.13-2", openJdkDeps()),
        pkg("openjdk-17", "17.0.21-2", openJdkDeps()),
        pkg("ca-certificates-java", depends = listOf(listOf("ca-certificates"))),
        pkg("libandroid-shmem", "0.7"),
        pkg("libandroid-spawn", "0.3"),
        pkg("libiconv", "1.19.1"),
        pkg("libjpeg-turbo", "3.2.1"),
        pkg("zlib", "1.4"),
        pkg("littlecms", "2.20"),
        pkg("alsa-plugins", depends = listOf(listOf("alsa-lib"), listOf("pulseaudio"))),
        pkg("ca-certificates", "1:2026.11.05-1")
    )

    @Test
    fun resolvesJreClosureAndSkipsIrrelevantPackages() {
        val resolver = TermuxDependencyResolver(fullRepo())
        val names = resolver.resolve("openjdk-21").map { it.name }
        assertTrue(names.contains("openjdk-21"))
        assertTrue(names.contains("ca-certificates-java"))
        assertTrue(names.contains("libandroid-shmem"))
        assertTrue(names.contains("libandroid-spawn"))
        assertTrue(names.contains("libiconv"))
        assertTrue(names.contains("libjpeg-turbo"))
        assertTrue(names.contains("zlib"))
        assertTrue(names.contains("littlecms"))
        assertFalse("audio plugins must be skipped", names.contains("alsa-plugins"))
        assertFalse("alsa-lib must be skipped", names.contains("alsa-lib"))
        assertFalse("pulseaudio must be skipped", names.contains("pulseaudio"))
        assertFalse("system ca-certificates bundle must be skipped", names.contains("ca-certificates"))
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun resolvesRequestedMajorOnly() {
        val resolver = TermuxDependencyResolver(fullRepo())
        val names = resolver.resolve("openjdk-17").map { it.name }
        assertTrue(names.contains("openjdk-17"))
        assertFalse(names.contains("openjdk-21"))
    }

    @Test
    fun picksLatestVersionOfDuplicatePackages() {
        val repo = listOf(
            pkg("openjdk-21", "21.0.13-2", openJdkDeps()),
            pkg("zlib", "1.3"),
            pkg("zlib", "1.4"),
            pkg("zlib", "1.4-1"),
            pkg("ca-certificates-java"),
            pkg("libandroid-shmem"),
            pkg("libandroid-spawn"),
            pkg("libiconv"),
            pkg("libjpeg-turbo"),
            pkg("littlecms"),
            pkg("alsa-plugins")
        )
        val resolver = TermuxDependencyResolver(repo)
        val zlib = resolver.resolve("openjdk-21").single { it.name == "zlib" }
        assertEquals("1.4-1", zlib.version)
    }

    @Test
    fun resolvesAlternativeDependenciesToFirstAvailable() {
        val repo = listOf(
            pkg("root", depends = listOf(listOf("missing-a", "present-b"))),
            pkg("present-b")
        )
        val names = TermuxDependencyResolver(repo).resolve("root").map { it.name }
        assertEquals(listOf("root", "present-b"), names)
    }

    @Test
    fun resolvesVirtualProvides() {
        val repo = listOf(
            pkg("root", depends = listOf(listOf("virtual-lib"))),
            pkg("real-lib", provides = listOf("virtual-lib"))
        )
        val names = TermuxDependencyResolver(repo).resolve("root").map { it.name }
        assertEquals(listOf("root", "real-lib"), names)
    }

    @Test
    fun skipsOptionalAlternativesEntirelySkipped() {
        val repo = listOf(pkg("root", depends = listOf(listOf("ca-certificates"))))
        val names = TermuxDependencyResolver(repo).resolve("root").map { it.name }
        assertEquals(listOf("root"), names)
    }

    @Test
    fun missingRootPackageThrowsActionableError() {
        val resolver = TermuxDependencyResolver(listOf(pkg("other")))
        val error = assertThrows(MissingTermuxPackageException::class.java) {
            resolver.resolve("openjdk-21")
        }
        assertTrue(error.message!!.contains("openjdk-21"))
        assertTrue(error.message!!.contains("mirror", ignoreCase = true))
    }

    @Test
    fun missingTransitiveDependencyThrows() {
        val repo = listOf(pkg("root", depends = listOf(listOf("missing-lib"))))
        val error = assertThrows(MissingTermuxPackageException::class.java) {
            TermuxDependencyResolver(repo).resolve("root")
        }
        assertTrue(error.message!!.contains("missing-lib"))
        assertTrue(error.message!!.contains("root"))
    }

    @Test
    fun isAvailableRespectsSkipList() {
        val resolver = TermuxDependencyResolver(fullRepo())
        assertTrue(resolver.isAvailable("openjdk-21"))
        assertFalse(resolver.isAvailable("ca-certificates"))
        assertFalse(resolver.isAvailable("alsa-plugins"))
    }

    @Test
    fun debianVersionOrdering() {
        assertTrue(DebianVersion.compare("21.0.13-2", "21.0.13-1") > 0)
        assertTrue(DebianVersion.compare("21.0.13", "21.0.13-2") < 0)
        assertEquals(0, DebianVersion.compare("21.0.13-2", "21.0.13-2"))
        assertTrue(DebianVersion.compare("1:1.0", "2.0") > 0)
        assertTrue(DebianVersion.compare("21.0.13~rc1", "21.0.13") < 0)
        assertTrue(DebianVersion.compare("1.8.0", "1.10.0") < 0)
        assertTrue(DebianVersion.compare("1.0", "1.0.0") < 0)
        assertTrue(DebianVersion.compare("zlib1.4", "zlib1.4") == 0)
        assertTrue(DebianVersion.compare("2.20", "2.2") > 0)
    }

    @Test
    fun newerMajorWinsAcrossJavaVersions() {
        val versions = listOf("17.0.21-2", "21.0.13-2", "25.0.5-2")
        assertEquals("25.0.5-2", versions.maxWith { a, b -> DebianVersion.compare(a, b) })
    }

    @Test
    fun resolverUsesRuntimeSupportExtrasWhenDependenciesAreIncomplete() {
        // A hypothetical mirror forgets to declare the native libs on openjdk-N.
        val repo = listOf(
            pkg("openjdk-21", depends = listOf(listOf("ca-certificates-java"))),
            pkg("ca-certificates-java"),
            pkg("zlib", "1.4"),
            pkg("libiconv", "1.19.1")
        )
        val names = TermuxDependencyResolver(repo).resolve("openjdk-21").map { it.name }
        assertTrue(names.contains("zlib"))
        assertTrue(names.contains("libiconv"))
    }
}
