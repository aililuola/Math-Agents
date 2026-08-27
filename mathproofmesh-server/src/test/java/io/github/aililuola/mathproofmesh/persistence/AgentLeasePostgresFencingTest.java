package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class AgentLeasePostgresFencingTest {
  @Test
  void leaseRowsAreTaskBoundVersionedAndFenced() throws Exception {
    String sql;
    try (var stream =
        getClass().getResourceAsStream("/db/migration/V6__research_concurrency_epochs.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("PRIMARY KEY (run_id, lease_id)")
        .contains("FOREIGN KEY (run_id, task_id)")
        .contains("version bigint NOT NULL")
        .contains("fencing_token bigint NOT NULL");
  }
}
