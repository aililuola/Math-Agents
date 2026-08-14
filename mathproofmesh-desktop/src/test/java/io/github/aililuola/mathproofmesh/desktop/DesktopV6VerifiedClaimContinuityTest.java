package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopV6VerifiedClaimContinuityTest {
  private static final String CLAIM_ID = "legacy-local-claim";
  private static final String STATEMENT =
      "LEGACY_LOCAL_CLAIM: every square of an even integer is divisible by four.";

  @TempDir Path temporaryDirectory;

  @Test
  void verifiedV6FactRemainsAuthoritativeWithoutReviewOrRepromotion() throws Exception {
    Path runDirectory = temporaryDirectory.resolve("v6-verified-claim-continuity");
    String runId = "v6-verified-claim-continuity";
    DesktopSolveCheckpoint versionSix;
    int legacyVerifiedFactsBefore;
    String initialRootHash;
    String initialNegativeHash;

    try (DesktopClaimSalvageTestHarness source =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      source.freezeAndCreateRoute();
      source.runSingleLegacyClaimRound(0, CLAIM_ID, STATEMENT);
      legacyVerifiedFactsBefore = factCount(source);
      initialRootHash = source.rootGoal().sourceStatementHash();
      initialNegativeHash = source.negativeRegistryHash();
      assertLegacyAuthorityProjections(source);
      versionSix = asVersionSix(source.checkpointRoundTrip());
    }

    assertThat(legacyVerifiedFactsBefore).isEqualTo(1);
    assertThat(versionSix.schemaVersion()).isEqualTo(6);
    assertThat(versionSix.attemptArtifacts().records()).isEmpty();
    assertThat(versionSix.claimLifecycle().entries()).isEmpty();

    int legacyVerifiedFactsAfter;
    int visibilityFailures;
    int legacyFactDuplicates;
    int legacyFactReviewCalls;
    int legacyFactRepromotions;
    int proofGraphNodeLosses;
    int lemmaStatusChanges;
    int rootHashChanges = 0;
    int negativeRegistryHashChanges = 0;
    DesktopSolveCheckpoint versionSeven;

    try (DesktopClaimSalvageTestHarness restored =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      restored.restore(versionSix);
      int factsImmediatelyAfterRestore = factCount(restored);
      int promotionsImmediatelyAfterRestore = promotionEventCount(restored);
      legacyVerifiedFactsAfter = factsImmediatelyAfterRestore;
      assertLegacyAuthorityProjections(restored);
      assertThat(restored.claimLifecycle().get(CLAIM_ID).state())
          .isEqualTo(ClaimLifecycleController.State.EXTERNALLY_ADMITTED_FACT);

      List<String> verifiedFacts = restored.exploreSubsequentRouteAndCaptureVerifiedFacts();
      visibilityFailures = verifiedFacts.contains(STATEMENT) ? 0 : 1;
      legacyFactDuplicates = duplicateFactCount(restored);
      legacyFactReviewCalls = restored.claimReviewRequests().size();
      legacyFactRepromotions =
          Math.max(0, promotionEventCount(restored) - promotionsImmediatelyAfterRestore);
      proofGraphNodeLosses = hasProofGraphNode(restored) ? 0 : 1;
      lemmaStatusChanges = hasVerifiedLemma(restored) ? 0 : 1;
      rootHashChanges +=
          restored.rootGoal().sourceStatementHash().equals(initialRootHash) ? 0 : 1;
      negativeRegistryHashChanges +=
          restored.negativeRegistryHash().equals(initialNegativeHash) ? 0 : 1;
      versionSeven = restored.checkpointRoundTrip();
    }

    assertThat(versionSeven.schemaVersion())
        .isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
    assertThat(versionSeven.claimLifecycle().entries()).containsKey(CLAIM_ID);

    int secondRestoreFactLosses;
    int secondRestoreDuplicates;
    try (DesktopClaimSalvageTestHarness restoredAgain =
        DesktopClaimSalvageTestHarness.open(runDirectory, runId)) {
      restoredAgain.restore(versionSeven);
      secondRestoreFactLosses = factCount(restoredAgain) == 1 ? 0 : 1;
      secondRestoreDuplicates = duplicateFactCount(restoredAgain);
      legacyFactReviewCalls += restoredAgain.claimReviewRequests().size();
      assertLegacyAuthorityProjections(restoredAgain);
      assertThat(restoredAgain.claimLifecycle().get(CLAIM_ID).state())
          .isEqualTo(ClaimLifecycleController.State.EXTERNALLY_ADMITTED_FACT);
      rootHashChanges +=
          restoredAgain.rootGoal().sourceStatementHash().equals(initialRootHash) ? 0 : 1;
      negativeRegistryHashChanges +=
          restoredAgain.negativeRegistryHash().equals(initialNegativeHash) ? 0 : 1;
    }

    assertThat(legacyVerifiedFactsAfter).isEqualTo(1);
    assertThat(visibilityFailures).isZero();
    assertThat(legacyFactDuplicates).isZero();
    assertThat(legacyFactReviewCalls).isZero();
    assertThat(legacyFactRepromotions).isZero();
    assertThat(proofGraphNodeLosses).isZero();
    assertThat(lemmaStatusChanges).isZero();
    assertThat(secondRestoreFactLosses).isZero();
    assertThat(secondRestoreDuplicates).isZero();
    assertThat(rootHashChanges).isZero();
    assertThat(negativeRegistryHashChanges).isZero();

    System.out.println("V6 VERIFIED CLAIM CONTINUITY DIAGNOSTIC");
    System.out.println("LEGACY_VERIFIED_FACTS_BEFORE=" + legacyVerifiedFactsBefore);
    System.out.println("LEGACY_VERIFIED_FACTS_AFTER=" + legacyVerifiedFactsAfter);
    System.out.println("LEGACY_FACT_VISIBILITY_FAILURES=" + visibilityFailures);
    System.out.println("LEGACY_FACT_DUPLICATES=" + legacyFactDuplicates);
    System.out.println("LEGACY_FACT_REVIEW_CALLS=" + legacyFactReviewCalls);
    System.out.println("LEGACY_FACT_REPROMOTIONS=" + legacyFactRepromotions);
    System.out.println("LEGACY_PROOFGRAPH_NODE_LOSSES=" + proofGraphNodeLosses);
    System.out.println("LEGACY_LEMMA_STATUS_CHANGES=" + lemmaStatusChanges);
    System.out.println("SECOND_RESTORE_FACT_LOSSES=" + secondRestoreFactLosses);
    System.out.println("SECOND_RESTORE_DUPLICATES=" + secondRestoreDuplicates);
    System.out.println("ROOT_HASH_CHANGES=" + rootHashChanges);
    System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=" + negativeRegistryHashChanges);
    System.out.println("RESULT=PASS");
  }

  private static DesktopSolveCheckpoint asVersionSix(DesktopSolveCheckpoint checkpoint) {
    ObjectNode json = (ObjectNode) ContractObjectMapper.toTree(checkpoint);
    json.put("schemaVersion", 6);
    json.remove("attemptArtifacts");
    json.remove("claimLifecycle");
    for (JsonNode route : json.withArray("routes")) {
      ((ObjectNode) route)
          .remove(
              List.of(
                  "artifactIds",
                  "salvagedVerifiedClaimIds",
                  "salvagedCounterexampleIds",
                  "rejectedClaimIds",
                  "uncertainClaimIds",
                  "claimReview"));
    }
    return ContractObjectMapper.read(
        ContractObjectMapper.write(json), DesktopSolveCheckpoint.class);
  }

  private static void assertLegacyAuthorityProjections(
      DesktopClaimSalvageTestHarness harness) {
    assertThat(factCount(harness)).isEqualTo(1);
    assertThat(hasVerifiedLemma(harness)).isTrue();
    assertThat(hasProofGraphNode(harness)).isTrue();
  }

  private static int factCount(DesktopClaimSalvageTestHarness harness) {
    return (int)
        harness.typedMemory().facts().stream()
            .filter(fact -> STATEMENT.equals(fact.statement()))
            .count();
  }

  private static int duplicateFactCount(DesktopClaimSalvageTestHarness harness) {
    return Math.max(0, factCount(harness) - 1);
  }

  private static int promotionEventCount(DesktopClaimSalvageTestHarness harness) {
    return (int)
        harness.typedMemory().audit().stream()
            .filter(event -> CLAIM_ID.equals(event.itemId()))
            .filter(event -> "fact_promoted".equals(event.eventType()))
            .count();
  }

  private static boolean hasVerifiedLemma(DesktopClaimSalvageTestHarness harness) {
    return harness.lemmaMemory().claims().stream()
        .anyMatch(claim -> CLAIM_ID.equals(claim.claimId()) && claim.status() == ClaimStatus.VERIFIED);
  }

  private static boolean hasProofGraphNode(DesktopClaimSalvageTestHarness harness) {
    return harness.proofGraph().claimNodes().stream()
        .anyMatch(node -> CLAIM_ID.equals(node.messageId()));
  }
}
