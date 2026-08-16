package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Deterministic in-memory content store used by core and isolated tests. */
public final class InMemoryComputationArtifactStore implements ComputationArtifactStore {
  private final ConcurrentMap<String, byte[]> values = new ConcurrentHashMap<>();
  private final ConcurrentMap<Key, ComputationArtifactRecord> records =
      new ConcurrentHashMap<>();

  @Override
  public ComputationArtifactRecord write(
      String executionId, ComputationArtifactKind kind, Object value) {
    com.fasterxml.jackson.databind.node.ObjectNode envelope =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    envelope.put("artifact_kind", kind.name());
    envelope.set("payload", ContractObjectMapper.toTree(value));
    String canonical = CanonicalJson.canonicalize(envelope);
    byte[] bytes = canonical.getBytes(StandardCharsets.UTF_8);
    String hash = CanonicalJson.stableHash(canonical);
    String reference = "artifact://sha256/" + hash;
    ComputationArtifactRecord record =
        new ComputationArtifactRecord(
            executionId,
            reference,
            hash,
            kind,
            "application/json; charset=utf-8",
            bytes.length);
    values.putIfAbsent(reference, bytes.clone());
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
    return record;
  }

  @Override
  public Optional<ComputationArtifactRecord> find(
      String executionId, ComputationArtifactKind kind) {
    return Optional.ofNullable(records.get(new Key(executionId, kind)));
  }

  @Override
  public <T> Optional<T> read(String reference, Class<T> type) {
    byte[] bytes = values.get(reference);
    if (bytes == null) {
      return Optional.empty();
    }
    com.fasterxml.jackson.databind.JsonNode envelope =
        ContractObjectMapper.parseTree(new String(bytes, StandardCharsets.UTF_8));
    return Optional.of(ContractObjectMapper.read(envelope.path("payload").toString(), type));
  }

  @Override
  public ComputationArtifactSnapshot snapshot() {
    return new ComputationArtifactSnapshot(new ArrayList<>(records.values()), null);
  }

  @Override
  public void restore(ComputationArtifactSnapshot snapshot) {
    if (snapshot == null) {
      return;
    }
    Map<Key, ComputationArtifactRecord> restored =
        snapshot.records().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    value -> new Key(value.executionId(), value.kind()), value -> value));
    records.keySet().retainAll(restored.keySet());
    restored.forEach(records::putIfAbsent);
  }

  public int storedValueCount() {
    return values.size();
  }

  private record Key(String executionId, ComputationArtifactKind kind) {
    private Key {
      if (executionId == null || executionId.isBlank() || kind == null) {
        throw new IllegalArgumentException("artifact execution key is required");
      }
    }
  }
}
