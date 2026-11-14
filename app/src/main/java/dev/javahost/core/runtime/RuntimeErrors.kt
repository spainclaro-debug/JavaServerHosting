package dev.javahost.core.runtime

import java.io.IOException

/**
 * Failure while downloading/unpacking/installing a Java runtime.
 *
 * Messages are user-facing: they should say what failed and what the user can
 * do about it (switch mirror, free space, retry). The UI shows [message] as-is.
 */
open class JreInstallException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The file is not a valid Debian package / tar archive. */
class ArchiveFormatException(message: String, cause: Throwable? = null) : JreInstallException(message, cause)
