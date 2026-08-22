package io.github.aililuola.mathproofmesh.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ActionKind;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Fail-closed coverage for the durable budget contracts used at restore boundaries. */
class BudgetInvariantValidationTest {

  @Test
  void certifiedGainReceiptDerivesGainAndRejectsInvalidOrForgedReceipts() {
    CertifiedGainReceipt noGain = receipt(0, 0, 0, 0, 3.0d, 3.0d, 0, 0, null);
    assertThat(noGain.meaningfulGain()).isFalse();
    assertThat(receipt(1, 0, 0, 0, 3.0d, 3.0d, 0, 0, null).meaningfulGain()).isTrue();
    assertThat(receipt(0, 1, 0, 0, 3.0d, 3.0d, 0, 0, null).meaningfulGain()).isTrue();
    assertThat(receipt(0, 0, 1, 0, 3.0d, 3.0d, 0, 0, null).meaningfulGain()).isTrue();
    assertThat(receipt(0, 0, 0, 1, 3.0d, 3.0d, 0, 0, null).meaningfulGain()).isTrue();
    assertThat(receipt(0, 0, 0, 0, 3.0d, 2.0d, 0, 0, null).meaningfulGain()).isTrue();
    assertThat(receipt(0, 0, 0, 0, 3.0d, 3.0d, 1, 0, null).meaningfulGain()).isTrue();
    assertThat(receipt(0, 0, 0, 0, 3.0d, 3.0d, 0, 1, null).meaningfulGain()).isTrue();
    assertThat(receipt(0, 0, 0, 0, 3.0d, 3.0d, 0, 0, noGain.receiptHash()))
        .isEqualTo(noGain);

    for (int index = 0; index < 6; index++) {
      int invalidIndex = index;
      assertThatThrownBy(() -> receiptWithInvalidCounter(invalidIndex))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (double[] debt :
        List.of(
            new double[] {Double.NaN, 0.0d},
            new double[] {0.0d, Double.NaN},
            new double[] {-1.0d, 0.0d},
            new double[] {0.0d, -1.0d})) {
      assertThatThrownBy(() -> receipt(0, 0, 0, 0, debt[0], debt[1], 0, 0, null))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> receipt(0, 0, 0, 0, 0.0d, 0.0d, 0, 0, "forged"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hash mismatch");
    assertThatThrownBy(
            () ->
                new CertifiedGainReceipt(
                    null, "before", "after", 0, 0, 0, 0, 0.0d, 0.0d, 0, 0, false, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CertifiedGainReceipt(
                    "epoch", " ", "after", 0, 0, 0, 0, 0.0d, 0.0d, 0, 0, false, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new CertifiedGainReceipt(
                    "epoch", "before", "", 0, 0, 0, 0, 0.0d, 0.0d, 0, 0, false, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void evidencePolicyCoversEveryFailureClassWithoutTrustingSelfReportedProgress() {
    BudgetEvidencePolicy defaultPolicy = BudgetEvidencePolicy.defaults();
    PathBudgetStats clean = path(AttemptEvidence.FailureClass.NONE, 0, false, "uncertain");
    PathBudgetStats execution = path(AttemptEvidence.FailureClass.EXECUTION, 0, false, "unknown");
    PathBudgetStats structural = path(AttemptEvidence.FailureClass.STRUCTURAL, 0, false, "fail");
    PathBudgetStats strategy = path(AttemptEvidence.FailureClass.STRATEGY, 0, false, "fail");
    PathBudgetStats integrity =
        path(AttemptEvidence.FailureClass.PROBLEM_INTEGRITY, 0, false, "fail");

    assertThat(defaultPolicy.repairAllowed(clean)).isFalse();
    assertThat(defaultPolicy.repairAllowed(execution)).isTrue();
    assertThat(defaultPolicy.repairAllowed(structural)).isTrue();
    assertThat(defaultPolicy.repairAllowed(strategy)).isFalse();
    assertThat(defaultPolicy.repairAllowed(integrity)).isFalse();
    assertThat(policy(true).repairAllowed(strategy)).isTrue();
    assertThat(defaultPolicy.adjustedProgress(clean)).isLessThan(clean.marginalProgress());
    assertThat(defaultPolicy.adjustedProgress(execution)).isLessThanOrEqualTo(0.45d);
    assertThat(defaultPolicy.adjustedProgress(structural)).isLessThanOrEqualTo(0.1d);
    assertThat(defaultPolicy.adjustedProgress(strategy)).isLessThanOrEqualTo(0.05d);
    assertThat(defaultPolicy.adjustedProgress(integrity)).isLessThanOrEqualTo(0.1d);
    assertThat(defaultPolicy.failurePenalty(clean)).isZero();
    assertThat(defaultPolicy.failurePenalty(execution)).isPositive();
    assertThat(defaultPolicy.failurePenalty(structural)).isPositive();
    assertThat(defaultPolicy.failurePenalty(strategy)).isPositive();
    assertThat(defaultPolicy.failurePenalty(integrity)).isPositive();

    for (int invalidLimit = 0; invalidLimit < 3; invalidLimit++) {
      int selected = invalidLimit;
      assertThatThrownBy(() -> policyWithInvalidRepairLimit(selected))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (double invalid : List.of(Double.NaN, -0.1d, 1.1d)) {
      assertThatThrownBy(() -> policyWithThreshold(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
    for (double invalid : List.of(Double.NaN, -0.1d)) {
      assertThatThrownBy(() -> policyWithPenalty(invalid))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void actionAndStageEnvelopesRejectEveryInvalidDimension() {
    for (int index = 0; index < 21; index++) {
      int selected = index;
      assertThatThrownBy(() -> invalidProfile(selected))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("invalid action cost profile");
    }
    for (int index = 0; index < 9; index++) {
      int selected = index;
      assertThatThrownBy(() -> invalidStageRequest(selected))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("invalid stage token envelope input");
    }
    assertThatThrownBy(() -> new StageTokenEnvelopeResolver.Resolution(true, null, 1, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StageTokenEnvelopeResolver.Resolution(true, " ", 1, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StageTokenEnvelopeResolver.Resolution(true, "ALLOW", -1, 1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StageTokenEnvelopeResolver.Resolution(true, "ALLOW", 1, -1, 2))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StageTokenEnvelopeResolver.Resolution(true, "ALLOW", 1, 1, -1))
        .isInstanceOf(IllegalArgumentException.class);

    StageTokenEnvelopeResolver resolver = new StageTokenEnvelopeResolver();
    assertThat(resolver.resolve(new StageTokenEnvelopeResolver.Request(1, 2, 2, 2, 2, 2, 2, 1, 2)).code())
        .isEqualTo("FINISH_RESERVE_EXHAUSTED");
    assertThat(resolver.resolve(new StageTokenEnvelopeResolver.Request(3, 2, 2, 2, 2, 2, 2, 3, 0)).code())
        .isEqualTo("INPUT_CONTEXT_EXCEEDS_BUDGET_ENVELOPE");
    assertThat(resolver.resolve(new StageTokenEnvelopeResolver.Request(2, 2, 2, 2, 2, 0, 2, 2, 0)).code())
        .isEqualTo("OUTPUT_TOKEN_BUDGET_EXHAUSTED");
  }

  @Test
  void valueObjectsFailClosedOnDimensionHashAndScopeMismatches() {
    BudgetResourceVector one = vector(1, 1, 1, 2, "0.1");
    for (int index = 0; index < 4; index++) {
      int selected = index;
      assertThatThrownBy(() -> invalidVector(selected)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new BudgetResourceVector(0, 0, 0, 0, new BigDecimal("-1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> one.times(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> one.minus(vector(2, 1, 1, 2, "0.1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(vector(0, 1, 0, 0, "0").isZero()).isFalse();
    assertThat(vector(0, 0, 1, 0, "0").isZero()).isFalse();
    assertThat(vector(0, 0, 0, 1, "0").isZero()).isFalse();
    assertThat(vector(0, 0, 0, 0, "0.1").isZero()).isFalse();
    assertThat(vector(2, 1, 1, 2, "0.1").fitsWithin(one)).isFalse();
    assertThat(vector(1, 2, 1, 2, "0.1").fitsWithin(one)).isFalse();
    assertThat(vector(1, 1, 2, 2, "0.1").fitsWithin(one)).isFalse();
    assertThat(vector(1, 1, 1, 3, "0.1").fitsWithin(one)).isFalse();
    assertThat(vector(1, 1, 1, 2, "0.2").fitsWithin(one)).isFalse();

    PricingSnapshot billed = pricing(PricingSnapshot.BillingMode.BILLED, "1", "2", null);
    assertThat(pricing(PricingSnapshot.BillingMode.BILLING_EXEMPT, "0", "0", null)
            .usableWithCostCap())
        .isTrue();
    assertThat(PricingSnapshot.legacyUnknown().usableWithCostCap()).isFalse();
    assertThat(pricing(PricingSnapshot.BillingMode.BILLED, "1", "2", billed.pricingHash()))
        .isEqualTo(billed);
    assertThatThrownBy(() -> pricing(PricingSnapshot.BillingMode.BILLED, "0", "2", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pricing(PricingSnapshot.BillingMode.BILLED, "1", "0", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pricing(PricingSnapshot.BillingMode.BILLING_EXEMPT, "1", "0", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pricing(PricingSnapshot.BillingMode.BILLING_EXEMPT, "0", "1", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pricing(PricingSnapshot.BillingMode.UNKNOWN, "0", "0", "forged"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PricingSnapshot(
                    null,
                    "model",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    PricingSnapshot.BillingMode.UNKNOWN,
                    "config",
                    null))
        .isInstanceOf(IllegalArgumentException.class);

    TargetMechanismKey key = new TargetMechanismKey("target", "strategy", ActionKind.DEEPEN, "mechanism");
    assertThat(TargetMechanismKey.fromStableIdentity(key.stableIdentity())).isEqualTo(key);
    assertThatThrownBy(() -> TargetMechanismKey.fromStableIdentity("not-a-key"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new TargetMechanismKey("bad\u001fvalue", "strategy", ActionKind.DEEPEN, "mechanism"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void canonicalSnapshotsAndZeroGainStatePreserveStrictRestoreIdentity() {
    TargetMechanismKey key = new TargetMechanismKey("target", "strategy", ActionKind.DEEPEN, "mechanism");
    Set<String> legacyExhausted = new LinkedHashSet<>();
    legacyExhausted.add(null);
    legacyExhausted.add("");
    legacyExhausted.add("mechanism");
    ZeroGainState initial = new ZeroGainState(null, 0, legacyExhausted, null);
    ZeroGainState exhausted = initial.record(key, false, 1);
    assertThat(exhausted.count(key)).isEqualTo(1);
    assertThat(exhausted.exhaustedMechanismSignatures()).containsExactly("mechanism");
    ZeroGainState recovered = exhausted.record(key, true, 1);
    assertThat(recovered.count(key)).isZero();
    assertThat(recovered.exhaustedMechanismSignatures()).isEmpty();
    assertThatThrownBy(() -> initial.record(key, false, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ZeroGainState(Map.of(), -1, Set.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
    Map<TargetMechanismKey, Integer> nullValue = new HashMap<>();
    nullValue.put(key, null);
    assertThatThrownBy(() -> new ZeroGainState(nullValue, 0, Set.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new ZeroGainState(exhausted.consecutiveZeroGain(), 1, Set.of("mechanism"), "forged"))
        .isInstanceOf(IllegalArgumentException.class);

    Map<BudgetBucket, BudgetUsageTotals> legacyBuckets = new HashMap<>();
    legacyBuckets.put(BudgetBucket.DEPTH, null);
    BudgetStateSnapshot snapshot =
        new BudgetStateSnapshot(
            "run",
            "authority",
            "epoch",
            0,
            "config",
            "pricing",
            0,
            null,
            null,
            legacyBuckets,
            null,
            null,
            null,
            null);
    assertThat(snapshot.committedUsage()).isEqualTo(BudgetUsageTotals.zero());
    assertThat(snapshot.pathStats()).isEmpty();
    assertThat(
            new BudgetStateSnapshot(
                "run",
                "authority",
                "epoch",
                0,
                "config",
                "pricing",
                0,
                snapshot.committedUsage(),
                snapshot.reservedUsage(),
                snapshot.bucketUsage(),
                snapshot.finishReserve(),
                snapshot.pathStats(),
                snapshot.zeroGainState(),
                snapshot.snapshotHash()))
        .isEqualTo(snapshot);
    assertThatThrownBy(
            () ->
                new BudgetStateSnapshot(
                    "run", "authority", "epoch", -1, "config", "pricing", 0,
                    null, null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new BudgetStateSnapshot(
                    "run", "authority", "epoch", 0, "config", "pricing", -1,
                    null, null, null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new BudgetStateSnapshot(
                    "run", "authority", "epoch", 0, "config", "pricing", 0,
                    null, null, null, null, null, null, "forged"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static CertifiedGainReceipt receipt(
      int claims,
      int facts,
      int refuted,
      int closed,
      double debtBefore,
      double debtAfter,
      int checkpoints,
      int strategies,
      String hash) {
    return new CertifiedGainReceipt(
        "epoch", "before", "after", claims, facts, refuted, closed, debtBefore, debtAfter,
        checkpoints, strategies, false, hash);
  }

  private static CertifiedGainReceipt receiptWithInvalidCounter(int index) {
    int[] values = new int[6];
    values[index] = -1;
    return receipt(values[0], values[1], values[2], values[3], 0.0d, 0.0d, values[4], values[5], null);
  }

  private static BudgetEvidencePolicy policy(boolean allowStrategyRepair) {
    return new BudgetEvidencePolicy(
        true, 1, 1, 1, allowStrategyRepair, 0.04d, 0.55d, 0.4d, 0.1d, 0.1d,
        0.45d, 0.05d, 0.3d, 0.12d, 0.5d, 0.15d);
  }

  private static BudgetEvidencePolicy policyWithInvalidRepairLimit(int index) {
    int[] values = {1, 1, 1};
    values[index] = -1;
    return new BudgetEvidencePolicy(
        true, values[0], values[1], values[2], false, 0.04d, 0.55d, 0.4d, 0.1d,
        0.1d, 0.45d, 0.05d, 0.3d, 0.12d, 0.5d, 0.15d);
  }

  private static BudgetEvidencePolicy policyWithThreshold(double threshold) {
    return new BudgetEvidencePolicy(
        true, 1, 1, 1, false, threshold, 0.55d, 0.4d, 0.1d, 0.1d, 0.45d, 0.05d,
        0.3d, 0.12d, 0.5d, 0.15d);
  }

  private static BudgetEvidencePolicy policyWithPenalty(double penalty) {
    return new BudgetEvidencePolicy(
        true, 1, 1, 1, false, 0.04d, 0.55d, 0.4d, 0.1d, 0.1d, 0.45d, 0.05d,
        penalty, 0.12d, 0.5d, 0.15d);
  }

  private static PathBudgetStats path(
      AttemptEvidence.FailureClass failure, int repairs, boolean verified, String verdict) {
    return new PathBudgetStats(
        "strategy", "route-" + failure, "attempt", false, verified, 0.8d, 0.4d, 0.3d,
        0.2d, 0.7d, verdict, failure, failure == AttemptEvidence.FailureClass.NONE ? 0.0d : 1.0d,
        failure == AttemptEvidence.FailureClass.NONE ? 0 : 1, repairs, 1, 0, 0,
        BigDecimal.ZERO, true, "mechanism");
  }

  private static ActionCostEstimator.Profile invalidProfile(int index) {
    int[] counts = {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
    long[] tokens = {1, 1, 1, 1, 1, 1, 1, 1};
    if (index < counts.length) {
      counts[index] = index < 2 ? 0 : -1;
    } else {
      tokens[index - counts.length] = -1;
    }
    return new ActionCostEstimator.Profile(
        counts[0], counts[1], true, counts[2], counts[3], counts[4], counts[5], counts[6],
        counts[7], counts[8], counts[9], counts[10], counts[11], counts[12], tokens[0],
        tokens[1], tokens[2], tokens[3], tokens[4], tokens[5], tokens[6], tokens[7]);
  }

  private static StageTokenEnvelopeResolver.Request invalidStageRequest(int index) {
    long[] values = {1, 2, 2, 2, 2, 2, 2, 2, 0};
    values[index] = index >= 1 && index <= 4 ? 0 : -1;
    return new StageTokenEnvelopeResolver.Request(
        values[0], values[1], values[2], values[3], values[4], values[5], values[6],
        values[7], values[8]);
  }

  private static BudgetResourceVector invalidVector(int index) {
    long[] values = {0, 0, 0, 0};
    values[index] = -1;
    return new BudgetResourceVector(values[0], values[1], values[2], values[3], BigDecimal.ZERO);
  }

  private static BudgetResourceVector vector(
      long calls, long input, long output, long total, String cost) {
    return new BudgetResourceVector(calls, input, output, total, new BigDecimal(cost));
  }

  private static PricingSnapshot pricing(
      PricingSnapshot.BillingMode mode, String input, String output, String hash) {
    return new PricingSnapshot(
        "provider", "model", new BigDecimal(input), new BigDecimal(output), mode, "config", hash);
  }
}
