package dev.javahost.core.launch

import dev.javahost.core.model.ServerFlavor
import dev.javahost.core.model.ServerInstance
import dev.javahost.core.model.ServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LaunchPlanBuilderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var workingDir: File
    private lateinit var javaHome: File
    private lateinit var javaExecutable: File
    private lateinit var jarFile: File

    @Before
    fun setUp() {
        workingDir = temp.newFolder("instance")
        javaHome = temp.newFolder("jre21")
        javaExecutable = File(javaHome, "bin/java").apply {
            parentFile.mkdirs()
            writeText("#!/bin/sh\n")
            setExecutable(true)
        }
        jarFile = File(workingDir, "server.jar").apply { writeText("not really a jar") }
    }

    private fun instance(
        kind: ServerKind,
        memoryMb: Int = 1536,
        jvmArgs: String = "",
        serverArgs: String = "nogui"
    ) = ServerInstance(
        id = "instance-1",
        name = if (kind == ServerKind.MINECRAFT) "Minecraft" else "Generic",
        jarFile = "server.jar",
        kind = kind,
        flavor = if (kind == ServerKind.MINECRAFT) ServerFlavor.VANILLA else ServerFlavor.GENERIC,
        memoryMb = memoryMb,
        jvmArgs = jvmArgs,
        serverArgs = serverArgs
    )

    @Test
    fun `minecraft command and environment shape`() {
        File(workingDir, "jna").mkdirs()
        val mc = instance(
            ServerKind.MINECRAFT,
            memoryMb = 1536,
            jvmArgs = "-XX:+UseSerialGC -Dfoo=\"a b\"",
            serverArgs = "nogui"
        )
        val plan = LaunchPlanBuilder.build(
            instance = mc,
            workingDir = workingDir,
            jarFile = jarFile,
            javaExecutable = javaExecutable,
            javaHome = javaHome,
            memoryMb = 1024
        )
        val command = plan.command

        assertEquals(javaExecutable.absolutePath, command[0])
        assertTrue(command.contains("-Xms1024M"))
        assertTrue(command.contains("-Xmx1024M"))
        assertTrue(command.contains("-Djava.awt.headless=true"))
        assertTrue(command.contains("-Dfile.encoding=UTF-8"))
        assertTrue(command.contains("-Duser.home=${workingDir.absolutePath}"))
        assertTrue(
            command.contains("-Djna.boot.library.path=${File(workingDir, "jna").absolutePath}")
        )

        val jarIndex = command.indexOf("-jar")
        assertTrue(jarIndex > 0)
        assertEquals(jarFile.absolutePath, command[jarIndex + 1])
        assertEquals("nogui", command.last())
        // user JVM args are appended right before -jar
        assertEquals("-XX:+UseSerialGC", command[jarIndex - 2])
        assertEquals("-Dfoo=a b", command[jarIndex - 1])

        assertEquals(1024, plan.memoryMb)
        assertEquals(workingDir.absolutePath, plan.workingDir.absolutePath)
        assertEquals(javaHome.absolutePath, plan.environment["JAVA_HOME"])
        assertEquals(
            "${javaHome.absolutePath}/lib:${javaHome.absolutePath}/lib/server",
            plan.environment["LD_LIBRARY_PATH"]
        )
        assertEquals(
            "${javaHome.absolutePath}/bin:/system/bin",
            plan.environment["PATH"]
        )
        assertEquals(workingDir.absolutePath, plan.environment["HOME"])
        assertEquals(File(workingDir, "tmp").absolutePath, plan.environment["TMPDIR"])
        assertTrue(File(workingDir, "tmp").isDirectory)
    }

    @Test
    fun `jna flag omitted when no dispatcher directory exists`() {
        val mc = instance(ServerKind.MINECRAFT)
        val plan = LaunchPlanBuilder.build(
            mc, workingDir, jarFile, javaExecutable, javaHome, memoryMb = 512
        )
        assertFalse(plan.command.any { it.startsWith("-Djna.boot.library.path=") })
    }

    @Test
    fun `explicit jna directory is used even when it is not under the working dir`() {
        val dispatcherDir = temp.newFolder("shared-jna")
        val mc = instance(ServerKind.MINECRAFT)
        val plan = LaunchPlanBuilder.build(
            mc, workingDir, jarFile, javaExecutable, javaHome,
            memoryMb = 512, jnaDir = dispatcherDir
        )
        assertTrue(
            plan.command.contains("-Djna.boot.library.path=${dispatcherDir.absolutePath}")
        )
    }

    @Test
    fun `generic server uses raw server args verbatim`() {
        val generic = instance(
            ServerKind.GENERIC,
            memoryMb = 512,
            serverArgs = " -port 8080 --verbose "
        )
        val plan = LaunchPlanBuilder.build(
            generic, workingDir, jarFile, javaExecutable, javaHome, memoryMb = 512
        )
        val jarIndex = plan.command.indexOf("-jar")
        assertEquals(
            listOf("-port", "8080", "--verbose"),
            plan.command.subList(jarIndex + 2, plan.command.size)
        )
    }

    @Test
    fun `generic server with blank args gets no trailing arguments`() {
        val generic = instance(ServerKind.GENERIC, serverArgs = "")
        val plan = LaunchPlanBuilder.build(
            generic, workingDir, jarFile, javaExecutable, javaHome, memoryMb = 512
        )
        val jarIndex = plan.command.indexOf("-jar")
        assertEquals(jarIndex + 2, plan.command.size)
    }

    @Test
    fun `minecraft blank server args default to nogui`() {
        val mc = instance(ServerKind.MINECRAFT, serverArgs = "   ")
        val plan = LaunchPlanBuilder.build(
            mc, workingDir, jarFile, javaExecutable, javaHome, memoryMb = 512
        )
        assertEquals("nogui", plan.command.last())
    }

    @Test
    fun `memory is clamped to sane bounds`() {
        assertEquals(256, LaunchPlanBuilder.clampMemoryMb(64))
        assertEquals(16384, LaunchPlanBuilder.clampMemoryMb(64_000))
        assertEquals(1024, LaunchPlanBuilder.clampMemoryMb(1024))
        assertEquals(512, LaunchPlanBuilder.clampMemoryMb(2048, ceilingMb = 512))
        assertEquals(256, LaunchPlanBuilder.clampMemoryMb(128, ceilingMb = 256))
    }

    @Test
    fun `resolved memory prefers instance value then default`() {
        val fromInstance = instance(ServerKind.GENERIC, memoryMb = 2048)
        assertEquals(
            2048,
            LaunchPlanBuilder.resolvedMemoryMb(fromInstance, defaultMb = 1024, ceilingMb = 8192)
        )
        val zero = instance(ServerKind.GENERIC, memoryMb = 0)
        assertEquals(
            1024,
            LaunchPlanBuilder.resolvedMemoryMb(zero, defaultMb = 1024, ceilingMb = 8192)
        )
        assertEquals(
            512,
            LaunchPlanBuilder.resolvedMemoryMb(fromInstance, defaultMb = 1024, ceilingMb = 512)
        )
    }

    @Test
    fun `command line rendering is space separated`() {
        val mc = instance(ServerKind.MINECRAFT)
        val plan = LaunchPlanBuilder.build(
            mc, workingDir, jarFile, javaExecutable, javaHome, memoryMb = 512
        )
        val rendered = plan.commandLine()
        assertTrue(rendered.startsWith(javaExecutable.absolutePath))
        assertTrue(rendered.contains("-Xmx512M"))
        assertNotNull(plan.javaExecutable)
    }
}
