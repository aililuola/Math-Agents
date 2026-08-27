package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.research.ResearchFindingRecord;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopCampaignResearchFindingPropagationTest {
  private static final String CAMPAIGN_RESEARCH = "campaign-research";
  private static final String FINDING =
      "A global minimal hitting set need only use primes not exceeding a1; it is unnecessary to "
          + "prove that every prime appearing in the sequence belongs to a fixed finite set.";

  @Test
  void strategyFindingSurvivesAdmissionFirstRoutePromptAndV8Restore(@TempDir Path directory)
      throws Exception {
    int campaignFindingsEmitted;
    int campaignFindingsPersisted;
    int firstRoutePromptsChecked;
    int campaignFindingVisibilityFailures;
    int postRestoreCampaignFindingLosses;
    int postRestorePromptVisibilityFailures;
    int duplicateCampaignFindings;
    long directFactPromotions;
    int directClaimVerifications;
    int directNegativeRegistrations;
    int mainGoalClosures;
    int rootHashChanges;
    int negativeRegistryHashChanges;
    int attemptArtifactLedgerHashChanges;
    int claimLifecycleHashChanges;

    try (var first =
        DesktopResearchCheckpointBlackBoxHarness.open(
            directory,
            "campaign-research-propagation",
            DesktopResearchCheckpointBlackBoxHarness.Scenario.CAMPAIGN_PROPAGATION,
            FINDING)) {
      first.freezeProblemOnly();
      String rootHash = first.rootHash();
      String negativeHash = first.negativeHash();
      String attemptHash = first.attemptArtifactHash();
      String claimHash = first.claimLifecycleHash();
      long baselineFacts = first.directFactPromotions();
      int baselineClaims = first.directClaimVerifications();
      int baselineNegatives = first.permanentNegativeRegistrations();
      int baselineMainGoalClosures = first.mainGoalClosures();

      first.generateCampaignStrategyAndAdmitRoute();
      List<ResearchFindingRecord> persisted =
          first.researchLedger().activeFindings(CAMPAIGN_RESEARCH);
      DesktopSolveCheckpoint beforeExploration = first.currentCheckpoint();
      first.explorePreparedRoutes();

      campaignFindingsEmitted = first.campaignFindingsEmitted();
      campaignFindingsPersisted = persisted.size();
      firstRoutePromptsChecked = first.downstreamPrompts().size();
      campaignFindingVisibilityFailures = visibilityFailures(first.downstreamPrompts());
      directFactPromotions = first.directFactPromotions() - baselineFacts;
      directClaimVerifications = first.directClaimVerifications() - baselineClaims;
      directNegativeRegistrations =
          first.permanentNegativeRegistrations() - baselineNegatives;
      mainGoalClosures = first.mainGoalClosures() - baselineMainGoalClosures;

      assertThat(campaignFindingsEmitted).isEqualTo(1);
      assertThat(persisted)
          .singleElement()
          .extracting(ResearchFindingRecord::statement)
          .isEqualTo(FINDING);
      assertThat(first.researchLedger().activeFindings("route-1")).isEmpty();
      assertThat(firstRoutePromptsChecked).isEqualTo(1);
      assertThat(campaignFindingVisibilityFailures).isZero();
      assertThat(directFactPromotions).isZero();
      assertThat(directClaimVerifications).isZero();
      assertThat(directNegativeRegistrations).isZero();
      assertThat(mainGoalClosures).isZero();

      try (var restored = first.restored(beforeExploration)) {
        List<ResearchFindingRecord> restoredCampaign =
            restored.researchLedger().activeFindings(CAMPAIGN_RESEARCH);
        restored.explorePreparedRoutes();
        postRestoreCampaignFindingLosses = Math.max(0, 1 - restoredCampaign.size());
        postRestorePromptVisibilityFailures = visibilityFailures(restored.downstreamPrompts());
        duplicateCampaignFindings =
            restoredCampaign.size()
                - (int)
                    restoredCampaign.stream()
                        .map(ResearchFindingRecord::findingId)
                        .distinct()
                        .count();
        rootHashChanges = restored.rootHash().equals(rootHash) ? 0 : 1;
        negativeRegistryHashChanges = restored.negativeHash().equals(negativeHash) ? 0 : 1;
        attemptArtifactLedgerHashChanges =
            restored.attemptArtifactHash().equals(attemptHash) ? 0 : 1;
        claimLifecycleHashChanges = restored.claimLifecycleHash().equals(claimHash) ? 0 : 1;

        assertThat(restoredCampaign)
            .singleElement()
            .extracting(ResearchFindingRecord::statement)
            .isEqualTo(FINDING);
        assertThat(restored.researchLedger().activeFindings("route-1")).isEmpty();
        assertThat(restored.downstreamPrompts()).hasSize(1);
        assertThat(postRestoreCampaignFindingLosses).isZero();
        assertThat(postRestorePromptVisibilityFailures).isZero();
        assertThat(duplicateCampaignFindings).isZero();
        assertThat(restored.directFactPromotions() - baselineFacts).isZero();
        assertThat(restored.directClaimVerifications() - baselineClaims).isZero();
        assertThat(restored.permanentNegativeRegistrations() - baselineNegatives).isZero();
        assertThat(restored.mainGoalClosures() - baselineMainGoalClosures).isZero();
        assertThat(rootHashChanges).isZero();
        assertThat(negativeRegistryHashChanges).isZero();
        assertThat(attemptArtifactLedgerHashChanges).isZero();
        assertThat(claimLifecycleHashChanges).isZero();
      }
    }

    System.out.println("CAMPAIGN RESEARCH FINDING PROPAGATION DIAGNOSTIC");
    print("CAMPAIGN_FINDINGS_EMITTED", campaignFindingsEmitted);
    print("CAMPAIGN_FINDINGS_PERSISTED", campaignFindingsPersisted);
    print("FIRST_ROUTE_PROMPTS_CHECKED", firstRoutePromptsChecked);
    print("CAMPAIGN_FINDING_VISIBILITY_FAILURES", campaignFindingVisibilityFailures);
    print("POST_RESTORE_CAMPAIGN_FINDING_LOSSES", postRestoreCampaignFindingLosses);
    print("POST_RESTORE_PROMPT_VISIBILITY_FAILURES", postRestorePromptVisibilityFailures);
    print("DUPLICATE_CAMPAIGN_FINDINGS", duplicateCampaignFindings);
    print("DIRECT_FACT_PROMOTIONS", directFactPromotions);
    print("DIRECT_CLAIM_VERIFICATIONS", directClaimVerifications);
    print("DIRECT_NEGATIVE_REGISTRATIONS", directNegativeRegistrations);
    print("MAIN_GOAL_CLOSURES", mainGoalClosures);
    print("ROOT_HASH_CHANGES", rootHashChanges);
    print("NEGATIVE_REGISTRY_HASH_CHANGES", negativeRegistryHashChanges);
    print("ATTEMPT_ARTIFACT_LEDGER_HASH_CHANGES", attemptArtifactLedgerHashChanges);
    print("CLAIM_LIFECYCLE_HASH_CHANGES", claimLifecycleHashChanges);
    System.out.println("RESULT=PASS");
  }

  private static int visibilityFailures(List<String> prompts) {
    return (int)
        prompts.stream()
            .filter(
                prompt ->
                    !prompt.contains("campaign_research_findings")
                        || !prompt.contains("campaign_checkpoint_frames")
                        || !prompt.contains("campaign_research_authority_rule")
                        || !prompt.contains("non-authoritative")
                        || !prompt.contains("Claim Review")
                        || !prompt.contains(FINDING))
            .count();
  }

  private static void print(String name, long value) {
    System.out.println(name + "=" + value);
  }
}
