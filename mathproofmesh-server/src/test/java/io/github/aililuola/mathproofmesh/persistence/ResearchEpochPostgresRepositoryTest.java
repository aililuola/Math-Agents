package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ResearchEpochPostgresRepositoryTest {
  @Test
  void epochIdentityIsRunScopedAndFenced() throws Exception {
    String sql = migration();
    assertThat(sql)
        .contains("PRIMARY KEY (run_id, epoch_id)")
        .contains("snapshot_hash sha256_hex NOT NULL")
        .contains("merge_plan_hash text NOT NULL")
        .contains("fencing_token bigint NOT NULL");
  }

  private String migration() throws Exception {
    try (var stream =
        getClass().getResourceAsStream("/db/migration/V6__research_concurrency_epochs.sql")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
