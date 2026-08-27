package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ProofAttemptSemanticContextMigrationTest {
  @Test
  void legacyAttemptWithoutContextManifestRestoresAsExplicitLegacyVersion() {
    ProofAttempt restored =
        ContractObjectMapper.read(
            """
            {
              "problem_hash":"%s",
              "strategy_id":"legacy-strategy",
              "agent_id":"legacy-agent",
              "round_index":3,
              "status":"partial",
              "proposed_lemmas":[]
            }
            """
                .formatted("a".repeat(64)),
            ProofAttempt.class);

    assertEquals(0, restored.claimSemanticContextManifestVersion());
    assertTrue(restored.claimSemanticContextBindings().isEmpty());

    ProofAttempt roundTripped =
        ContractObjectMapper.read(ContractObjectMapper.write(restored), ProofAttempt.class);
    assertEquals(0, roundTripped.claimSemanticContextManifestVersion());
    assertTrue(roundTripped.claimSemanticContextBindings().isEmpty());
  }
}
