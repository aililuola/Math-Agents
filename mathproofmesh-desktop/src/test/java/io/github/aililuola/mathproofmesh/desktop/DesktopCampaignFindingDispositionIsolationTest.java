package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.research.ResearchFindingRecord;
import io.github.aililuola.mathproofmesh.research.ResearchFindingStatus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCampaignFindingDispositionIsolationTest {
  private static final String CAMPAIGN_RESEARCH = "campaign-research";
  private static final String FINDING =
      "A campaign-level exact reduction is available as a non-authoritative candidate.";

  @Test
  void campaignKeepActiveObservationCannotCrashRouteOrAcquireAuthority(@TempDir Path directory)
      throws Exception {
    int keepActiveObservations = 1;
    int submittedAttempts;
    int routeWorkerFailures;
    int campaignFindingMutations;
    long directFactPromotions;
    int directClaimVerifications;
    int directNegativeRegistrations;
    int rootHashChanges;
    int negativeRegistryHashChanges;

    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "campaign-finding-disposition-isolation",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.CAMPAIGN_KEEP_ACTIVE,
            FINDING)) {
      harness.freezeProblemOnly();
      String rootHash = harness.rootHash();
      String negativeHash = harness.negativeHash();
      long baselineFacts = harness.directFactPromotions();
      int baselineClaims = harness.directClaimVerifications();
      int baselineNegatives = harness.permanentNegativeRegistrations();

      harness.generateCampaignStrategyAndAdmitRoute();
      ResearchFindingRecord before =
          harness.researchLedger().activeFindings(CAMPAIGN_RESEARCH).getFirst();
      harness.explorePreparedRoutes();
      ResearchFindingRecord after = harness.researchLedger().finding(before.findingId());

      submittedAttempts = harness.submittedAttemptCount();
      routeWorkerFailures = harness.failedRouteCount();
      campaignFindingMutations = before.equals(after) ? 0 : 1;
      directFactPromotions = harness.directFactPromotions() - baselineFacts;
      directClaimVerifications = harness.directClaimVerifications() - baselineClaims;
      directNegativeRegistrations =
          harness.permanentNegativeRegistrations() - baselineNegatives;
      rootHashChanges = harness.rootHash().equals(rootHash) ? 0 : 1;
      negativeRegistryHashChanges = harness.negativeHash().equals(negativeHash) ? 0 : 1;

      assertThat(after.status()).isEqualTo(ResearchFindingStatus.ACTIVE);
      assertThat(after.routeId()).isEqualTo(CAMPAIGN_RESEARCH);
      assertThat(harness.researchLedger().activeFindings("route-1")).isEmpty();
      assertThat(submittedAttempts).isEqualTo(1);
      assertThat(routeWorkerFailures).isZero();
      assertThat(campaignFindingMutations).isZero();
      assertThat(directFactPromotions).isZero();
      assertThat(directClaimVerifications).isZero();
      assertThat(directNegativeRegistrations).isZero();
      assertThat(rootHashChanges).isZero();
      assertThat(negativeRegistryHashChanges).isZero();
    }

    System.out.println("CAMPAIGN FINDING DISPOSITION ISOLATION DIAGNOSTIC");
    print("CAMPAIGN_KEEP_ACTIVE_OBSERVATIONS", keepActiveObservations);
    print("SUBMITTED_ATTEMPTS", submittedAttempts);
    print("ROUTE_WORKER_FAILURES", routeWorkerFailures);
    print("CAMPAIGN_FINDING_MUTATIONS", campaignFindingMutations);
    print("DIRECT_FACT_PROMOTIONS", directFactPromotions);
    print("DIRECT_CLAIM_VERIFICATIONS", directClaimVerifications);
    print("DIRECT_NEGATIVE_REGISTRATIONS", directNegativeRegistrations);
    print("ROOT_HASH_CHANGES", rootHashChanges);
    print("NEGATIVE_REGISTRY_HASH_CHANGES", negativeRegistryHashChanges);
    System.out.println("RESULT=PASS");
  }

  @Test
  void stateChangingCampaignDispositionFailsClosedWithoutMutation(@TempDir Path directory)
      throws Exception {
    int crossRouteMutationBlocks;
    int campaignFindingMutations;
    int submittedAttemptLeaks;
    int authorityLeaks;

    try (var harness =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "campaign-finding-mutation-block",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.CAMPAIGN_DEFER,
            FINDING)) {
      harness.freezeProblemOnly();
      String rootHash = harness.rootHash();
      String negativeHash = harness.negativeHash();
      long baselineFacts = harness.directFactPromotions();
      int baselineClaims = harness.directClaimVerifications();
      int baselineNegatives = harness.permanentNegativeRegistrations();

      harness.generateCampaignStrategyAndAdmitRoute();
      ResearchFindingRecord before =
          harness.researchLedger().activeFindings(CAMPAIGN_RESEARCH).getFirst();
      harness.explorePreparedRoutes();
      ResearchFindingRecord after = harness.researchLedger().finding(before.findingId());

      crossRouteMutationBlocks = harness.failedRouteCount();
      campaignFindingMutations = before.equals(after) ? 0 : 1;
      submittedAttemptLeaks = harness.submittedAttemptCount();
      authorityLeaks =
          Math.toIntExact(harness.directFactPromotions() - baselineFacts)
              + (harness.directClaimVerifications() - baselineClaims)
              + (harness.permanentNegativeRegistrations() - baselineNegatives);

      assertThat(after).isEqualTo(before);
      assertThat(submittedAttemptLeaks).isZero();
      assertThat(crossRouteMutationBlocks).isEqualTo(1);
      assertThat(harness.directFactPromotions() - baselineFacts).isZero();
      assertThat(harness.directClaimVerifications() - baselineClaims).isZero();
      assertThat(harness.permanentNegativeRegistrations() - baselineNegatives).isZero();
      assertThat(campaignFindingMutations).isZero();
      assertThat(authorityLeaks).isZero();
      assertThat(harness.rootHash()).isEqualTo(rootHash);
      assertThat(harness.negativeHash()).isEqualTo(negativeHash);
    }

    System.out.println("CROSS_ROUTE_CAMPAIGN_MUTATION_DIAGNOSTIC");
    print("CROSS_ROUTE_MUTATION_BLOCKS", crossRouteMutationBlocks);
    print("CAMPAIGN_FINDING_MUTATIONS", campaignFindingMutations);
    print("SUBMITTED_ATTEMPT_LEAKS", submittedAttemptLeaks);
    print("AUTHORITY_LEAKS", authorityLeaks);
    System.out.println("RESULT=PASS");
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
