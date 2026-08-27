package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ResearchTaskPostgresRepositoryTest {
  @Test
  void taskIdentityAndDurableResultBindingAreDeclared() throws Exception {
    String sql;
    try (var stream =
        getClass().getResourceAsStream("/db/migration/V6__research_concurrency_epochs.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("PRIMARY KEY (run_id, task_id)")
        .contains("provider_request_id text NOT NULL")
        .contains("result_ref text NOT NULL")
        .contains("result_hash text NOT NULL")
        .contains("REFERENCES research_epoch(run_id, epoch_id)");
  }
}
