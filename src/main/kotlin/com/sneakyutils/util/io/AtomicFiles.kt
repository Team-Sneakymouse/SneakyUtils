package com.sneakyutils.util.io

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Replaces individual files without exposing partially written content. Callers must serialize operations that target
 * the same file, including creation of the content snapshot, if an older save must never overwrite a newer one.
 * Filesystems without atomic-move support use a non-atomic replacement after logging a warning once.
 */
object AtomicFiles {

    private val LOGGER = Logger.getLogger("SneakyUtils")
    private val FALLBACK_WARNING_LOGGED = AtomicBoolean()

    @JvmStatic
    @Throws(IOException::class)
    fun writeUtf8(target: Path, content: String) {
        writeUtf8(target, content, AtomicFiles::atomicMove, Files::deleteIfExists)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun copy(target: Path, input: InputStream) {
        write(target, { temporary -> copyAndSync(temporary, input) }, AtomicFiles::atomicMove, Files::deleteIfExists)
    }

    @Throws(IOException::class)
    internal fun writeUtf8(
        target: Path,
        content: String,
        atomicMover: AtomicMover,
        deleter: TemporaryFileDeleter,
    ) {
        write(
            target,
            { temporary -> writeAndSync(temporary, StandardCharsets.UTF_8.encode(content)) },
            atomicMover,
            deleter,
        )
    }

    @Throws(IOException::class)
    private fun write(
        target: Path,
        writer: TemporaryFileWriter,
        atomicMover: AtomicMover,
        deleter: TemporaryFileDeleter,
    ) {
        val absoluteTarget = target.toAbsolutePath()
        val directory = requireNotNull(absoluteTarget.parent) { "target must have a parent directory" }
        Files.createDirectories(directory)

        val temporary = Files.createTempFile(directory, "." + absoluteTarget.fileName + ".", ".tmp")
        var moved = false
        var failure: Throwable? = null
        try {
            writer.write(temporary)
            moveIntoPlace(temporary, absoluteTarget, atomicMover)
            moved = true
            forceDirectory(directory)
        } catch (e: IOException) {
            failure = e
            throw e
        } catch (e: RuntimeException) {
            failure = e
            throw e
        } catch (e: Error) {
            failure = e
            throw e
        } finally {
            if (!moved) {
                try {
                    deleter.delete(temporary)
                } catch (cleanupFailure: IOException) {
                    if (failure != null) failure.addSuppressed(cleanupFailure)
                    else throw cleanupFailure
                } catch (cleanupFailure: RuntimeException) {
                    if (failure != null) failure.addSuppressed(cleanupFailure)
                    else throw cleanupFailure
                } catch (cleanupFailure: Error) {
                    if (failure != null) failure.addSuppressed(cleanupFailure)
                    else throw cleanupFailure
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun writeAndSync(temporary: Path, buffer: ByteBuffer) {
        FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            var buf = buffer
            while (buf.hasRemaining()) channel.write(buf)
            channel.force(true)
        }
    }

    @Throws(IOException::class)
    private fun copyAndSync(temporary: Path, input: InputStream) {
        val inputChannel = Channels.newChannel(input)
        FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { outputChannel ->
            val buffer = ByteBuffer.allocate(8192)
            while (inputChannel.read(buffer) != -1) {
                buffer.flip()
                while (buffer.hasRemaining()) outputChannel.write(buffer)
                buffer.clear()
            }
            outputChannel.force(true)
        }
    }

    @Throws(IOException::class)
    private fun moveIntoPlace(temporary: Path, target: Path, atomicMover: AtomicMover) {
        try {
            atomicMover.move(temporary, target)
        } catch (_: AtomicMoveNotSupportedException) {
            if (FALLBACK_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.warning(
                    "The filesystem does not support atomic file replacement. " +
                        "SneakyUtils will use non-atomic replacement for persisted data.",
                )
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Throws(IOException::class)
    private fun atomicMove(temporary: Path, target: Path) {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    private fun forceDirectory(directory: Path) {
        try {
            FileChannel.open(directory, StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        } catch (_: UnsupportedOperationException) {
            // The filesystem does not support opening or syncing directories.
        } catch (e: AccessDeniedException) {
            if (!System.getProperty("os.name", "").startsWith("Windows")) logDirectorySyncFailure(directory, e)
        } catch (e: IOException) {
            logDirectorySyncFailure(directory, e)
        } catch (e: SecurityException) {
            logDirectorySyncFailure(directory, e)
        }
    }

    private fun logDirectorySyncFailure(directory: Path, exception: Exception) {
        LOGGER.log(
            Level.WARNING,
            "Saved data, but failed to sync its directory to storage: $directory",
            exception,
        )
    }

    private fun interface TemporaryFileWriter {
        @Throws(IOException::class)
        fun write(temporary: Path)
    }

    internal fun interface AtomicMover {
        @Throws(IOException::class)
        fun move(temporary: Path, target: Path)
    }

    internal fun interface TemporaryFileDeleter {
        @Throws(IOException::class)
        fun delete(temporary: Path)
    }
}
