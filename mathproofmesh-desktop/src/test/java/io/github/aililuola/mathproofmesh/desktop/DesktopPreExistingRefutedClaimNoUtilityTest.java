package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerDeliveryBaseline;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactReceiptStatus;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerVerifiedEffectType;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPreExistingRefutedClaimNoUtilityTest {
  private static final int RESTORE_ROUND = 5;

  @TempDir Path temporaryDirectory;

  @Test
  void preExistingRouteRefutationCannotEarnPostDeliveryUtilityAfterRestore() throws Exception {
    int preExistingRouteRefutedClaims;
    int baselineClaimIds;
    int baselineNegativeMessageIds;
    int newlyRefutedClaims;
    int exactRefutationEffects;
    int utilities;
    int identityDomainMismatches;

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory, "preexisting-refuted-broker-utility")) {
      harness.freezeAndCreateRoute();
      String suffix = "preexisting-refuted";
      String claimId = "claim-" + suffix;
      harness.addRejectedClaimToRoute(claimId);
      harness.setRoundForBrokerTest(RESTORE_ROUND);
      harness.installBrokerUseAttempt(RESTORE_ROUND);

      DesktopBrokerArtifactFixture fixture =
          new DesktopBrokerArtifactFixture(
              harness.mathematicalArtifactBroker(),
              DesktopClaimSalvageTestHarness.PROBLEM_HASH,
              harness.rootGoal().sourceStatementHash());
      var artifact = fixture.counterexample(suffix, "source-refutation-route");
      var publication =
          fixture.broker.publish(
              artifact,
              java.util.List.of(fixture.related("route-1", suffix)),
              RESTORE_ROUND,
              8);
      assertThat(publication.deliveries()).hasSize(1);

      var promptBatch = harness.consumeBrokerContext();
      assertThat(promptBatch.deliveries()).hasSize(1);
      String deliveryId = promptBatch.deliveries().getFirst().deliveryId();

      DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
      harness.restore(checkpoint);
      BrokerDeliveryBaseline baseline =
          fixture.broker.deliverySnapshot().baselines().get(deliveryId);
      assertThat(baseline).isNotNull();

      Set<String> routeRefuted = harness.rejectedClaimIds();
      Set<String> negativeMessageIds =
          harness.typedMemory().negatives().stream()
              .map(MessageEnvelope::messageId)
              .collect(java.util.stream.Collectors.toUnmodifiableSet());
      preExistingRouteRefutedClaims = routeRefuted.contains(claimId) ? 1 : 0;
      baselineClaimIds = intersectionSize(baseline.refutedClaimIdsBefore(), routeRefuted);
      baselineNegativeMessageIds =
          intersectionSize(baseline.refutedClaimIdsBefore(), negativeMessageIds);
      identityDomainMismatches = symmetricDifferenceSize(baseline.refutedClaimIdsBefore(), routeRefuted);

      harness.stageAndAcknowledgeBrokerUse(
          fixture.use(promptBatch.providerRequestId(), artifact, BrokerArtifactUseKind.REFUTES_CLAIM));
      assertThat(fixture.broker.receipts())
          .singleElement()
          .extracting(receipt -> receipt.status())
          .isEqualTo(BrokerArtifactReceiptStatus.USED_PENDING_EFFECT);
      harness.verifyConsumedArtifactEffects();

      Set<String> postDeliveryRefuted = harness.rejectedClaimIds();
      newlyRefutedClaims =
          differenceSize(postDeliveryRefuted, baseline.refutedClaimIdsBefore());
      exactRefutationEffects =
          (int)
              fixture.broker.utilities().stream()
                  .filter(
                      utility ->
                          utility.verifiedEffectTypes().contains(
                              BrokerVerifiedEffectType.EXACT_CLAIM_REFUTED))
                  .count();
      utilities = fixture.broker.utilities().size();
    }

    System.out.println("PREEXISTING REFUTED CLAIM BROKER DIAGNOSTIC");
    System.out.println("RESTORE_ROUND=" + RESTORE_ROUND);
    System.out.println("PREEXISTING_ROUTE_REFUTED_CLAIMS=" + preExistingRouteRefutedClaims);
    System.out.println("REFUTED_BASELINE_CLAIM_IDS=" + baselineClaimIds);
    System.out.println("REFUTED_BASELINE_NEGATIVE_MESSAGE_IDS=" + baselineNegativeMessageIds);
    System.out.println("NEW_REFUTED_CLAIMS_AFTER_DELIVERY=" + newlyRefutedClaims);
    System.out.println("EXACT_CLAIM_REFUTED_EFFECTS=" + exactRefutationEffects);
    System.out.println("PREEXISTING_REFUTED_CLAIM_UTILITIES=" + utilities);
    System.out.println("REFUTED_BASELINE_ID_DOMAIN_MISMATCHES=" + identityDomainMismatches);

    assertThat(preExistingRouteRefutedClaims).isEqualTo(1);
    assertThat(baselineClaimIds).isEqualTo(1);
    assertThat(baselineNegativeMessageIds).isZero();
    assertThat(newlyRefutedClaims).isZero();
    assertThat(exactRefutationEffects).isZero();
    assertThat(utilities).isZero();
    assertThat(identityDomainMismatches).isZero();
    System.out.println("RESULT=PASS");
  }

  private static int intersectionSize(Set<String> left, Set<String> right) {
    Set<String> intersection = new LinkedHashSet<>(left);
    intersection.retainAll(right);
    return intersection.size();
  }

  private static int differenceSize(Set<String> left, Set<String> right) {
    Set<String> difference = new LinkedHashSet<>(left);
    difference.removeAll(right);
    return difference.size();
  }

  private static int symmetricDifferenceSize(Set<String> left, Set<String> right) {
    return differenceSize(left, right) + differenceSize(right, left);
  }
}
