package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StrategyDiversityStateOwnerTest {
  @Test
  void candidateLedgerIsIdempotentMonotonicAndRestorable() {
    StrategyCandidateLedger ledger = new StrategyCandidateLedger();

    StrategyCandidateRecord captured = ledger.capture("episode", "candidate", 0, false);
    assertThat(ledger.capture("ignored", "candidate", 99, true)).isSameAs(captured);
    assertThat(ledger.capture("episode", "replenished", 1, true).status())
        .isEqualTo(StrategyCandidateStatus.REPLENISHMENT_CANDIDATE);
    assertThat(ledger.find("missing")).isEmpty();

    StrategyCandidateRecord preflighted =
        ledger.transition(
            "candidate", StrategyCandidateStatus.PREFLIGHTED, " signature ", "", 0.5d, null);
    assertThat(preflighted.mechanismSignatureHash()).isEqualTo("signature");
    assertThat(preflighted.preflightReportHash()).isEmpty();
    assertThat(preflighted.detail()).isEmpty();

    StrategyCandidateRecord selected =
        ledger.transition(
            "candidate", StrategyCandidateStatus.SELECTED, null, " report ", null, "done");
    assertThat(selected.calibratedScore()).isEqualTo(0.5d);
    assertThat(selected.preflightReportHash()).isEqualTo("report");
    assertThat(
            ledger.transition(
                "candidate", StrategyCandidateStatus.SELECTED, "", "", null, "replayed"))
        .extracting(StrategyCandidateRecord::status)
        .isEqualTo(StrategyCandidateStatus.SELECTED);

    assertThatThrownBy(
            () ->
                ledger.transition(
                    "candidate",
                    StrategyCandidateStatus.NOT_SELECTED,
                    "",
                    "",
                    null,
                    "illegal regression"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () ->
                ledger.transition(
                    "missing", StrategyCandidateStatus.PREFLIGHTED, "", "", null, ""))
        .isInstanceOf(IllegalArgumentException.class);

    String hash = ledger.ledgerHash();
    StrategyCandidateLedger restored = StrategyCandidateLedger.restore(ledger.snapshot());
    assertThat(restored.ledgerHash()).isEqualTo(hash);
    assertThat(StrategyCandidateLedger.restore(null).snapshot().records()).isEmpty();
  }

  @Test
  void preflightAndPortfolioRegistriesRejectConflictingReplays() {
    StrategyCard strategy =
        StrategyDiversityTestFixtures.strategy(
            "candidate", "Candidate", "Edge deletion", "The bridge holds.", 0.5d);
    StrategyPreflightReport report =
        StrategyDiversityTestFixtures.report(strategy, CriticalClaimPreflightStatus.UNKNOWN);
    StrategyPreflightReport conflicting =
        new StrategyPreflightReport(
            report.strategyId(),
            report.problemHash(),
            report.claims(),
            report.hardRejected(),
            report.requiresRegeneration(),
            report.requiredClaimEvidenceCoverage(),
            report.unresolvedRequiredClaimKeys(),
            "conflicting-report-hash");
    StrategyPreflightRegistry preflights = new StrategyPreflightRegistry();

    assertThat(preflights.find(strategy.strategyId())).isEmpty();
    assertThat(preflights.record(report)).isSameAs(report);
    assertThat(preflights.record(report)).isSameAs(report);
    assertThatThrownBy(() -> preflights.record(conflicting))
        .isInstanceOf(IllegalStateException.class);
    assertThat(StrategyPreflightRegistry.restore(preflights.snapshot()).registryHash())
        .isEqualTo(preflights.registryHash());
    assertThat(StrategyPreflightRegistry.restore(null).snapshot().reports()).isEmpty();

    StrategyPortfolioDecision decision = decision("decision-hash");
    StrategyPortfolioDecision conflictingDecision = decision("other-decision-hash");
    StrategyPortfolioApplyReceipt receipt =
        new StrategyPortfolioApplyReceipt(
            "receipt", "plan", List.of("candidate"), List.of("route"), "active-hash");
    StrategyPortfolioRegistry portfolios = new StrategyPortfolioRegistry();

    assertThat(portfolios.find("episode")).isEmpty();
    assertThat(portfolios.receipt("episode")).isEmpty();
    assertThatThrownBy(() -> portfolios.recordReceipt("episode", receipt))
        .isInstanceOf(IllegalStateException.class);
    assertThat(portfolios.record(decision)).isSameAs(decision);
    assertThat(portfolios.record(decision)).isSameAs(decision);
    assertThatThrownBy(() -> portfolios.record(conflictingDecision))
        .isInstanceOf(IllegalStateException.class);
    assertThat(portfolios.recordReceipt("episode", receipt)).isSameAs(receipt);
    assertThat(portfolios.recordReceipt("episode", receipt)).isSameAs(receipt);
    assertThatThrownBy(
            () ->
                portfolios.recordReceipt(
                    "episode",
                    new StrategyPortfolioApplyReceipt(
                        "receipt-2",
                        "plan",
                        List.of("candidate"),
                        List.of("route"),
                        "active-hash")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(StrategyPortfolioRegistry.restore(portfolios.snapshot()).registryHash())
        .isEqualTo(portfolios.registryHash());
    assertThat(StrategyPortfolioRegistry.restore(null).snapshot().decisions()).isEmpty();
  }

  @Test
  void replenishmentLedgerAllowsExactlyOneCompletedProviderCall() {
    PortfolioReplenishmentLedger ledger = new PortfolioReplenishmentLedger();

    assertThat(ledger.mayRequest("episode")).isTrue();
    assertThatThrownBy(() -> ledger.complete("missing", List.of()))
        .isInstanceOf(IllegalStateException.class);
    ledger.begin("episode", " request-hash ");
    assertThat(ledger.mayRequest("episode")).isFalse();
    assertThatThrownBy(() -> ledger.begin("episode", "again"))
        .isInstanceOf(IllegalStateException.class);
    ledger.complete("episode", null);
    assertThat(ledger.find("episode")).get().extracting(record -> record.completed()).isEqualTo(true);
    assertThatThrownBy(() -> ledger.complete("episode", List.of("late")))
        .isInstanceOf(IllegalStateException.class);

    String hash = ledger.ledgerHash();
    assertThat(PortfolioReplenishmentLedger.restore(ledger.snapshot()).ledgerHash()).isEqualTo(hash);
    assertThat(PortfolioReplenishmentLedger.restore(null).snapshot().episodes()).isEmpty();

    assertThatThrownBy(
            () ->
                new PortfolioReplenishmentSnapshot.ReplenishmentRecord(
                    "episode", true, false, -1, List.of(), "hash"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PortfolioReplenishmentSnapshot.ReplenishmentRecord(
                    "episode", true, false, 2, List.of(), "hash"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PortfolioReplenishmentSnapshot.ReplenishmentRecord(
                    "episode", false, true, 0, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PortfolioReplenishmentSnapshot(1, Map.of(), -1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void immutableApplyContractsNormalizeOptionalCollectionsAndValidateIdentity() {
    StrategyPortfolioApplyPlan plan =
        new StrategyPortfolioApplyPlan(
            "plan", "episode", "problem", "root", null, "decision");
    StrategyPortfolioApplyReceipt receipt =
        new StrategyPortfolioApplyReceipt("receipt", "plan", null, null, "active");

    assertThat(plan.selectedStrategyIds()).isEmpty();
    assertThat(receipt.admittedStrategyIds()).isEmpty();
    assertThat(receipt.createdRouteIds()).isEmpty();
    assertThatThrownBy(
            () ->
                new StrategyPortfolioApplyPlan(
                    " ", "episode", "problem", "root", List.of(), "decision"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new StrategyPortfolioApplyReceipt("receipt", " ", null, null, "active"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StrategyCandidateRecord(
                    "candidate",
                    "episode",
                    StrategyCandidateStatus.CAPTURED,
                    null,
                    null,
                    Double.NaN,
                    null,
                    0,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new StrategyCandidateRecord(
                    "candidate",
                    "episode",
                    StrategyCandidateStatus.CAPTURED,
                    null,
                    null,
                    null,
                    null,
                    -1,
                    1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StrategyCandidateSnapshot(1, null, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StrategyPreflightSnapshot(1, null, -1L))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new StrategyPortfolioSnapshot(1, null, null, -1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static StrategyPortfolioDecision decision(String hash) {
    return new StrategyPortfolioDecision(
        "episode", List.of("candidate"), Map.of(), 10.0d, true, hash, List.of());
  }
}
