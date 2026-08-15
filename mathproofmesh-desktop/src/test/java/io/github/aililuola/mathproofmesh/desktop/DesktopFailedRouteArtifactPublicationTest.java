package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.memory.VerifiedCounterexampleAuthority;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactKind;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimCourtRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot;
import java.util.List;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopFailedRouteArtifactPublicationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void verifiedLocalClaimIsEligibleEvenWhenItsRouteTheoremFailed() throws Exception {
    String claimId = "failed-route-local-claim";
    String statement = "Every finite tree has a vertex of minimum degree.";
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(temporaryDirectory, "failed-route-artifact")) {
      harness.freezeAndCreateRouteForClaim("failed-source", claimId, statement);
      harness.runSingleLegacyClaimRound(0, claimId, statement);
      harness.distributeBrokerArtifacts();

      assertThat(harness.claimCourt().records())
          .singleElement()
          .satisfies(record -> assertThat(record.outcome()).isEqualTo(ClaimCourtOutcome.VERIFIED));
      assertThat(harness.mathematicalArtifactBroker().artifacts())
          .singleElement()
          .satisfies(
              artifact -> {
                assertThat(artifact.artifactType()).isEqualTo(BrokerArtifactType.VERIFIED_CLAIM);
                assertThat(artifact.sourceRouteId()).isEqualTo("route-1");
                assertThat(artifact.sourceClaimId()).isEqualTo(claimId);
              });
    }
  }

  @Test
  void exactRefutationPublishesOnlyAsVerifiedCounterexample() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("counterexample"), "failed-route-counterexample")) {
      harness.freezeAndCreateRoute();
      harness.runSingleLegacyClaimRound(
          0,
          "false-connected-graph",
          "FALSE_LOCAL_REFUTED: Every connected finite graph has a Hamiltonian cycle; P4 refutes it.");
      installTrustedCounterexampleAuthority(harness);
      harness.distributeBrokerArtifacts();

      assertThat(harness.claimCourt().records().getFirst().outcome())
          .isEqualTo(ClaimCourtOutcome.REFUTED);
      assertThat(harness.mathematicalArtifactBroker().artifacts())
          .singleElement()
          .satisfies(
              artifact ->
                  assertThat(artifact.artifactType())
                      .isEqualTo(BrokerArtifactType.VERIFIED_COUNTEREXAMPLE));
    }
  }

  private static void installTrustedCounterexampleAuthority(
      DesktopClaimSalvageTestHarness harness) {
    ClaimCourtRecord court = harness.claimCourt().records().getFirst();
    FrozenClaimSnapshot frozen = court.frozenClaim();
    String targetObligationId = "exact-target-" + frozen.claimId();
    harness
        .proofGraph()
        .addObligation(
            new ProofObligation(
                List.of(),
                0.5d,
                "",
                List.of(),
                List.of(),
                List.of(),
                null,
                ObligationKind.LEMMA,
                frozen.statement(),
                targetObligationId,
                0.7d,
                frozen.problemHash(),
                List.of(),
                List.of(frozen.sourceRouteId()),
                frozen.statement(),
                "refuted"));

    AttemptArtifactRecord source =
        harness.attemptArtifacts().records().stream()
            .filter(record -> record.claimId().equals(frozen.claimId()))
            .findFirst()
            .orElseThrow();
    String artifactId = "trusted-counterexample-" + frozen.claimId();
    harness
        .attemptArtifacts()
        .addAll(
            List.of(
                new AttemptArtifactRecord(
                    artifactId,
                    source.problemHash(),
                    source.routeId(),
                    source.sourceAttemptId(),
                    AttemptStatus.FAILED,
                    source.sourceDeltaId(),
                    source.sourceRouteStatus(),
                    AttemptArtifactKind.COUNTEREXAMPLE,
                    frozen.claimId(),
                    CanonicalJson.stableHash(List.of(frozen.claimSemanticHash(), "P4")),
                    "P4 is an independently replayed exact counterexample.",
                    "independent-computation-replay",
                    true,
                    targetObligationId,
                    AttemptArtifactStatus.HARVESTED,
                    List.of(),
                    court.refutationEvidenceIds(),
                    null,
                    1L,
                    List.of("harvested:counterexample"))));
    harness
        .attemptArtifacts()
        .applyCourtOutcome(
            artifactId,
            ClaimCourtOutcome.VERIFIED,
            court.courtCaseId(),
            court.currentProofRevisionId(),
            "trusted counterexample evidence bound to the refuted statement");

    String evidenceId = "trusted-negative-" + frozen.claimId();
    MessageEnvelope evidence =
        new MessageEnvelope(
            List.of("experiment://" + evidenceId),
            frozen.assumptions(),
            frozen.conclusion(),
            "",
            null,
            frozen.dependencyClaimIds(),
            List.of(),
            EvidenceType.COUNTEREXAMPLE,
            MemoryTier.NEGATIVE,
            evidenceId,
            MessageType.COUNTEREXAMPLE,
            1.0d,
            frozen.statement(),
            frozen.problemHash(),
            frozen.quantifiers(),
            "result-hash-" + evidenceId,
            0,
            "1",
            frozen.scopeLimitations(),
            "independent-computation-replay",
            RouteRole.SKEPTIC,
            frozen.sourceRouteId(),
            frozen.statement(),
            List.of(),
            2,
            frozen.variableBindings(),
            1.0d,
            ClaimStatus.REJECTED);
    harness
        .typedMemory()
        .applyVerifiedCounterexample(
            evidence,
            VerifiedCounterexampleAuthority.independentReplay(
                true,
                true,
                ComputationEvidenceGate.EvidenceAuthority.REFUTED,
                "experiment://" + evidenceId,
                frozen.statement(),
                "result-hash-" + evidenceId,
                List.of()));
    harness.attemptArtifacts().markCounterexampleApplied(artifactId, evidence.messageId());
  }

  @Test
  void invalidProofPublishesReviewedObstructionWithoutClaimAuthority() throws Exception {
    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("obstruction"), "failed-route-obstruction")) {
      harness.freezeAndCreateRoute();
      harness.runSingleLegacyClaimRound(
          0,
          "tree-leaves-bad-proof",
          "FALSE_LOCAL_BAD_PROOF: Every finite tree with at least two vertices has two leaves; this proof needs a nonlocal rewrite.");
      harness.distributeBrokerArtifacts();

      assertThat(harness.claimCourt().records().getFirst().outcome())
          .isEqualTo(ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN);
      assertThat(harness.mathematicalArtifactBroker().artifacts())
          .singleElement()
          .satisfies(
              artifact ->
                  assertThat(artifact.artifactType())
                      .isEqualTo(BrokerArtifactType.REVIEWED_OBSTRUCTION));
    }
  }
}
