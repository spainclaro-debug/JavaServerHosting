package dev.javahost.core.runtime

import android.content.Context
import dev.javahost.BuildConfig
import dev.javahost.core.api.Downloader
import dev.javahost.core.api.JreController
import dev.javahost.core.model.JobProgress
import dev.javahost.core.model.JobState
import dev.javahost.core.model.JreInstallation
import dev.javahost.core.storage.Paths
import dev.javahost.core.storage.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Downloads and installs aarch64 OpenJDK runtimes from Termux APT mirrors.
 *
 * Responsibilities:
 *  - mirror fallback: user-configured mirror -> build primary -> build fallback;
 *  - fetching/parsing the `Packages` index and resolving the dependency closure;
 *  - downloading, size/SHA-256 verifying every `.deb` with progress;
 *  - unpacking into a staging directory, overlaying shared libraries and the
 *    ca-certificates-java trust store, trimming development-only content;
 *  - an atomic directory swap plus a marker file, so `isInstalled()` never
 *    reports a half-unpacked runtime;
 *  - user archive imports and execute-bit self-healing.
 *
 * All state is exposed as [StateFlow]s so the foreground service and Compose UI
 * can observe the same instance. Long operations run on [Dispatchers.IO] and
 * check for coroutine cancellation between steps.
 */
class JreManager(
    context: Context,
    private val settings: SettingsStore,
    private val downloader: Downloader,
    private val fs: FileSystemOps = AndroidFileSystemOps
) : JreController {

    private val appContext: Context = context.applicationContext
    private val paths = Paths(appContext)

    private val debExtractor = DebExtractor(fs)
    private val archiveImporter = JreArchiveImporter(fs)

    /** Serializes installs/imports/uninstalls; downloads of different majors are rare. */
    private val opMutex = Mutex()

    /** Major -> coroutine currently performing an install/import (for cancelInstall). */
    private val runningJobs = ConcurrentHashMap<Int, Job>()

    private val versionLabels = ConcurrentHashMap<Int, String>()
    private val installedBytesCache = ConcurrentHashMap<Int, Long>()

    private val _installations = MutableStateFlow<List<JreInstallation>>(emptyList())
    override val installations: StateFlow<List<JreInstallation>> = _installations.asStateFlow()

    private val _jobs = MutableStateFlow<Map<String, JobProgress>>(emptyMap())
    override val jobs: StateFlow<Map<String, JobProgress>> = _jobs.asStateFlow()

    init {
        refreshInstallations()
    }

    // ---------------------------------------------------------------------
    // Install
    // ---------------------------------------------------------------------

    override suspend fun install(major: Int): Result<JreInstallation> {
        if (major !in SUPPORTED_MAJORS) {
            return Result.failure(
                JreInstallException(
                    "Java $major is not supported. Supported Java versions: " +
                        SUPPORTED_MAJORS.joinToString(", ") + "."
                )
            )
        }
        val existingJob = runningJobs[major]
        if (existingJob != null && existingJob.isActive) {
            return Result.failure(
                JreInstallException("A Java $major installation is already running.")
            )
        }
        val callerJob = currentCoroutineContext()[Job]
        if (callerJob != null) runningJobs[major] = callerJob
        var published: JobProgress? = null
        try {
            val installation = opMutex.withLock {
                withContext(Dispatchers.IO) {
                    runInstall(major) { progress -> published = progress }
                }
            }
            return Result.success(installation)
        } catch (e: CancellationException) {
            publishJob(
                major,
                fraction = published?.fraction,
                bytesDone = published?.bytesDone ?: 0L,
                bytesTotal = published?.bytesTotal ?: 0L,
                state = JobState.CANCELLED,
                message = "Installation cancelled."
            )
            throw e
        } catch (e: Exception) {
            val message = userMessage(e)
            publishJob(major, state = JobState.FAILED, message = message)
            val failure = if (e is JreInstallException) e else JreInstallException(message, e)
            return Result.failure(failure)
        } finally {
            if (callerJob != null) runningJobs.remove(major, callerJob)
            runCatching { paths.jreStageDir(major).deleteRecursively() }
        }
    }

    override fun cancelInstall(major: Int) {
        runningJobs[major]?.cancel(
            CancellationException("Installation of Java $major was cancelled.")
        )
    }

    private suspend fun runInstall(
        major: Int,
        onProgressPublished: (JobProgress) -> Unit
    ): JreInstallation {
        val staging = paths.jreStageDir(major)
        val downloaded = ArrayList<File>()
        var bytesTotal = 0L
        var bytesDone = 0L
        var lastFraction: Float? = null
        var lastEmitNanos = 0L

        fun emit(
            fraction: Float?,
            done: Long = bytesDone,
            total: Long = bytesTotal,
            message: String? = null,
            force: Boolean = false
        ) {
            val now = System.nanoTime()
            val changed = fraction == null ||
                lastFraction == null ||
                abs(fraction - lastFraction!!) >= PROGRESS_EPSILON
            if (!force && (!changed || now - lastEmitNanos < PROGRESS_INTERVAL_NANOS)) return
            lastEmitNanos = now
            lastFraction = fraction
            publishJob(major, fraction, done, total, message = message)
            _jobs.value[jobKey(major)]?.let(onProgressPublished)
        }

        emit(null, message = "Preparing to install Java $major\u2026", force = true)
        try {
            currentCoroutineContext().ensureActive()
            val mirrors = mirrorCandidates()
            if (mirrors.isEmpty()) {
                throw JreInstallException(
                    "No usable Termux mirror is configured. Configure an HTTPS mirror in Settings " +
                        "or check your internet connection."
                )
            }
            val (mirror, packages) = fetchPackageIndex(mirrors) { status ->
                emit(lastFraction, message = status)
            }
            val resolver = TermuxDependencyResolver(packages)
            val rootPackage = "openjdk-$major"
            if (!resolver.isAvailable(rootPackage)) {
                val available = SUPPORTED_MAJORS.filter { resolver.isAvailable("openjdk-$it") }
                val hint = if (available.isEmpty()) {
                    "No supported Java runtime was found on ${hostLabel(mirror)}; try another mirror."
                } else {
                    "This mirror offers Java ${available.joinToString(", ")}. Pick one of those versions."
                }
                throw JreInstallException("Java $major is not available from this mirror. $hint")
            }
            val plan = try {
                resolver.resolve(rootPackage)
            } catch (e: MissingTermuxPackageException) {
                throw JreInstallException(e.message ?: "Could not resolve the Java runtime dependencies.", e)
            }
            currentCoroutineContext().ensureActive()
            bytesTotal = plan.sumOf { it.size.coerceAtLeast(0L) }
            ensureEnoughSpace(bytesTotal)
            cleanupWork(major)
            staging.deleteRecursively()
            if (!staging.mkdirs()) {
                throw JreInstallException("Cannot create the staging directory ${staging.path}.")
            }

            // ---- Download --------------------------------------------------
            var completed = 0L
            for ((index, pkg) in plan.withIndex()) {
                currentCoroutineContext().ensureActive()
                val url = TermuxMirrors.packageUrl(mirror, pkg.filename)
                val local = File(paths.downloadsDir, debFileName(pkg, index))
                local.delete()
                emit(
                    downloadFraction(completed, 0, bytesTotal),
                    completed,
                    bytesTotal,
                    "Downloading ${pkg.name} (${index + 1}/${plan.size})\u2026",
                    force = true
                )
                try {
                    downloader.download(
                        url = url,
                        destination = local,
                        expectedSha256 = pkg.sha256.ifBlank { null },
                        onProgress = { done, _ ->
                            emit(
                                downloadFraction(
                                    completed, done, bytesTotal, span = DOWNLOAD_SPAN
                                ).coerceIn(0f, DOWNLOAD_SPAN),
                                done = completed + done,
                                total = bytesTotal
                            )
                        }
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw JreInstallException(
                        "Downloading ${pkg.name} from ${hostLabel(mirror)} failed: " +
                            "${e.message ?: e.javaClass.simpleName}",
                        e
                    )
                }
                downloaded += local
                completed += local.length()
                emit(
                    downloadFraction(completed, 0, bytesTotal),
                    completed,
                    bytesTotal,
                    null,
                    force = true
                )
            }

            // ---- Unpack ----------------------------------------------------
            currentCoroutineContext().ensureActive()
            emit(DOWNLOAD_SPAN, completed, bytesTotal, "Installing Java $major\u2026", force = true)
            var extractedBytes = 0L
            fun extractPackage(deb: File, extraction: PackageExtraction) {
                debExtractor.extract(deb, extraction, staging) { done, _ ->
                    val allDone = extractedBytes + done
                    val fraction = if (bytesTotal > 0) {
                        DOWNLOAD_SPAN + UNPACK_SPAN * (allDone.toDouble() / bytesTotal).toFloat()
                    } else {
                        null
                    }
                    emit(fraction?.coerceIn(DOWNLOAD_SPAN, DOWNLOAD_SPAN + UNPACK_SPAN))
                }
            }

            val rootIndex = plan.indexOfFirst { it.name == rootPackage }
            if (rootIndex < 0) {
                throw JreInstallException(
                    "The mirror's package index changed while installing Java $major (no $rootPackage). " +
                        "Try again."
                )
            }
            val rootDeb = downloaded[rootIndex]
            extractPackage(rootDeb, PackageExtraction.OpenJdk(major))
            extractedBytes += rootDeb.length()
            for ((index, pkg) in plan.withIndex()) {
                if (pkg.name == rootPackage) continue
                currentCoroutineContext().ensureActive()
                val extraction = when {
                    pkg.name == CA_CERTIFICATES_JAVA -> PackageExtraction.JavaCacerts
                    else -> PackageExtraction.SharedLibraries
                }
                val deb = downloaded[index]
                extractPackage(deb, extraction)
                extractedBytes += deb.length()
            }

            // ---- Validate the unpacked runtime -----------------------------
            currentCoroutineContext().ensureActive()
            val javaFile = File(staging, "bin/java")
            if (!javaFile.isFile) {
                throw JreInstallException(
                    "Installation failed: 'bin/java' is missing after unpacking $rootPackage. " +
                        "The mirror's package layout looks unexpected; try another mirror."
                )
            }
            val libjvm = File(staging, "lib/server/libjvm.so")
            if (!libjvm.isFile) {
                throw JreInstallException(
                    "Installation failed: 'lib/server/libjvm.so' is missing. " +
                        "The OpenJDK package from this mirror looks incomplete; try another mirror."
                )
            }
            val cacerts = File(staging, "lib/security/cacerts")
            if (!cacerts.isFile || cacerts.length() == 0L) {
                throw JreInstallException(
                    "Installation failed: the TLS trust store (cacerts) is missing. " +
                        "The ca-certificates-java package from this mirror is broken; try another mirror."
                )
            }
            JrePermissions.chmodTree(fs, staging)
            if (!fs.isExecutable(javaFile)) {
                throw JreInstallException(
                    "The installed java binary is not executable. App-private storage may be mounted " +
                        "noexec on this device; move the app to internal storage and retry."
                )
            }
            emit(DOWNLOAD_SPAN + UNPACK_SPAN, completed, bytesTotal, "Finishing installation\u2026", force = true)
            val label = ReleaseFile.versionLabel(staging)?.takeIf { it.isNotBlank() }
                ?: "Java $major"
            val installation = finalizeInstallation(major, staging, label)
            publishJob(
                major, 1f, bytesTotal, bytesTotal,
                state = JobState.SUCCEEDED, message = "Java $label installed."
            )
            return installation
        } finally {
            // A failed or cancelled install must never leave a half-unpacked
            // staging tree or up to ~110 MB of deb files behind.
            runCatching { staging.deleteRecursively() }
            for (deb in downloaded) runCatching { deb.delete() }
        }
    }

    // ---------------------------------------------------------------------
    // Import
    // ---------------------------------------------------------------------

    override suspend fun importArchive(file: File, displayName: String): Result<JreInstallation> {
        val callerJob = currentCoroutineContext()[Job]
        var detectedMajor: Int? = null
        try {
            val installation = opMutex.withLock {
                withContext(Dispatchers.IO) {
                    runImport(file, displayName) { major ->
                        detectedMajor = major
                        if (callerJob != null) runningJobs[major] = callerJob
                    }
                }
            }
            return Result.success(installation)
        } catch (e: CancellationException) {
            detectedMajor?.let {
                publishJob(it, state = JobState.CANCELLED, message = "Import cancelled.")
            }
            throw e
        } catch (e: Exception) {
            val message = userMessage(e)
            detectedMajor?.let { publishJob(it, state = JobState.FAILED, message = message) }
            val failure = if (e is JreInstallException) e else JreInstallException(message, e)
            return Result.failure(failure)
        } finally {
            val major = detectedMajor
            if (major != null && callerJob != null) runningJobs.remove(major, callerJob)
        }
    }

    override suspend fun importDirectory(
        sourceDir: File,
        displayName: String
    ): Result<JreInstallation> {
        val callerJob = currentCoroutineContext()[Job]
        var detectedMajor: Int? = null
        try {
            val installation = opMutex.withLock {
                withContext(Dispatchers.IO) {
                    runImportDirectory(sourceDir, displayName) { major ->
                        detectedMajor = major
                        if (callerJob != null) runningJobs[major] = callerJob
                    }
                }
            }
            return Result.success(installation)
        } catch (e: CancellationException) {
            detectedMajor?.let {
                publishJob(it, state = JobState.CANCELLED, message = "Import cancelled.")
            }
            throw e
        } catch (e: Exception) {
            val message = userMessage(e)
            detectedMajor?.let { publishJob(it, state = JobState.FAILED, message = message) }
            val failure = if (e is JreInstallException) e else JreInstallException(message, e)
            return Result.failure(failure)
        } finally {
            val major = detectedMajor
            if (major != null && callerJob != null) runningJobs.remove(major, callerJob)
        }
    }

    /**
     * Imports a runtime that is already unpacked on disk (SAF folder import).
     * The directory may be the JRE home or a parent containing it.
     */
    private suspend fun runImportDirectory(
        sourceDir: File,
        displayName: String,
        onMajorDetected: (Int) -> Unit
    ): JreInstallation {
        var succeeded = false
        var staging: File? = null
        try {
            currentCoroutineContext().ensureActive()
            if (!sourceDir.isDirectory) {
                throw JreInstallException("The selected folder is no longer available.")
            }
            val home = JreRuntimeValidation.locateJavaHome(sourceDir)
                ?: throw JreInstallException(
                    "No Java runtime was found in the selected folder. Pick the folder that " +
                        "contains bin/java (or a parent folder such as the extracted jdk-21 directory)."
                )
            validateRuntime(home, displayName)
            val label = ReleaseFile.versionLabel(home)?.takeIf { it.isNotBlank() }
                ?: displayName.ifBlank { home.name }
            val major = ReleaseFile.majorVersion(ReleaseFile.versionLabel(home))
                ?: ReleaseFile.majorVersionFromName(displayName)
                ?: ReleaseFile.majorVersionFromName(home.name)
                ?: throw JreInstallException(
                    "Could not determine the Java version of this folder. Rename the folder to " +
                        "include its version (for example 'jdk-21.0.5') and try again."
                )
            if (major !in SUPPORTED_IMPORT_MAJORS) {
                throw JreInstallException(
                    "This folder is Java $major, which JavaHost cannot launch. " +
                        "Use a Java 17, 21 or 25 runtime."
                )
            }
            onMajorDetected(major)
            val totalBytes = directorySize(home)
            publishJob(
                major, 0f, 0, totalBytes,
                message = "Importing Java $major from folder\u2026"
            )
            currentCoroutineContext().ensureActive()
            val stage = paths.jreStageDir(major)
            staging = stage
            stage.deleteRecursively()
            if (!home.renameTo(stage)) {
                // SAF/cache and filesDir are normally on the same filesystem, but
                // fall back to a copy when rename is not possible.
                home.copyRecursively(stage, overwrite = true)
            }
            currentCoroutineContext().ensureActive()
            val stagedJava = File(stage, "bin/java")
            if (!stagedJava.isFile) {
                throw JreInstallException(
                    "The selected runtime's bin/java could not be imported. " +
                        "Check free storage and try again."
                )
            }
            if (!File(stage, "lib/server/libjvm.so").isFile &&
                !File(stage, "lib/aarch64/server/libjvm.so").isFile
            ) {
                throw JreInstallException(
                    "The selected folder is missing libjvm.so, so it is not a complete JRE. " +
                        "Extract the full runtime archive and try again."
                )
            }
            JrePermissions.chmodTree(fs, stage)
            if (!fs.isExecutable(stagedJava)) {
                throw JreInstallException(
                    "The imported java binary is not executable. App-private storage may be " +
                        "mounted noexec on this device."
                )
            }
            publishJob(
                major, 0.9f, totalBytes, totalBytes,
                message = "Finishing folder import\u2026"
            )
            currentCoroutineContext().ensureActive()
            val finalLabel = ReleaseFile.versionLabel(stage)?.takeIf { it.isNotBlank() } ?: label
            val installation = finalizeInstallation(major, stage, finalLabel)
            succeeded = true
            publishJob(
                major, 1f, totalBytes, totalBytes,
                state = JobState.SUCCEEDED, message = "Java $finalLabel imported from folder."
            )
            return installation
        } finally {
            if (!succeeded) runCatching { staging?.deleteRecursively() }
        }
    }

    /** Shared completeness/architecture checks for user-supplied runtimes. */
    private fun validateRuntime(home: File, displayName: String) {
        val javaFile = File(home, "bin/java")
        if (!javaFile.isFile) {
            throw JreInstallException(
                "The selected folder does not contain a Java runtime (bin/java is missing)."
            )
        }
        if (!JreRuntimeValidation.looksLikeAndroidBionic(javaFile)) {
            throw JreInstallException(
                "This Java runtime is not an Android/bionic build, so Android cannot execute it. " +
                    "Official Oracle or Adoptium *Linux* builds use glibc and will fail on Android. " +
                    "Use a Termux/Pojav-style Android aarch64 runtime (or let JavaHost download one)."
            )
        }
        val arch = JreRuntimeValidation.architecture(home)
        if (!JreRuntimeValidation.isArm64(arch)) {
            throw JreInstallException(
                "This runtime is built for ${arch ?: "a different architecture"}; " +
                    "JavaHost needs an arm64-v8a (aarch64) runtime. Selected: $displayName."
            )
        }
    }

    private fun directorySize(dir: File): Long {
        var total = 0L
        runCatching {
            dir.walkTopDown().forEach { if (it.isFile) total += it.length() }
        }
        return total
    }

    private suspend fun runImport(
        file: File,
        displayName: String,
        onMajorDetected: (Int) -> Unit
    ): JreInstallation {
        var succeeded = false
        var staging: File? = null
        val tempRoot = File(paths.jreDir, "import-tmp-${System.nanoTime()}")
        try {
            currentCoroutineContext().ensureActive()
            if (!file.isFile) {
                throw JreInstallException("The selected JRE archive no longer exists: ${file.path}")
            }
            val imported = archiveImporter.extract(file, tempRoot)
            validateRuntime(imported.home, displayName)
            val label = imported.versionLabel?.takeIf { it.isNotBlank() }
                ?: displayName.ifBlank { file.nameWithoutExtension }
            val major = ReleaseFile.majorVersion(imported.versionLabel)
                ?: ReleaseFile.majorVersionFromName(displayName)
                ?: ReleaseFile.majorVersionFromName(file.name)
                ?: throw JreInstallException(
                    "Could not determine the Java version of this archive. Rename the file to include " +
                        "its version (for example 'jdk-21.0.5.tar.gz') and import it again."
                )
            if (major !in SUPPORTED_IMPORT_MAJORS) {
                throw JreInstallException(
                    "This archive is Java $major, which JavaHost cannot launch. " +
                        "Use a Java 17, 21 or 25 runtime."
                )
            }
            onMajorDetected(major)
            publishJob(
                major, 0f, 0, file.length(),
                message = "Importing Java $major from archive\u2026"
            )
            val stage = paths.jreStageDir(major)
            staging = stage
            stage.deleteRecursively()
            if (!imported.home.renameTo(stage)) {
                throw JreInstallException(
                    "Could not move the extracted runtime into place. Check that there is enough " +
                        "free storage and try again."
                )
            }
            val javaFile = File(stage, "bin/java")
            if (!javaFile.isFile) {
                throw ArchiveFormatException(
                    "The archive's 'bin/java' could not be unpacked. The archive may be incomplete."
                )
            }
            JrePermissions.chmodTree(fs, stage)
            if (!fs.isExecutable(javaFile)) {
                throw JreInstallException(
                    "The imported java binary is not executable. App-private storage may be mounted " +
                        "noexec on this device."
                )
            }
            publishJob(
                major, 0.95f, file.length(), file.length(),
                message = "Finishing import\u2026"
            )
            currentCoroutineContext().ensureActive()
            val finalLabel = ReleaseFile.versionLabel(stage)?.takeIf { it.isNotBlank() } ?: label
            val installation = finalizeInstallation(major, stage, finalLabel)
            succeeded = true
            publishJob(
                major, 1f, file.length(), file.length(),
                state = JobState.SUCCEEDED, message = "Java $finalLabel imported."
            )
            return installation
        } finally {
            runCatching { tempRoot.deleteRecursively() }
            if (!succeeded) runCatching { staging?.deleteRecursively() }
        }
    }

    // ---------------------------------------------------------------------
    // Uninstall / query / repair
    // ---------------------------------------------------------------------

    override suspend fun uninstall(major: Int) {
        runningJobs[major]?.cancel(CancellationException("Runtime is being uninstalled."))
        opMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { paths.jreHome(major).deleteRecursively() }
                runCatching { paths.jreMarker(major).delete() }
                runCatching { paths.jreStageDir(major).deleteRecursively() }
                cleanupWork(major)
                versionLabels.remove(major)
                installedBytesCache.remove(major)
                _jobs.update { it - jobKey(major) }
                refreshInstallations()
            }
        }
    }

    override fun isInstalled(major: Int): Boolean {
        val home = paths.jreHome(major)
        return home.isDirectory &&
            paths.jreMarker(major).isFile &&
            File(home, "bin/java").isFile
    }

    override fun javaHome(major: Int): File? =
        if (isInstalled(major)) paths.jreHome(major) else null

    override fun javaExecutable(major: Int): File? =
        javaHome(major)?.let { File(it, "bin/java") }?.takeIf { it.isFile }

    override fun versionLabel(major: Int): String {
        versionLabels[major]?.let { return it }
        val label = readMarker(major)?.get("version")
            ?: ReleaseFile.versionLabel(paths.jreHome(major))
            ?: "Java $major"
        versionLabels[major] = label
        return label
    }

    override fun installedBytes(major: Int): Long {
        installedBytesCache[major]?.let { return it }
        val bytes = if (isInstalled(major)) JrePermissions.installedBytes(paths.jreHome(major)) else 0L
        installedBytesCache[major] = bytes
        return bytes
    }

    override suspend fun ensureExecutable(major: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isInstalled(major)) return@withContext false
        val home = paths.jreHome(major)
        val java = File(home, "bin/java")
        if (fs.isExecutable(java)) return@withContext true
        JrePermissions.chmodTree(fs, home)
        fs.isExecutable(java)
    }

    override fun supportedMajors(): List<Int> = SUPPORTED_MAJORS

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    private suspend fun fetchPackageIndex(
        mirrors: List<String>,
        onStatus: (String) -> Unit
    ): Pair<String, List<TermuxPackage>> {
        val failures = ArrayList<String>()
        for (mirror in orderMirrors(mirrors)) {
            onStatus("Loading package index from ${hostLabel(mirror)}\u2026")
            for (url in TermuxMirrors.packagesUrls(mirror)) {
                currentCoroutineContext().ensureActive()
                try {
                    val bytes = downloader.fetchBytes(url, MAX_PACKAGE_INDEX_BYTES)
                    val packages = if (url.endsWith(".gz")) {
                        TermuxPackagesParser.parseGzip(bytes)
                    } else {
                        TermuxPackagesParser.parse(bytes.toString(Charsets.UTF_8))
                    }
                    if (packages.isEmpty()) {
                        throw JreInstallException("The mirror returned an empty package index.")
                    }
                    return mirror to packages
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures += "${hostLabel(mirror)}: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
        throw JreInstallException(
            "Could not load the Termux package index from any mirror. " +
                "Check your connection or set a different mirror in Settings. " +
                "Tried: ${failures.joinToString(" | ")}"
        )
    }

    private fun mirrorCandidates(): List<String> = TermuxMirrors.candidates(
        configured = settings.current().termuxMirror,
        primary = BuildConfig.TERMUX_PRIMARY_MIRROR,
        fallback = BuildConfig.TERMUX_FALLBACK_MIRROR
    )

    /**
     * When the user has not pinned a mirror, probe all candidates in parallel and
     * try the lowest-latency one first. This avoids waiting on a stalled official
     * host before falling back to a nearby mirror. A user-pinned mirror keeps
     * priority and is never reordered.
     */
    private suspend fun orderMirrors(mirrors: List<String>): List<String> {
        if (mirrors.size <= 1) return mirrors
        if (settings.current().termuxMirror.isNotBlank()) return mirrors
        val probeUrl: (String) -> String = { mirror ->
            TermuxMirrors.packageUrl(mirror, TermuxMirrors.PACKAGES_GZ_PATH)
        }
        val probed = coroutineScope {
            mirrors.map { mirror ->
                async { mirror to runCatching { downloader.probe(probeUrl(mirror), 4_000L) }.getOrNull() }
            }.awaitAll()
        }
        return probed.sortedWith { a, b ->
            when {
                a.second == null && b.second == null -> 0
                a.second == null -> 1
                b.second == null -> -1
                else -> a.second!!.compareTo(b.second!!)
            }
        }.map { it.first }
    }

    /**
     * Moves a fully unpacked staging tree into [paths.jreHome] and writes the
     * installation marker. The previous runtime (if any) is kept as a backup
     * until the new one is committed, so a failed marker write cannot destroy a
     * working installation.
     */
    private fun finalizeInstallation(major: Int, staging: File, label: String): JreInstallation {
        val home = paths.jreHome(major)
        val backup = File(paths.jreDir, "jre$major.old")
        backup.deleteRecursively()
        if (home.exists() && !home.renameTo(backup)) {
            throw JreInstallException(
                "Cannot replace the existing Java $major runtime. " +
                    "Close any running servers and make sure there is enough free storage."
            )
        }
        try {
            if (!staging.renameTo(home)) {
                throw JreInstallException(
                    "Could not move the new Java $major runtime into place. " +
                        "Check free storage and try again."
                )
            }
            val installedAt = System.currentTimeMillis()
            writeMarker(major, label, installedAt)
            versionLabels[major] = label
            installedBytesCache.remove(major)
            installedBytes(major)
            refreshInstallations()
            backup.deleteRecursively()
            return JreInstallation(
                major = major,
                versionLabel = label,
                javaHome = home.absolutePath,
                installedBytes = installedBytes(major),
                installedAt = installedAt
            )
        } catch (e: Exception) {
            // Roll back to the previous runtime if the commit failed.
            runCatching { home.deleteRecursively() }
            if (backup.exists()) runCatching { backup.renameTo(home) }
            versionLabels.remove(major)
            installedBytesCache.remove(major)
            refreshInstallations()
            throw e
        }
    }

    private fun writeMarker(major: Int, label: String, installedAt: Long) {
        val marker = paths.jreMarker(major)
        marker.parentFile?.mkdirs()
        val temp = File(marker.parentFile, marker.name + ".tmp")
        temp.writeText(
            buildString {
                append("major=").append(major).append('\n')
                append("version=").append(label.replace('\n', ' ')).append('\n')
                append("installedAt=").append(installedAt).append('\n')
            }
        )
        marker.delete()
        if (!temp.renameTo(marker)) {
            temp.delete()
            throw JreInstallException("Could not record the installed Java $major runtime.")
        }
    }

    private fun readMarker(major: Int): Map<String, String>? {
        val marker = paths.jreMarker(major)
        if (!marker.isFile) return null
        val text = runCatching { marker.readText() }.getOrNull() ?: return null
        val map = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq > 0) map[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return map
    }

    private fun refreshInstallations() {
        val found = ArrayList<JreInstallation>()
        val directories = paths.jreDir.listFiles().orEmpty()
        for (dir in directories) {
            if (!dir.isDirectory) continue
            val major = dir.name.removePrefix("jre").toIntOrNull() ?: continue
            if (!isInstalled(major)) continue
            val marker = readMarker(major)
            val label = marker?.get("version") ?: run {
                val read = ReleaseFile.versionLabel(dir) ?: "Java $major"
                read
            }
            versionLabels[major] = label
            installedBytesCache.remove(major)
            found += JreInstallation(
                major = major,
                versionLabel = label,
                javaHome = dir.absolutePath,
                installedBytes = installedBytes(major),
                installedAt = marker?.get("installedAt")?.toLongOrNull() ?: dir.lastModified()
            )
        }
        _installations.value = found.sortedByDescending { it.major }
    }

    /** Deletes this major's staging dir and any stale staging/import dirs from crashes. */
    private fun cleanupWork(major: Int) {
        runCatching { paths.jreStageDir(major).deleteRecursively() }
        val now = System.currentTimeMillis()
        for (file in paths.jreDir.listFiles().orEmpty()) {
            if (!file.isDirectory) continue
            if (file.name != "stage-jre$major" &&
                (file.name.startsWith("stage-") || file.name.startsWith("import-tmp-")) &&
                now - file.lastModified() > STALE_STAGING_MS
            ) {
                runCatching { file.deleteRecursively() }
            }
        }
    }

    private fun ensureEnoughSpace(downloadBytes: Long) {
        val free = runCatching { paths.jreDir.usableSpace }.getOrDefault(0L)
        if (free <= 0L) return
        val needed = maxOf(
            MIN_INSTALL_SPACE_BYTES,
            downloadBytes + ESTIMATED_INSTALLED_BYTES + SPACE_MARGIN_BYTES
        )
        if (free < needed) {
            throw JreInstallException(
                "Not enough free storage: about ${needed / MB} MB is needed but only " +
                    "${free / MB} MB is available. Free up space and try again."
            )
        }
    }

    private fun publishJob(
        major: Int,
        fraction: Float? = null,
        bytesDone: Long = 0L,
        bytesTotal: Long = 0L,
        state: JobState = JobState.RUNNING,
        message: String? = null
    ) {
        val key = jobKey(major)
        val progress = JobProgress(
            key = key,
            title = "Java $major runtime",
            fraction = fraction,
            bytesDone = bytesDone,
            bytesTotal = bytesTotal,
            state = state,
            message = message
        )
        _jobs.update { it + (key to progress) }
    }

    private fun userMessage(e: Exception): String {
        if (e is JreInstallException) return e.message ?: "Java runtime installation failed."
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "Java runtime installation failed: $detail"
    }

    private fun downloadFraction(completed: Long, current: Long, total: Long): Float =
        downloadFraction(completed, current, total, DOWNLOAD_SPAN)

    private fun downloadFraction(
        completed: Long,
        current: Long,
        total: Long,
        span: Float
    ): Float {
        if (total <= 0L) return 0f
        val done = (completed + current).coerceIn(0L, total)
        return (done.toDouble() / total).toFloat().coerceIn(0f, 1f) * span
    }

    private fun debFileName(pkg: TermuxPackage, index: Int): String {
        val name = sanitizeFileName(pkg.name)
        val version = sanitizeFileName(pkg.version)
        return "$name-$version-$index.deb"
    }

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "package" }

    private fun hostLabel(mirror: String): String =
        mirror.substringAfter("://").substringBefore('/').ifBlank { mirror }

    private fun jobKey(major: Int): String = "jre-$major"

    companion object {
        private const val MB = 1024L * 1024L
        private const val DOWNLOAD_SPAN = 0.80f
        private const val UNPACK_SPAN = 0.18f
        private const val PROGRESS_EPSILON = 0.005f
        private const val PROGRESS_INTERVAL_NANOS = 120_000_000L
        private const val MAX_PACKAGE_INDEX_BYTES = 64L * 1024 * 1024
        private const val MIN_INSTALL_SPACE_BYTES = 320L * MB
        private const val ESTIMATED_INSTALLED_BYTES = 180L * MB
        private const val SPACE_MARGIN_BYTES = 40L * MB
        private const val STALE_STAGING_MS = 60L * 60 * 1000
        private const val CA_CERTIFICATES_JAVA = "ca-certificates-java"

        /** Newest first; 17 and 21 are the versions every current server works with. */
        val SUPPORTED_MAJORS: List<Int> = listOf(25, 21, 17)

        /** Import escape hatch also allows Java 8 for legacy servers. */
        private val SUPPORTED_IMPORT_MAJORS: IntRange = 8..25
    }
}
