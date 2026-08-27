package io.github.aililuola.mathproofmesh.persistence;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.nio.charset.StandardCharsets.US_ASCII;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Content-addressed artifact storage with atomic, quota-bounded writes.
 *
 * <p>The global byte path is always {@code artifacts/sha256/xx/hash}. Run
 * ownership and purpose are recorded by the metadata sink.
 */
public final class ArtifactStore {
  public static final long DEFAULT_MAX_ARTIFACT_BYTES = 16L * 1024 * 1024;
  public static final long DEFAULT_QUOTA_BYTES = 2L * 1024 * 1024 * 1024;

  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9_.-]{1,160}");
  private static final int MOVE_ATTEMPTS = 9;

  private final Object writeLock = new Object();
  private final Path root;
  private final Path artifactRoot;
  private final Path namedRunRoot;
  private final String runId;
  private final long maxArtifactBytes;
  private final long quotaBytes;
  private final ArtifactMetadataSink metadataSink;
  private final AtomicMover mover;
  private final Sleeper sleeper;

  public ArtifactStore(Path storageRoot, String runId) {
    this(
        storageRoot,
        runId,
        DEFAULT_MAX_ARTIFACT_BYTES,
        DEFAULT_QUOTA_BYTES,
        ArtifactMetadataSink.noOp());
  }

  public ArtifactStore(
      Path storageRoot,
      String runId,
      long maxArtifactBytes,
      long quotaBytes,
      ArtifactMetadataSink metadataSink) {
    this(
        storageRoot,
        runId,
        maxArtifactBytes,
        quotaBytes,
        metadataSink,
        ArtifactStore::atomicMove,
        Thread::sleep);
  }

  ArtifactStore(
      Path storageRoot,
      String runId,
      long maxArtifactBytes,
      long quotaBytes,
      ArtifactMetadataSink metadataSink,
      AtomicMover mover,
      Sleeper sleeper) {
    this.root = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
    this.runId = requireText(runId, "runId");
    if (maxArtifactBytes < 1) {
      throw new IllegalArgumentException("maxArtifactBytes must be positive");
    }
    if (quotaBytes < maxArtifactBytes) {
      throw new IllegalArgumentException("quotaBytes must be at least maxArtifactBytes");
    }
    this.maxArtifactBytes = maxArtifactBytes;
    this.quotaBytes = quotaBytes;
    this.metadataSink = Objects.requireNonNull(metadataSink, "metadataSink");
    this.mover = Objects.requireNonNull(mover, "mover");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    this.artifactRoot = root.resolve("artifacts").resolve("sha256");
    this.namedRunRoot = root.resolve("named").resolve(sha256(runId.getBytes(StandardCharsets.UTF_8)));
    try {
      ensureDirectory(root);
      ensureDirectory(root.resolve("artifacts"));
      ensureDirectory(artifactRoot);
      ensureDirectory(root.resolve("named"));
      ensureDirectory(namedRunRoot);
    } catch (IOException exception) {
      throw new ArtifactValidationException("failed to initialize artifact storage", exception);
    }
  }

  public String write(
      byte[] content,
      String mediaType,
      String provenanceSource,
      String retentionPolicy,
      String purpose) {
    Objects.requireNonNull(content, "content");
    String hash = sha256(content);
    return writeExpected(
        hash, content, mediaType, provenanceSource, retentionPolicy, purpose);
  }

  public String writeText(
      String content,
      String mediaType,
      String provenanceSource,
      String retentionPolicy,
      String purpose) {
    return write(
        Objects.requireNonNull(content, "content").getBytes(StandardCharsets.UTF_8),
        mediaType,
        provenanceSource,
        retentionPolicy,
        purpose);
  }

  public String savePrompt(
      String stage, String agentId, String systemPrompt, String userPrompt) {
    String content =
        "# SYSTEM\n"
            + Objects.requireNonNull(systemPrompt, "systemPrompt")
            + "\n\n# USER\n"
            + Objects.requireNonNull(userPrompt, "userPrompt")
            + "\n";
    return writeText(
        content,
        "text/plain; charset=utf-8",
        "provider-prompt:" + requireText(stage, "stage") + ":" + requireText(agentId, "agentId"),
        "short-term",
        "provider_prompt");
  }

  public String writeExpected(
      String expectedHash,
      byte[] content,
      String mediaType,
      String provenanceSource,
      String retentionPolicy,
      String purpose) {
    requireHash(expectedHash);
    Objects.requireNonNull(content, "content");
    if (content.length > maxArtifactBytes) {
      throw new ArtifactValidationException(
          "artifact exceeds maximum size of " + maxArtifactBytes + " bytes");
    }
    String actualHash = sha256(content);
    if (!hashesEqual(actualHash, expectedHash)) {
      throw new ArtifactValidationException("artifact bytes do not match expected SHA-256");
    }
    String safeMediaType = requireText(mediaType, "mediaType");
    String safeSource = requireText(provenanceSource, "provenanceSource");
    String safeRetention = requireText(retentionPolicy, "retentionPolicy");
    String safePurpose = requireText(purpose, "purpose");
    Path shard = artifactRoot.resolve(expectedHash.substring(0, 2));
    Path destination = shard.resolve(expectedHash);
    synchronized (writeLock) {
      try {
        ensureDirectory(shard);
        if (Files.exists(destination, NOFOLLOW_LINKS)) {
          verifyRegularFile(destination);
          verifyHash(destination, expectedHash);
        } else {
          long used = storedBytes();
          if (used > quotaBytes - content.length) {
            throw new ArtifactValidationException(
                "artifact quota of " + quotaBytes + " bytes would be exceeded");
          }
          writeAtomically(destination, content, false);
        }
      } catch (IOException exception) {
        throw new ArtifactValidationException("artifact write failed", exception);
      }
    }
    String storagePath =
        root.relativize(destination).toString().replace('\\', '/');
    metadataSink.register(
        new ArtifactMetadata(
            runId,
            expectedHash,
            content.length,
            safeMediaType,
            storagePath,
            safeSource,
            safeRetention,
            safePurpose));
    return "artifact://sha256/" + expectedHash;
  }

  public byte[] read(String reference) {
    Path path = resolve(reference);
    String expectedHash =
        Objects.requireNonNull(path.getFileName(), "artifact file name").toString();
    try {
      byte[] content = Files.readAllBytes(path);
      if (!hashesEqual(sha256(content), expectedHash)) {
        throw new ArtifactValidationException("stored artifact hash verification failed");
      }
      return content;
    } catch (IOException exception) {
      throw new ArtifactValidationException("artifact read failed", exception);
    }
  }

  public Path resolve(String reference) {
    Objects.requireNonNull(reference, "reference");
    String prefix = "artifact://sha256/";
    if (!reference.startsWith(prefix)) {
      throw new ArtifactValidationException("not a SHA-256 artifact reference");
    }
    String hash = reference.substring(prefix.length());
    requireHash(hash);
    Path shard = artifactRoot.resolve(hash.substring(0, 2));
    Path candidate = shard.resolve(hash).normalize();
    if (!candidate.startsWith(artifactRoot)) {
      throw new ArtifactValidationException("artifact path escapes the storage root");
    }
    try {
      verifyDirectory(artifactRoot);
      verifyDirectory(shard);
      verifyRegularFile(candidate);
      return candidate;
    } catch (IOException exception) {
      throw new ArtifactValidationException("unsafe artifact path", exception);
    }
  }

  public String writeNamed(
      String namespace, String name, byte[] content) {
    String safeNamespace = requireSafeSegment(namespace, "namespace");
    String safeName = requireSafeSegment(name, "name");
    Objects.requireNonNull(content, "content");
    if (content.length > maxArtifactBytes) {
      throw new ArtifactValidationException(
          "named artifact exceeds maximum size of " + maxArtifactBytes + " bytes");
    }
    Path directory = namedRunRoot.resolve(safeNamespace);
    Path destination = directory.resolve(safeName).normalize();
    if (!destination.startsWith(namedRunRoot)) {
      throw new ArtifactValidationException("named artifact path escapes the run root");
    }
    synchronized (writeLock) {
      try {
        ensureDirectory(directory);
        writeAtomically(destination, content, true);
      } catch (IOException exception) {
        throw new ArtifactValidationException("named artifact write failed", exception);
      }
    }
    return "artifact://named/" + safeNamespace + "/" + safeName;
  }

  public byte[] readNamed(String namespace, String name) {
    String safeNamespace = requireSafeSegment(namespace, "namespace");
    String safeName = requireSafeSegment(name, "name");
    Path directory = namedRunRoot.resolve(safeNamespace);
    Path candidate = directory.resolve(safeName).normalize();
    if (!candidate.startsWith(namedRunRoot)) {
      throw new ArtifactValidationException("named artifact path escapes the run root");
    }
    try {
      verifyDirectory(directory);
      verifyRegularFile(candidate);
      return Files.readAllBytes(candidate);
    } catch (IOException exception) {
      throw new ArtifactValidationException("named artifact read failed", exception);
    }
  }

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "The destination is derived from validated hashes or safe path segments, "
              + "normalized beneath the configured root, and every parent is checked "
              + "without following links before this private method is invoked.")
  private void writeAtomically(Path destination, byte[] content, boolean replace)
      throws IOException {
    Path parent =
        Objects.requireNonNull(destination.getParent(), "artifact parent directory");
    Path fileName =
        Objects.requireNonNull(destination.getFileName(), "artifact file name");
    ensureDirectory(parent);
    Path temporary =
        Files.createTempFile(parent, "." + fileName + ".", ".tmp");
    try {
      try (FileChannel channel = FileChannel.open(temporary, WRITE)) {
        ByteBuffer buffer = ByteBuffer.wrap(content);
        while (buffer.hasRemaining()) {
          channel.write(buffer);
        }
        channel.force(true);
      }
      moveWithRetry(temporary, destination, replace);
      forceDirectory(parent);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private void moveWithRetry(Path source, Path destination, boolean replace)
      throws IOException {
    for (int attempt = 0; attempt < MOVE_ATTEMPTS; attempt++) {
      try {
        mover.move(source, destination, replace);
        return;
      } catch (AtomicMoveNotSupportedException exception) {
        throw exception;
      } catch (FileSystemException exception) {
        if (attempt == MOVE_ATTEMPTS - 1) {
          throw exception;
        }
        try {
          long delayMillis = Math.min(20L << attempt, 500L);
          sleeper.sleep(Duration.ofMillis(delayMillis));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted while retrying atomic move", interrupted);
        }
      }
    }
    throw new IOException("atomic move retry loop exhausted");
  }

  private long storedBytes() throws IOException {
    verifyDirectory(artifactRoot);
    try (Stream<Path> paths = Files.walk(artifactRoot)) {
      return paths
          .filter(path -> Files.isRegularFile(path, NOFOLLOW_LINKS))
          .mapToLong(ArtifactStore::fileSize)
          .sum();
    }
  }

  private void ensureDirectory(Path directory) throws IOException {
    if (!directory.startsWith(root)) {
      throw new ArtifactValidationException("directory escapes artifact root");
    }
    Files.createDirectories(directory);
    Path current = root;
    verifyDirectory(current);
    Path relative = root.relativize(directory);
    for (Path component : relative) {
      current = current.resolve(component);
      verifyDirectory(current);
    }
  }

  private static void verifyDirectory(Path directory) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(directory, BasicFileAttributes.class, NOFOLLOW_LINKS);
    if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
      throw new ArtifactValidationException(
          "artifact directory is a symlink, reparse point, or non-directory");
    }
  }

  private static void verifyRegularFile(Path path) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()
        || attributes.isSymbolicLink()
        || attributes.isOther()) {
      throw new ArtifactValidationException(
          "artifact target is a symlink, reparse point, or non-file");
    }
  }

  private static void verifyHash(Path path, String expectedHash) throws IOException {
    if (!hashesEqual(sha256(Files.readAllBytes(path)), expectedHash)) {
      throw new ArtifactValidationException(
          "existing content-addressed artifact has invalid bytes");
    }
  }

  private static void forceDirectory(Path directory) throws IOException {
    try (FileChannel channel = FileChannel.open(directory, READ)) {
      channel.force(true);
    } catch (AccessDeniedException | UnsupportedOperationException ignored) {
      // Windows cannot open directories as channels; the file itself was forced.
    }
  }

  private static void atomicMove(Path source, Path destination, boolean replace)
      throws IOException {
    StandardCopyOption[] options =
        replace
            ? new StandardCopyOption[] {ATOMIC_MOVE, REPLACE_EXISTING}
            : new StandardCopyOption[] {ATOMIC_MOVE};
    Files.move(source, destination, options);
  }

  private static long fileSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException exception) {
      throw new ArtifactValidationException("failed to measure artifact quota", exception);
    }
  }

  private static String requireSafeSegment(String value, String label) {
    String text = requireText(value, label);
    if (!SAFE_SEGMENT.matcher(text).matches()
        || text.contains("..")
        || text.indexOf('/') >= 0
        || text.indexOf('\\') >= 0
        || Path.of(text).isAbsolute()) {
      throw new ArtifactValidationException(label + " is not a safe relative name");
    }
    return text;
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    String text = value.strip();
    if (text.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return text;
  }

  private static void requireHash(String value) {
    if (value == null || !HASH.matcher(value).matches()) {
      throw new ArtifactValidationException(
          "artifact hash must be 64 lowercase hexadecimal characters");
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the JDK", exception);
    }
  }

  private static boolean hashesEqual(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(US_ASCII),
        right.getBytes(US_ASCII));
  }

  @FunctionalInterface
  interface AtomicMover {
    void move(Path source, Path destination, boolean replace) throws IOException;
  }

  @FunctionalInterface
  interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }
}
