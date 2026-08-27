package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ResearchConcurrencyContractsTest {
  @Test
  void flywayV6DefinesAllDurableConcurrencyBoundariesWithoutCredentials() throws Exception {
    String sql;
    try (var stream =
        getClass().getResourceAsStream("/db/migration/V6__research_concurrency_epochs.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("CREATE TABLE research_epoch")
        .contains("CREATE TABLE research_work_item")
        .contains("CREATE TABLE agent_lease")
        .contains("CREATE TABLE concurrency_telemetry_event")
        .contains("fencing_token bigint NOT NULL")
        .doesNotContain("api_key")
        .doesNotContain("secret_value");
  }
}
