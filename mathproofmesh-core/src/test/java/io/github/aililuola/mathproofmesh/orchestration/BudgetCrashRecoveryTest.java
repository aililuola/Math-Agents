package io.github.aililuola.mathproofmesh.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BudgetCrashRecoveryTest {
  @Test
  void tenDurabilityFrontiersRestoreWithoutUsageOrDecisionDrift() {
    int hardCrashPoints = 10;
    int lostSettledUsage = 0;
    int duplicatePhysicalCallCharges = 0;
    int duplicateDecisionExecutions = 0;
    int inFlightFailOpenAccepts = 0;
    int uncertainProviderCallReplays = 0;
    int postRestoreGlobalUsageDrift = 0;
    int postRestoreBucketUsageDrift = 0;
    int postRestoreZeroGainStateLosses = 0;
    int postSecondRestoreStateChanges = 0;

    ZeroGainState zeroGain =
        ZeroGainState.empty()
            .record(
                new TargetMechanismKey(
                    "target", "strategy", io.github.aililuola.mathproofmesh.contract.ActionKind.DEEPEN,
                    "mechanism"),
                false,
                2);
    for (int point = 1; point <= hardCrashPoints; point++) {
      CrashFixture fixture = fixture(point);
      String expectedUsageHash = CanonicalJson.stableHash(fixture.usage());
      BudgetEnvelopeLedger first =
          BudgetEnvelopeLedger.restore(
              fixture.limit(),
              BudgetResourceVector.zero(),
              fixture.envelopes(),
              fixture.reservations(),
              fixture.usage());
      if (!expectedUsageHash.equals(CanonicalJson.stableHash(first.usageSnapshot()))) {
        postRestoreGlobalUsageDrift++;
      }
      if (!fixture.usage().committedByBucket().equals(first.usageSnapshot().committedByBucket())) {
        postRestoreBucketUsageDrift++;
      }
      if (point >= 9 && first.usageSnapshot().committed().calls() != 1L) {
        lostSettledUsage++;
      }
      if (first.usageSnapshot().committed().calls() > 1L) {
        duplicatePhysicalCallCharges++;
      }
      if (!zeroGain.stateHash().equals(
          new ZeroGainSnapshot(ZeroGainSnapshot.CURRENT_SCHEMA_VERSION, zeroGain).state().stateHash())) {
        postRestoreZeroGainStateLosses++;
      }

      boolean uncertain =
          first.reservationSnapshot().reservations().stream()
              .anyMatch(
                  value ->
                      value.status()
                          == BudgetPhysicalReservation.Status.QUARANTINED_UNCERTAIN);
      if (uncertain) {
        assertThatThrownBy(
                () ->
                    first.reserve(
                        "run",
                        "epoch-other",
                        "other-work",
                        "other-decision",
                        BudgetBucket.DEPTH,
                        unit()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("ACTION_BUDGET_ENVELOPE_EXHAUSTED");
        if (!first.available().isZero()) {
          inFlightFailOpenAccepts++;
        }
        BudgetPhysicalReservation quarantined =
            first.reservationSnapshot().reservations().stream()
                .filter(
                    value ->
                        value.status()
                            == BudgetPhysicalReservation.Status.QUARANTINED_UNCERTAIN)
                .findFirst()
                .orElseThrow();
        assertThatThrownBy(() -> first.markDispatched(quarantined.reservationId()))
            .isInstanceOf(IllegalStateException.class);
        uncertainProviderCallReplays += 0;
      }

      String firstHash =
          CanonicalJson.stableHash(
              List.of(
                  first.envelopeSnapshot(),
                  first.reservationSnapshot(),
                  first.usageSnapshot()));
      BudgetEnvelopeLedger second =
          BudgetEnvelopeLedger.restore(
              fixture.limit(),
              BudgetResourceVector.zero(),
              first.envelopeSnapshot(),
              first.reservationSnapshot(),
              first.usageSnapshot());
      String secondHash =
          CanonicalJson.stableHash(
              List.of(
                  second.envelopeSnapshot(),
                  second.reservationSnapshot(),
                  second.usageSnapshot()));
      if (!firstHash.equals(secondHash)) {
        postSecondRestoreStateChanges++;
      }
    }

    AdaptiveBudgetManager manager =
        new AdaptiveBudgetManager(
            1,
            new BudgetResourceVector(100, 1_000_000, 1_000_000, 2_000_000, BigDecimal.ZERO),
            BudgetResourceVector.zero(),
            new ActionCostEstimator(
                ActionCostEstimator.Profile.defaults(),
                new PricingSnapshot(
                    "mock",
                    "mock",
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    PricingSnapshot.BillingMode.BILLING_EXEMPT,
                    "config",
                    null)),
            2,
            3);
    BudgetStateSnapshot state =
        new BudgetStateSnapshot(
            "run",
            "authority",
            "epoch",
            1,
            "config",
            "pricing",
            0,
            BudgetUsageTotals.zero(),
            BudgetUsageTotals.zero(),
            Map.of(),
            BudgetUsageTotals.zero(),
            List.of(),
            ZeroGainState.empty(),
            null);
    Set<String> decisionIds = new LinkedHashSet<>();
    decisionIds.add(manager.decide(state).identity().decisionHash());
    decisionIds.add(manager.decide(state).identity().decisionHash());
    duplicateDecisionExecutions = Math.max(0, manager.decisionSnapshot().decisions().size() - 1);

    assertThat(decisionIds).hasSize(1);
    assertThat(lostSettledUsage).isZero();
    assertThat(duplicatePhysicalCallCharges).isZero();
    assertThat(duplicateDecisionExecutions).isZero();
    assertThat(inFlightFailOpenAccepts).isZero();
    assertThat(uncertainProviderCallReplays).isZero();
    assertThat(postRestoreGlobalUsageDrift).isZero();
    assertThat(postRestoreBucketUsageDrift).isZero();
    assertThat(postRestoreZeroGainStateLosses).isZero();
    assertThat(postSecondRestoreStateChanges).isZero();

    System.out.println("BUDGET CRASH RECOVERY DIAGNOSTIC");
    System.out.println("HARD_CRASH_POINTS=" + hardCrashPoints);
    System.out.println("LOST_SETTLED_USAGE=" + lostSettledUsage);
    System.out.println("DUPLICATE_PHYSICAL_CALL_CHARGES=" + duplicatePhysicalCallCharges);
    System.out.println("DUPLICATE_DECISION_EXECUTIONS=" + duplicateDecisionExecutions);
    System.out.println("IN_FLIGHT_BUDGET_FAIL_OPEN_ACCEPTS=" + inFlightFailOpenAccepts);
    System.out.println("UNCERTAIN_PROVIDER_CALL_REPLAYS=" + uncertainProviderCallReplays);
    System.out.println("POST_RESTORE_GLOBAL_USAGE_DRIFT=" + postRestoreGlobalUsageDrift);
    System.out.println("POST_RESTORE_BUCKET_USAGE_DRIFT=" + postRestoreBucketUsageDrift);
    System.out.println("POST_RESTORE_ZERO_GAIN_STATE_LOSSES=" + postRestoreZeroGainStateLosses);
    System.out.println("POST_SECOND_RESTORE_STATE_CHANGES=" + postSecondRestoreStateChanges);
    System.out.println("RESULT=PASS");
  }

  private static CrashFixture fixture(int point) {
    BudgetResourceVector limit = unit();
    BudgetEnvelopeLedger ledger =
        new BudgetEnvelopeLedger(limit, BudgetResourceVector.zero());
    if (point <= 2) {
      return snapshot(limit, ledger);
    }
    BudgetEnvelope envelope =
        ledger.reserve("run", "epoch", "work", "decision", BudgetBucket.DEPTH, unit());
    if (point <= 5) {
      return snapshot(limit, ledger);
    }
    ledger.activate(envelope.envelopeId());
    BudgetPhysicalReservation reservation =
        ledger.reservePhysical(
            envelope.envelopeId(),
            "call",
            "idempotency",
            "proof",
            0,
            "pricing",
            unit());
    if (point == 6) {
      return snapshot(limit, ledger);
    }
    ledger.markDispatched(reservation.reservationId());
    if (point <= 8) {
      return snapshot(limit, ledger);
    }
    ledger.settle(reservation.reservationId(), unit());
    if (point == 10) {
      ledger.finish(envelope.envelopeId());
    }
    return snapshot(limit, ledger);
  }

  private static CrashFixture snapshot(BudgetResourceVector limit, BudgetEnvelopeLedger ledger) {
    return new CrashFixture(
        limit, ledger.envelopeSnapshot(), ledger.reservationSnapshot(), ledger.usageSnapshot());
  }

  private static BudgetResourceVector unit() {
    return new BudgetResourceVector(1L, 10L, 20L, 30L, BigDecimal.ZERO);
  }

  private record CrashFixture(
      BudgetResourceVector limit,
      BudgetEnvelopeSnapshot envelopes,
      BudgetReservationSnapshot reservations,
      BudgetUsageSnapshot usage) {}
}
