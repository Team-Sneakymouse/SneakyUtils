package com.sneakyutils.util.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replaces individual files without exposing partially written content. Callers must serialize operations that target
 * the same file, including creation of the content snapshot, if an older save must never overwrite a newer one.
 * Filesystems without atomic-move support use a non-atomic replacement after logging a warning once.
 */
public final class AtomicFiles {

	private static final Logger LOGGER = Logger.getLogger("SneakyUtils");
	private static final AtomicBoolean FALLBACK_WARNING_LOGGED = new AtomicBoolean();

	public static void writeUtf8(Path target, String content) throws IOException {
		Objects.requireNonNull(content, "content");
		writeUtf8(target, content, AtomicFiles::atomicMove, Files::deleteIfExists);
	}

	public static void copy(Path target, InputStream input) throws IOException {
		Objects.requireNonNull(input, "input");
		write(target, temporary -> copyAndSync(temporary, input), AtomicFiles::atomicMove, Files::deleteIfExists);
	}

	static void writeUtf8(Path target, String content, AtomicMover atomicMover, TemporaryFileDeleter deleter)
			throws IOException {
		Objects.requireNonNull(content, "content");
		write(
				target,
				temporary -> writeAndSync(temporary, StandardCharsets.UTF_8.encode(content)),
				atomicMover,
				deleter
		);
	}

	private static void write(
			Path target,
			TemporaryFileWriter writer,
			AtomicMover atomicMover,
			TemporaryFileDeleter deleter
	) throws IOException {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(atomicMover, "atomicMover");
		Objects.requireNonNull(deleter, "deleter");
		Path absoluteTarget = target.toAbsolutePath();
		Path directory = Objects.requireNonNull(absoluteTarget.getParent(), "target must have a parent directory");
		Files.createDirectories(directory);

		Path temporary = Files.createTempFile(directory, "." + absoluteTarget.getFileName() + ".", ".tmp");
		boolean moved = false;
		Throwable failure = null;
		try {
			writer.write(temporary);
			moveIntoPlace(temporary, absoluteTarget, atomicMover);
			moved = true;
			forceDirectory(directory);
		} catch (IOException | RuntimeException | Error e) {
			failure = e;
			throw e;
		} finally {
			if (!moved) {
				try {
					deleter.delete(temporary);
				} catch (IOException | RuntimeException | Error cleanupFailure) {
					if (failure != null) failure.addSuppressed(cleanupFailure);
					else throw cleanupFailure;
				}
			}
		}
	}

	private static void writeAndSync(Path temporary, ByteBuffer buffer) throws IOException {
		try (FileChannel channel = FileChannel.open(
				temporary,
				StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING
		)) {
			while (buffer.hasRemaining()) channel.write(buffer);
			channel.force(true);
		}
	}

	private static void copyAndSync(Path temporary, InputStream input) throws IOException {
		ReadableByteChannel inputChannel = Channels.newChannel(input);
		try (FileChannel outputChannel = FileChannel.open(
						temporary,
						StandardOpenOption.WRITE,
						StandardOpenOption.TRUNCATE_EXISTING
		)) {
			ByteBuffer buffer = ByteBuffer.allocate(8192);
			while (inputChannel.read(buffer) != -1) {
				buffer.flip();
				while (buffer.hasRemaining()) outputChannel.write(buffer);
				buffer.clear();
			}
			outputChannel.force(true);
		}
	}

	private static void moveIntoPlace(Path temporary, Path target, AtomicMover atomicMover) throws IOException {
		try {
			atomicMover.move(temporary, target);
		} catch (AtomicMoveNotSupportedException e) {
			if (FALLBACK_WARNING_LOGGED.compareAndSet(false, true)) {
				LOGGER.warning("The filesystem does not support atomic file replacement. "
						+ "SneakyUtils will use non-atomic replacement for persisted data.");
			}
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static void atomicMove(Path temporary, Path target) throws IOException {
		Files.move(
				temporary,
				target,
				StandardCopyOption.ATOMIC_MOVE,
				StandardCopyOption.REPLACE_EXISTING
		);
	}

	private static void forceDirectory(Path directory) {
		try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
			channel.force(true);
		} catch (UnsupportedOperationException ignored) {
			// The filesystem does not support opening or syncing directories.
		} catch (AccessDeniedException e) {
			if (!System.getProperty("os.name", "").startsWith("Windows")) logDirectorySyncFailure(directory, e);
		} catch (IOException | SecurityException e) {
			logDirectorySyncFailure(directory, e);
		}
	}

	private static void logDirectorySyncFailure(Path directory, Exception exception) {
		LOGGER.log(
				Level.WARNING,
				"Saved data, but failed to sync its directory to storage: " + directory,
				exception
		);
	}

	private AtomicFiles() {
	}

	@FunctionalInterface
	private interface TemporaryFileWriter {
		void write(Path temporary) throws IOException;
	}

	@FunctionalInterface
	interface AtomicMover {
		void move(Path temporary, Path target) throws IOException;
	}

	@FunctionalInterface
	interface TemporaryFileDeleter {
		void delete(Path temporary) throws IOException;
	}

}
