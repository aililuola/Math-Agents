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
  private final FailureInjector failureInjector;

  public enum FailurePoint {
    AFTER_COMMIT_ENVELOPE,
    AFTER_STATE_PROJECTION,
    AFTER_TRANSITION_PROJECTION
  }

  @FunctionalInterface
  public interface FailureInjector {
    void after(FailurePoint point);
  }

  public FileRunStateStore(Path runRoot) {
    this(
        runRoot,
        JsonMapper.builder()
            .findAndAddModules()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build());
  }

  public FileRunStateStore(Path runRoot, ObjectMapper mapper) {
    this(runRoot, mapper, ignored -> {});
  }

  public FileRunStateStore(
      Path runRoot, ObjectMapper mapper, FailureInjector failureInjector) {
    this.runRoot = Objects.requireNonNull(runRoot, "runRoot").toAbsolutePath().normalize();
    this.mapper = Objects.requireNonNull(mapper, "mapper");
    this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
  }

  @Override
  public Optional<RunStateSnapshot> load(String runId) {
    try {
      Optional<RunStateCommitEnvelope> envelope = loadEnvelope(runId);
      if (envelope.isPresent()) {
        repairLegacyProjections(runId, envelope.orElseThrow());
        return Optional.of(envelope.orElseThrow().state());
      }
      return loadLegacyState(runId);
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
        Optional<RunStateCommitEnvelope> currentEnvelope = loadEnvelope(safeRunId);
        Optional<RunStateSnapshot> current =
            currentEnvelope.isPresent()
                ? Optional.of(currentEnvelope.orElseThrow().state())
                : loadLegacyState(safeRunId);
        long actualVersion = current.map(value -> value.authority().version()).orElse(-1L);
        if (actualVersion != expectedVersion) {
          throw new IllegalStateException(
              "run state optimistic version mismatch: expected "
                  + expectedVersion
                  + " but was "
                  + actualVersion);
        }
        if (current.isPresent()) {
          RunStateSnapshot prior = current.orElseThrow();
          if (RunStateHashes.equalHash(
                  prior.authority().authorityHash(), next.authority().authorityHash())
              && !RunStateHashes.equalHash(prior.stateHash(), next.stateHash())) {
            throw new IllegalStateException(
                "projection-only update requires projection compare-and-set");
          }
          if (next.authority().version() <= prior.authority().version()
              && !RunStateHashes.equalHash(prior.stateHash(), next.stateHash())) {
            throw new IllegalStateException("run state version must increase");
          }
        }
        commitEnvelope(safeRunId, currentEnvelope, current.orElse(null), next);
        updateCheckpointAnchor(structured, next);
      }
      return next;
    } catch (IOException exception) {
      throw new IllegalStateException("run state could not be committed", exception);
    }
  }

  @Override
  public RunStateSnapshot compareAndSetProjection(
      String runId,
      String expectedStateHash,
      long expectedProjectionVersion,
      RunStateSnapshot next,
      String ownerId,
      long fencingToken) {
    String safeRunId = safeRunId(runId);
    Objects.requireNonNull(next, "next");
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
        Optional<RunStateCommitEnvelope> currentEnvelope = loadEnvelope(safeRunId);
        RunStateSnapshot current =
            (currentEnvelope.isPresent()
                    ? Optional.of(currentEnvelope.orElseThrow().state())
                    : loadLegacyState(safeRunId))
                .orElseThrow(() -> new IllegalStateException("run state does not exist"));
        if (!RunStateHashes.equalHash(expectedStateHash, current.stateHash())
            || current.projection().projectionVersion() != expectedProjectionVersion) {
          throw new IllegalStateException("stale projection compare-and-set");
        }
        if (!RunStateHashes.equalHash(
                current.authority().authorityHash(), next.authority().authorityHash())
            || current.authority().version() != next.authority().version()
            || next.projection().projectionVersion() != expectedProjectionVersion + 1L) {
          throw new IllegalStateException("projection update cannot modify authority");
        }
        commitEnvelope(safeRunId, currentEnvelope, current, next);
      }
      return next;
    } catch (IOException exception) {
      throw new IllegalStateException("run state projection could not be committed", exception);
    }
  }

  @Override
  public List<RunStateTransition> transitions(String runId) {
    try {
      Optional<RunStateCommitEnvelope> envelope = loadEnvelope(runId);
      if (envelope.isPresent()) {
        return envelope.orElseThrow().transitions().transitions();
      }
      return legacyTransitions(runId);
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("run state transitions could not be read", exception);
    }
  }

  private void commitEnvelope(
      String runId,
      Optional<RunStateCommitEnvelope> currentEnvelope,
      RunStateSnapshot previous,
      RunStateSnapshot next)
      throws IOException {
    List<RunStateTransition> items =
        new ArrayList<>(
            currentEnvelope
                .map(value -> value.transitions().transitions())
                .orElseGet(() -> legacyTransitions(runId)));
    RunStateTransitionTrigger trigger =
        previous == null
            ? RunStateTransitionTrigger.RUN_CREATED
            : next.reconciliationStatus() == RunReconciliationStatus.REPAIRED
                ? RunStateTransitionTrigger.RECONCILIATION_REPAIRED
                : RunStateTransitionTrigger.USAGE_RECONCILED;
    RunStateTransitionLedger ledger = new RunStateTransitionLedger();
    ledger.restore(new RunStateTransitionSnapshot(items, null));
    if (previous == null || !RunStateHashes.equalHash(previous.stateHash(), next.stateHash())) {
      ledger.append(
          runId,
          previous == null ? "" : previous.stateHash(),
          next.stateHash(),
          trigger,
          java.util.Map.of("authority_hash", next.authority().authorityHash()),
          next.updatedAt());
    }
    RunStateCommitEnvelope committed = RunStateCommitEnvelope.create(next, ledger.snapshot());
    atomicWrite(
        envelopePath(runId), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(committed));
    failureInjector.after(FailurePoint.AFTER_COMMIT_ENVELOPE);
    atomicWrite(statePath(runId), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(next));
    failureInjector.after(FailurePoint.AFTER_STATE_PROJECTION);
    atomicWrite(
        transitionsPath(runId),
        mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(ledger.snapshot().transitions()));
    failureInjector.after(FailurePoint.AFTER_TRANSITION_PROJECTION);
  }

  private Optional<RunStateCommitEnvelope> loadEnvelope(String runId) throws IOException {
    Path path = envelopePath(runId);
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    return Optional.of(
        mapper.readValue(Files.readAllBytes(path), RunStateCommitEnvelope.class));
  }

  private Optional<RunStateSnapshot> loadLegacyState(String runId) throws IOException {
    Path path = statePath(runId);
    if (!Files.isRegularFile(path)) {
      return Optional.empty();
    }
    return Optional.of(mapper.readValue(Files.readAllBytes(path), RunStateSnapshot.class));
  }

  private List<RunStateTransition> legacyTransitions(String runId) {
    Path path = transitionsPath(runId);
    if (!Files.isRegularFile(path)) {
      return List.of();
    }
    try {
      return List.copyOf(mapper.readValue(Files.readAllBytes(path), TRANSITIONS));
    } catch (IOException exception) {
      throw new IllegalStateException("legacy run state transitions could not be read", exception);
    }
  }

  private void repairLegacyProjections(String runId, RunStateCommitEnvelope envelope)
      throws IOException {
    byte[] stateBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope.state());
    byte[] transitionBytes =
        mapper
            .writerWithDefaultPrettyPrinter()
            .writeValueAsBytes(envelope.transitions().transitions());
    if (!Files.isRegularFile(statePath(runId))
        || !RunStateHashes.equalHash(
            mapper.readValue(Files.readAllBytes(statePath(runId)), RunStateSnapshot.class).stateHash(),
            envelope.state().stateHash())) {
      atomicWrite(statePath(runId), stateBytes);
    }
    if (!Files.isRegularFile(transitionsPath(runId))
        || !mapper
            .readValue(Files.readAllBytes(transitionsPath(runId)), TRANSITIONS)
            .equals(envelope.transitions().transitions())) {
      atomicWrite(transitionsPath(runId), transitionBytes);
    }
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

  private Path envelopePath(String runId) {
    return runRoot
        .resolve(safeRunId(runId))
        .resolve("structured")
        .resolve("run_state_commit.json");
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
