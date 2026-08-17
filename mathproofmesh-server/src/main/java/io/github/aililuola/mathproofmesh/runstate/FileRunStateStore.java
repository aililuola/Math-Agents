package io.github.aililuola.mathproofmesh.runstate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Atomic file authority for desktop and standalone-server run state. */
public final class FileRunStateStore implements RunStateStore {
  private static final TypeReference<List<RunStateTransition>> TRANSITIONS =
      new TypeReference<>() {};
  private final Path runRoot;
  private final ObjectMapper mapper;

  public FileRunStateStore(Path runRoot) {
    this(
        runRoot,
        JsonMapper.builder()
            .findAndAddModules()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build());
  }

  public FileRunStateStore(Path runRoot, ObjectMapper mapper) {
    this.runRoot = Objects.requireNonNull(runRoot, "runRoot").toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public Optional<RunStateSnapshot> load(String runId) {
    Path path = statePath(runId);
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    try {
      return Optional.of(mapper.readValue(Files.readAllBytes(path), RunStateSnapshot.class));
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("run state could not be read", exception);
    }
  }

  @Override
  public RunStateSnapshot compareAndSet(
      String runId,
      long expectedVersion,
      RunStateSnapshot next,
      String ownerId,
      long fencingToken) {
    String safeRunId = safeRunId(runId);
    Objects.requireNonNull(next, "next");
    if (!safeRunId.equals(next.authority().runId())) {
      throw new IllegalArgumentException("run state identity mismatch");
    }
    if (ownerId == null || ownerId.isBlank() || fencingToken < 0L) {
      throw new IllegalArgumentException("file state commit requires an owner and fencing token");
    }
    Path structured = runRoot.resolve(safeRunId).resolve("structured");
    Path lockPath = structured.resolve(".run-state.lock");
    try {
      Files.createDirectories(structured);
      try (FileChannel channel =
              FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock lock = channel.lock()) {
        if (!lock.isValid()) {
          throw new IllegalStateException("run state lock is not valid");
        }
        Optional<RunStateSnapshot> current = load(safeRunId);
        long actualVersion = current.map(value -> value.authority().version()).orElse(-1L);
        if (actualVersion != expectedVersion) {
          throw new IllegalStateException(
              "run state optimistic version mismatch: expected "
                  + expectedVersion
                  + " but was "
                  + actualVersion);
        }
        if (current.isPresent()
            && next.authority().version() < current.orElseThrow().authority().version()) {
          throw new IllegalStateException("run state version cannot regress");
        }
        atomicWrite(statePath(safeRunId), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(next));
        updateCheckpointAnchor(structured, next);
        if (current.isEmpty() || !current.orElseThrow().stateHash().equals(next.stateHash())) {
          appendTransition(safeRunId, current.orElse(null), next);
        }
      }
      return next;
    } catch (IOException exception) {
      throw new IllegalStateException("run state could not be committed", exception);
    }
  }

  @Override
  public List<RunStateTransition> transitions(String runId) {
    Path path = transitionsPath(runId);
    if (!Files.isRegularFile(path)) {
      return List.of();
    }
    try {
      return List.copyOf(mapper.readValue(Files.readAllBytes(path), TRANSITIONS));
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("run state transitions could not be read", exception);
    }
  }

  private void appendTransition(
      String runId, RunStateSnapshot previous, RunStateSnapshot next) throws IOException {
    List<RunStateTransition> items = new ArrayList<>(transitions(runId));
    RunStateTransitionTrigger trigger =
        previous == null
            ? RunStateTransitionTrigger.RUN_CREATED
            : next.reconciliationStatus() == RunReconciliationStatus.REPAIRED
                ? RunStateTransitionTrigger.RECONCILIATION_REPAIRED
                : RunStateTransitionTrigger.USAGE_RECONCILED;
    RunStateTransitionLedger ledger = new RunStateTransitionLedger();
    ledger.restore(new RunStateTransitionSnapshot(items, null));
    ledger.append(
        runId,
        previous == null ? "" : previous.stateHash(),
        next.stateHash(),
        trigger,
        java.util.Map.of("authority_hash", next.authority().authorityHash()),
        next.updatedAt());
    atomicWrite(
        transitionsPath(runId),
        mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(ledger.snapshot().transitions()));
  }

  private void updateCheckpointAnchor(Path structured, RunStateSnapshot state) {
    Path checkpoint = structured.resolve("desktop-solve-state.json");
    if (!Files.isRegularFile(checkpoint)) {
      return;
    }
    try {
      com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(Files.readAllBytes(checkpoint));
      if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) {
        return;
      }
      object.set(
          "runStateAnchor",
          mapper.valueToTree(
              new RunStateAnchor(
                  state.authority().authoritySequence(),
                  state.authority().authorityHash(),
                  state.authority().executionAttemptId())));
      atomicWrite(
          checkpoint, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(object));
    } catch (IOException | RuntimeException ignored) {
      // The authority file is already durable. A stale anchor is repaired on reconciliation.
    }
  }

  private Path statePath(String runId) {
    return runRoot.resolve(safeRunId(runId)).resolve("structured").resolve("run_state.json");
  }

  private Path transitionsPath(String runId) {
    return runRoot
        .resolve(safeRunId(runId))
        .resolve("structured")
        .resolve("run_state_transitions.json");
  }

  private String safeRunId(String runId) {
    String value = Objects.requireNonNull(runId, "runId").strip();
    if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || value.contains("..")) {
      throw new IllegalArgumentException("invalid run id");
    }
    Path candidate = runRoot.resolve(value).normalize();
    if (!candidate.startsWith(runRoot)) {
      throw new IllegalArgumentException("run path escapes root");
    }
    return value;
  }

  private static void atomicWrite(Path target, byte[] bytes) throws IOException {
    Path parent = Objects.requireNonNull(target.getParent(), "state parent");
    Files.createDirectories(parent);
    Path temporary = Files.createTempFile(parent, ".run-state-", ".tmp");
    try {
      try (FileChannel output =
          FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        output.write(ByteBuffer.wrap(bytes));
        output.force(true);
      }
      try {
        Files.move(
            temporary,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        Path journal = target.resolveSibling(target.getFileName() + ".journal");
        Files.move(temporary, journal, StandardCopyOption.REPLACE_EXISTING);
        Files.move(journal, target, StandardCopyOption.REPLACE_EXISTING);
      }
      try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
        directory.force(true);
      } catch (IOException | UnsupportedOperationException ignored) {
        // Windows does not expose directory fsync; the file itself is already forced.
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
