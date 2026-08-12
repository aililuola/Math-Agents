package io.github.aililuola.mathproofmesh.persistence;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreParityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void repeatedPromptsAreImmutableContentAddressedArtifacts() {
    List<ArtifactMetadata> metadata = new ArrayList<>();
    ArtifactStore store =
        new ArtifactStore(
            temporaryDirectory,
            "run-a",
            1024,
            4096,
            metadata::add);

    String first =
        store.savePrompt("solve", "agent-1", "system one", "user one");
    String second =
        store.savePrompt("solve", "agent-1", "system two", "user two");
    String duplicate =
        store.savePrompt("solve", "agent-1", "system one", "user one");

    assertThat(first).isNotEqualTo(second);
    assertThat(duplicate).isEqualTo(first);
    assertThat(new String(store.read(first), StandardCharsets.UTF_8))
        .isEqualTo("# SYSTEM\nsystem one\n\n# USER\nuser one\n");
    assertThat(new String(store.read(second), StandardCharsets.UTF_8))
        .isEqualTo("# SYSTEM\nsystem two\n\n# USER\nuser two\n");
    assertThat(metadata).hasSize(3);
    assertThat(metadata.getFirst().storagePath())
        .isEqualTo(
            "artifacts/sha256/"
                + first.substring("artifact://sha256/".length(), "artifact://sha256/".length() + 2)
                + "/"
                + first.substring("artifact://sha256/".length()));
  }

  @Test
  void atomicReplacementRetriesTransientWindowsStyleFailures() {
    AtomicInteger attempts = new AtomicInteger();
    ArtifactStore.AtomicMover mover =
        (source, destination, replace) -> {
          if (attempts.incrementAndGet() < 3) {
            throw new AccessDeniedException(destination.toString());
          }
          Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING);
        };
    ArtifactStore store =
        new ArtifactStore(
            temporaryDirectory,
            "run-a",
            1024,
            4096,
            ArtifactMetadataSink.noOp(),
            mover,
            ignored -> {});

    store.writeNamed("checkpoints", "latest.json", bytes("generation-two"));

    assertThat(attempts).hasValue(3);
    assertThat(store.readNamed("checkpoints", "latest.json"))
        .isEqualTo(bytes("generation-two"));
  }

  @Test
  void failedAtomicReplacementPreservesPriorGenerationAndCleansTemporaryFiles()
      throws IOException {
    ArtifactStore initial = new ArtifactStore(temporaryDirectory, "run-a");
    initial.writeNamed("checkpoints", "latest.json", bytes("generation-one"));
    ArtifactStore failing =
        new ArtifactStore(
            temporaryDirectory,
            "run-a",
            1024,
            4096,
            ArtifactMetadataSink.noOp(),
            (source, destination, replace) -> {
              throw new AccessDeniedException(destination.toString());
            },
            ignored -> {});

    assertThatThrownBy(
            () ->
                failing.writeNamed(
                    "checkpoints", "latest.json", bytes("generation-two")))
        .isInstanceOf(ArtifactValidationException.class)
        .hasMessageContaining("named artifact write failed");
    assertThat(initial.readNamed("checkpoints", "latest.json"))
        .isEqualTo(bytes("generation-one"));
    try (var paths = Files.walk(temporaryDirectory)) {
      assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".tmp")))
          .isEmpty();
    }
  }

  @Test
  void concurrentCheckpointWritesRemainWholeAndSerialized() throws Exception {
    ArtifactStore store = new ArtifactStore(temporaryDirectory, "run-a");
    Set<String> generations =
        Set.of(
            "generation-00",
            "generation-01",
            "generation-02",
            "generation-03",
            "generation-04",
            "generation-05",
            "generation-06",
            "generation-07");
    List<Callable<Void>> writes =
        generations.stream()
            .<Callable<Void>>map(
                generation ->
                    () -> {
                      store.writeNamed(
                          "working-checkpoints",
                          "latest.json",
                          bytes(generation));
                      return null;
                    })
            .toList();

    try (var executor = Executors.newFixedThreadPool(8)) {
      for (var result : executor.invokeAll(writes)) {
        result.get();
      }
    }

    assertThat(
            new String(
                store.readNamed("working-checkpoints", "latest.json"),
                StandardCharsets.UTF_8))
        .isIn(generations);
    try (var paths = Files.walk(temporaryDirectory)) {
      assertThat(paths.filter(path -> path.getFileName().toString().endsWith(".tmp")))
          .isEmpty();
    }
  }

  @Test
  void rejectsTraversalAbsoluteMalformedAndUppercaseReferences() {
    ArtifactStore store = new ArtifactStore(temporaryDirectory, "run-a");

    assertThatThrownBy(() -> store.resolve("C:\\outside\\artifact"))
        .isInstanceOf(ArtifactValidationException.class);
    assertThatThrownBy(
            () ->
                store.resolve(
                    "artifact://sha256/../../"
                        + "0".repeat(64)))
        .isInstanceOf(ArtifactValidationException.class);
    assertThatThrownBy(
            () ->
                store.resolve(
                    "artifact://sha256/"
                        + "A".repeat(64)))
        .isInstanceOf(ArtifactValidationException.class);
    assertThatThrownBy(
            () -> store.writeNamed("../outside", "result", bytes("x")))
        .isInstanceOf(ArtifactValidationException.class);
    assertThatThrownBy(
            () -> store.writeNamed("checkpoints", "..\\outside", bytes("x")))
        .isInstanceOf(ArtifactValidationException.class);
  }

  @Test
  void rejectsHashMismatchMaximumSizeAndQuotaOverflow() {
    ArtifactStore store =
        new ArtifactStore(
            temporaryDirectory,
            "run-a",
            4,
            5,
            ArtifactMetadataSink.noOp());

    assertThatThrownBy(
            () ->
                store.writeExpected(
                    "0".repeat(64),
                    bytes("no"),
                    "text/plain",
                    "test",
                    "short-term",
                    "test"))
        .isInstanceOf(ArtifactValidationException.class)
        .hasMessageContaining("do not match");
    assertThatThrownBy(
            () ->
                store.write(
                    bytes("12345"),
                    "text/plain",
                    "test",
                    "short-term",
                    "test"))
        .isInstanceOf(ArtifactValidationException.class)
        .hasMessageContaining("maximum size");

    store.write(
        bytes("1234"),
        "text/plain",
        "test",
        "short-term",
        "test");
    assertThatThrownBy(
            () ->
                store.write(
                    bytes("56"),
                    "text/plain",
                    "test",
                    "short-term",
                    "test"))
        .isInstanceOf(ArtifactValidationException.class)
        .hasMessageContaining("quota");
  }

  @Test
  void rejectsSymlinkOrReparsePointInContentAddressedPath() throws IOException {
    ArtifactStore store = new ArtifactStore(temporaryDirectory, "run-a");
    byte[] content = bytes("outside-content");
    String hash = sha256(content);
    Path outside = temporaryDirectory.resolve("outside.bin");
    Files.write(outside, content);
    Path shard =
        temporaryDirectory.resolve("artifacts").resolve("sha256").resolve(hash.substring(0, 2));
    Files.createDirectories(shard);
    Path destination = shard.resolve(hash);
    Files.createSymbolicLink(destination, Path.of("..", "..", "..", "outside.bin"));

    assertThatThrownBy(
            () ->
                store.writeExpected(
                    hash,
                    content,
                    "application/octet-stream",
                    "test",
                    "short-term",
                    "test"))
        .isInstanceOf(ArtifactValidationException.class)
        .hasMessageContaining("symlink, reparse point, or non-file");
    assertThatThrownBy(() -> store.read("artifact://sha256/" + hash))
        .isInstanceOf(ArtifactValidationException.class)
        .hasMessageContaining("symlink, reparse point, or non-file");
    Files.delete(destination);
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
