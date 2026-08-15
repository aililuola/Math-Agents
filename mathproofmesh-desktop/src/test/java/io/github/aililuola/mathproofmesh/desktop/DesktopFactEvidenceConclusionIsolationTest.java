package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFactEvidenceConclusionIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void aFactWithAnotherConclusionCannotMintTrustedClaimEvidence() throws Exception {
    String claimId = "claim-conclusion-isolation";
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "conclusion-isolation")) {
      harness.freezeAndCreateRoute();
      harness.installSingleClaimRound(0, claimId, "P");
      harness.integrateInstalledRound();

      var frozen = harness.frozenClaim(claimId);
      var fact = harness.typedMemory().find(claimId).orElseThrow();
      MessageEnvelope wrongConclusion =
          new MessageEnvelope(
              fact.artifactRefs(),
              frozen.assumptions(),
              "Q",
              "",
              fact.createdAt(),
              fact.dependencies(),
              fact.dependencyRefs(),
              fact.evidenceType(),
              fact.memoryTier(),
              "wrong-conclusion-fact",
              fact.messageType(),
              fact.normalizationConfidence(),
              fact.normalizedStatement(),
              fact.problemHash(),
              frozen.quantifiers(),
              fact.rawSourceRef(),
              fact.roundCreated(),
              fact.schemaVersion(),
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
              frozen.polarity());

      int accepts = harness.factMatchesFrozenClaim(wrongConclusion, frozen) ? 1 : 0;
      assertThat(accepts).isZero();
      System.out.println("CONCLUSION_MISMATCH_FACT_ACCEPTS=" + accepts);
    }
  }
}
