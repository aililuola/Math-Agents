package io.github.aililuola.mathproofmesh.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.provider.CircuitStateStore;
import io.github.aililuola.mathproofmesh.provider.ProviderCircuitSnapshot;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcCircuitStateStore implements CircuitStateStore {
  private final JdbcClient jdbc;

  public JdbcCircuitStateStore(JdbcClient jdbc) {
    this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
  }

  @Override
  public Optional<ProviderCircuitSnapshot> load(String providerScope) {
    return jdbc.sql(
            """
            SELECT provider_scope, failures_payload::text AS failures_payload,
                   open_until, version
            FROM provider_circuit_state
            WHERE provider_scope = :providerScope
            """)
        .param("providerScope", providerScope)
        .query(JdbcCircuitStateStore::map)
        .optional();
  }

  @Override
  public void save(ProviderCircuitSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    int updated =
        jdbc.sql(
                """
                INSERT INTO provider_circuit_state (
                  provider_scope, failures_payload, open_until, version
                ) VALUES (
                  :providerScope, CAST(:failuresPayload AS jsonb),
                  :openUntil, :version
                )
                ON CONFLICT (provider_scope) DO UPDATE
                SET failures_payload = EXCLUDED.failures_payload,
                    open_until = EXCLUDED.open_until,
                    version = EXCLUDED.version,
                    updated_at = clock_timestamp()
                WHERE provider_circuit_state.version <= EXCLUDED.version
                """)
            .param("providerScope", snapshot.providerScope())
            .param(
                "failuresPayload",
                ContractObjectMapper.write(
                    snapshot.failures().stream()
                        .map(
                            failure ->
                                java.util.Map.of(
                                    "occurredAt",
                                    failure.occurredAt().toString(),
                                    "agentId",
                                    failure.agentId(),
                                    "category",
                                    failure.category()))
                        .toList()))
            .param(
                "openUntil",
                snapshot.openUntil() == null
                    ? null
                    : Timestamp.from(snapshot.openUntil()))
            .param("version", snapshot.version())
            .update();
    if (updated != 1) {
      throw new OptimisticLockException(
          snapshot.providerScope(), snapshot.version());
    }
  }

  @Override
  public void delete(String providerScope) {
    jdbc.sql(
            """
            DELETE FROM provider_circuit_state
            WHERE provider_scope = :providerScope
            """)
        .param("providerScope", providerScope)
        .update();
  }

  private static ProviderCircuitSnapshot map(ResultSet result, int row)
      throws SQLException {
    JsonNode payload =
        ContractObjectMapper.parseTree(result.getString("failures_payload"));
    List<ProviderCircuitSnapshot.Failure> failures = new ArrayList<>();
    for (JsonNode item : payload) {
      failures.add(
          new ProviderCircuitSnapshot.Failure(
              Instant.parse(item.path("occurredAt").asText()),
              item.path("agentId").asText(),
              item.path("category").asText()));
    }
    Timestamp openUntil = result.getTimestamp("open_until");
    return new ProviderCircuitSnapshot(
        result.getString("provider_scope"),
        failures,
        openUntil == null ? null : openUntil.toInstant(),
        result.getLong("version"));
  }
}
