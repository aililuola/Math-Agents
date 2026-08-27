package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.computation.ComputationArtifactKind;
import io.github.aililuola.mathproofmesh.computation.ComputationArtifactRecord;
import io.github.aililuola.mathproofmesh.computation.ComputationArtifactSnapshot;
import io.github.aililuola.mathproofmesh.computation.ComputationArtifactStore;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.persistence.ArtifactStore;
import io.github.aililuola.mathproofmesh.persistence.ArtifactValidationException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Durable computation evidence adapter over the existing content-addressed artifact store. */
final class ArtifactStoreComputationArtifactStore implements ComputationArtifactStore {
  private static final String INDEX_NAMESPACE = "computation-artifacts";

  private final ArtifactStore delegate;
  private final ConcurrentMap<Key, ComputationArtifactRecord> records =
      new ConcurrentHashMap<>();

  ArtifactStoreComputationArtifactStore(ArtifactStore delegate) {
    this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
  }

  @Override
  public ComputationArtifactRecord write(
      String executionId, ComputationArtifactKind kind, Object value) {
    com.fasterxml.jackson.databind.node.ObjectNode envelope =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    envelope.put("artifact_kind", kind.name());
    envelope.set("payload", ContractObjectMapper.toTree(value));
    String canonical = CanonicalJson.canonicalize(envelope);
    byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
    String reference =
        delegate.write(
            bytes,
            "application/json; charset=utf-8",
            "computation-execution:" + executionId,
            "campaign",
            "computation_" + kind.name().toLowerCase(java.util.Locale.ROOT));
    String hash = reference.substring("artifact://sha256/".length());
    ComputationArtifactRecord record =
        new ComputationArtifactRecord(
            executionId,
            reference,
            hash,
            kind,
            "application/json; charset=utf-8",
            bytes.length);
    Key key = new Key(executionId, kind);
    records.merge(
        key,
        record,
        (left, right) -> {
          if (!left.reference().equals(right.reference())) {
            throw new IllegalStateException("execution artifact is immutable");
          }
          return left;
        });
    delegate.writeNamed(
        INDEX_NAMESPACE,
        indexName(key),
        ContractObjectMapper.write(record).getBytes(StandardCharsets.UTF_8));
    return record;
  }

  @Override
  public Optional<ComputationArtifactRecord> find(
      String executionId, ComputationArtifactKind kind) {
    Key key = new Key(executionId, kind);
    ComputationArtifactRecord current = records.get(key);
    if (current != null) {
      return Optional.of(current);
    }
    try {
      ComputationArtifactRecord restored =
          ContractObjectMapper.read(
              new String(
                  delegate.readNamed(INDEX_NAMESPACE, indexName(key)),
                  StandardCharsets.UTF_8),
              ComputationArtifactRecord.class);
      ComputationArtifactRecord existing = records.putIfAbsent(key, restored);
      if (existing != null && !existing.reference().equals(restored.reference())) {
        throw new IllegalStateException("execution artifact index is immutable");
      }
      return Optional.of(existing == null ? restored : existing);
    } catch (ArtifactValidationException exception) {
      return Optional.empty();
    }
  }

  @Override
  public <T> Optional<T> read(String reference, Class<T> type) {
    try {
      String content = new String(delegate.read(reference), StandardCharsets.UTF_8);
      com.fasterxml.jackson.databind.JsonNode envelope = ContractObjectMapper.parseTree(content);
      return Optional.of(ContractObjectMapper.read(envelope.path("payload").toString(), type));
    } catch (ArtifactValidationException exception) {
      return Optional.empty();
    }
  }

  @Override
  public ComputationArtifactSnapshot snapshot() {
    return new ComputationArtifactSnapshot(records.values().stream().toList(), null);
  }

  @Override
  public void restore(ComputationArtifactSnapshot snapshot) {
    records.clear();
    ComputationArtifactSnapshot safe =
        snapshot == null ? ComputationArtifactSnapshot.empty() : snapshot;
    safe.records().forEach(record -> records.put(new Key(record.executionId(), record.kind()), record));
  }

  private static String indexName(Key key) {
    return CanonicalJson.stableHash(
            Map.of("execution_id", key.executionId(), "kind", key.kind().name()))
        + ".json";
  }

  private record Key(String executionId, ComputationArtifactKind kind) {
    private Key {
      if (executionId == null || executionId.isBlank() || kind == null) {
        throw new IllegalArgumentException("artifact execution key is required");
      }
    }
  }
}
