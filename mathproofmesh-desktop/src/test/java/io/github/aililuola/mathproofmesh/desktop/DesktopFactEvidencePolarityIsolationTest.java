package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFactEvidencePolarityIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void oppositePolarityFactCannotMintTrustedEvidence() throws Exception {
    String claimId = "claim-polarity-isolation";
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "polarity-isolation")) {
      harness.freezeAndCreateRoute();
      harness.installSingleClaimRound(0, claimId, "P");
      harness.integrateInstalledRound();
      var frozen = harness.frozenClaim(claimId);
      var fact = harness.typedMemory().find(claimId).orElseThrow();
      MessageEnvelope opposite =
          new MessageEnvelope(
              fact.artifactRefs(),
              frozen.assumptions(),
              frozen.conclusion(),
              "",
              fact.createdAt(),
              fact.dependencies(),
              fact.dependencyRefs(),
              fact.evidenceType(),
              fact.memoryTier(),
              "opposite-polarity-fact",
              fact.messageType(),
              fact.normalizationConfidence(),
              fact.normalizedStatement(),
              fact.problemHash(),
              frozen.quantifiers(),
              fact.rawSourceRef(),
              fact.roundCreated(),
              "2",
              frozen.scopeLimitations(),
              fact.sourceAgentId(),
              fact.sourceRole(),
              fact.sourceRouteId(),
              fact.statement(),
              List.of("*"),
              fact.ttlRounds(),
              frozen.variableBindings(),
              fact.verificationConfidence(),
              fact.verificationStatus(),
              frozen.claimStatementHash(),
              frozen.claimSemanticHash(),
              "negative");

      int accepts = harness.factMatchesFrozenClaim(opposite, frozen) ? 1 : 0;
      assertThat(accepts).isZero();
      System.out.println("POLARITY_MISMATCH_FACT_ACCEPTS=" + accepts);
    }
  }
}
