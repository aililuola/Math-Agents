package io.github.aililuola.mathproofmesh.desktop;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Per-user single-instance lock with deterministic release. */
public final class SingleInstance implements AutoCloseable {
  private final Path lockPath;
  private final Path lockDirectory;
  private FileChannel channel;
  private FileLock lock;

  public SingleInstance(Path lockPath) {
    this.lockPath = Objects.requireNonNull(lockPath, "lockPath").toAbsolutePath().normalize();
    this.lockDirectory =
        Objects.requireNonNull(this.lockPath.getParent(), "single-instance lock parent directory");
  }

  public synchronized boolean acquire() {
    if (channel != null) {
      throw new IllegalStateException("single-instance lock was already attempted");
    }
    try {
      java.nio.file.Files.createDirectories(lockDirectory);
      channel =
          FileChannel.open(
              lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      try {
        lock = channel.tryLock();
      } catch (OverlappingFileLockException exception) {
        lock = null;
      }
      return lock != null;
    } catch (IOException exception) {
      close();
      throw new IllegalStateException("single-instance lock could not be acquired", exception);
    }
  }

  @Override
  public synchronized void close() {
    try {
      if (lock != null) {
        lock.release();
      }
    } catch (IOException ignored) {
      // Closing the channel below releases the operating-system lock.
    } finally {
      lock = null;
    }
    try {
      if (channel != null) {
        channel.close();
      }
    } catch (IOException ignored) {
      // No secret or mutable domain state is stored in this lock file.
    } finally {
      channel = null;
    }
  }
}
