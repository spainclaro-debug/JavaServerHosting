package dev.javahost.core.runtime

import java.io.File

/** Test double for [FileSystemOps] that records chmod/symlink calls. */
class FakeFileSystemOps : FileSystemOps {

    data class ChmodCall(val file: File, val mode: Int)
    data class SymlinkCall(val link: File, val target: String)

    val chmods = ArrayList<ChmodCall>()
    val symlinks = ArrayList<SymlinkCall>()
    val executableFiles = HashSet<String>()

    override fun chmod(file: File, mode: Int) {
        chmods += ChmodCall(file, mode)
        if (mode and 0b001_001_001 != 0) executableFiles += file.absolutePath
        else executableFiles -= file.absolutePath
    }

    override fun createSymbolicLink(link: File, target: String) {
        symlinks += SymlinkCall(link, target)
    }

    override fun isExecutable(file: File): Boolean = file.absolutePath in executableFiles

    fun hasChmod(file: File, mode: Int): Boolean = chmods.any { it.file == file && it.mode == mode }

    fun modeOf(file: File): Int? = chmods.lastOrNull { it.file == file }?.mode

    fun linkTarget(link: File): String? = symlinks.lastOrNull { it.link == link }?.target
}
