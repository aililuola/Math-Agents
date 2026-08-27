package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateStatus;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightPlanCompiler;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPreflightBindingAuthorityRecoveryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void unboundRegisteredRequestsCannotAcquireBindingsOrTriggerProviderPreflight()
      throws Exception {
    List<StrategyCard> candidates =
        DesktopStrategyPortfolioTestHarness.fourIndependent("unbound").stream()
            .map(strategy -> withRegisteredRequest(strategy, false))
            .toList();

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temporaryDirectory.resolve("unbound"), "unbound-preflight")) {
      harness.freeze();
      harness.setStrategies(candidates);
      harness.generateAndAdmit();

      assertThat(harness.preflightPlanProviderCalls()).isZero();
      assertThat(harness.preflightExecutionCount()).isZero();
      assertThat(harness.admittedStrategies()).hasSize(4);
      assertThat(harness.routeStrategyIds()).hasSize(4);
    }
  }

  @Test
  void unauthorizedModelRemappingRejectsOnlyCandidatesAndFeedsOneShotReplenishment()
      throws Exception {
    List<StrategyCard> invalid =
        DesktopStrategyPortfolioTestHarness.fourIndependent("preflight-invalid").stream()
            .map(strategy -> withRegisteredRequest(strategy, true))
            .toList();
    List<StrategyCard> valid =
        DesktopStrategyPortfolioTestHarness.fourIndependent("preflight-valid");
    Map<String, StrategyPreflightPlan> unauthorized = new LinkedHashMap<>();
    StrategyPreflightPlanCompiler compiler = new StrategyPreflightPlanCompiler();
    for (StrategyCard strategy : invalid) {
      StrategyPreflightPlan expected =
          compiler.compile(DesktopStrategyPortfolioTestHarness.PROBLEM_HASH, strategy);
      CriticalClaimPreflightPlan claim = expected.claimPlans().getFirst();
      unauthorized.put(
          strategy.strategyId(),
          new StrategyPreflightPlan(
              expected.problemHash(),
              expected.strategyId(),
              List.of(
                  new CriticalClaimPreflightPlan(
                      claim.claimId(), "", claim.evidenceRefs(), List.of()))));
    }

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temporaryDirectory.resolve("mismatch"),
            "preflight-mismatch-replenishment",
            List.of(
                new StrategySet("Invalid remappings.", List.of(), invalid),
                new StrategySet("Valid replacements.", List.of(), valid)),
            unauthorized)) {
      harness.freeze();
      harness.generateAndAdmit();

      long rejected =
          harness.candidates().snapshot().records().values().stream()
              .filter(record -> record.strategyId().startsWith("preflight-invalid-"))
              .filter(record -> record.status() == StrategyCandidateStatus.REJECTED_INVALID)
              .count();
      String replenishmentPrompt =
          harness.providerRequests().stream()
              .filter(
                  request ->
                      request.messages().stream()
                          .anyMatch(
                              message ->
                                  message
                                      .content()
                                      .contains(
                                          "\"generation_mode\":\"portfolio_gap_replenishment\"")))
              .findFirst()
              .orElseThrow()
              .messages()
              .getLast()
              .content();
      long invalidRouteLeaks =
          harness.routeStrategyIds().stream()
              .filter(id -> id.startsWith("preflight-invalid-"))
              .count();

      assertThat(rejected).isEqualTo(invalid.size());
      assertThat(replenishmentPrompt)
          .contains("INVALID_STRATEGY_PREFLIGHT_CONTRACT")
          .contains("server-authorized claim binding");
      assertThat(harness.admittedStrategies())
          .extracting(StrategyCard::strategyId)
          .allMatch(id -> id.startsWith("preflight-valid-"));
      assertThat(invalidRouteLeaks).isZero();

      System.out.println("PREFLIGHT BINDING AUTHORITY RECOVERY DIAGNOSTIC");
      System.out.println("UNAUTHORIZED_MODEL_REMAPPINGS=" + invalid.size());
      System.out.println("CANDIDATE_CONTRACT_REJECTIONS=" + rejected);
      System.out.println("CAMPAIGN_ABORTS=0");
      System.out.println("REPLENISHMENT_REQUESTS=" + harness.replenishmentProviderCalls());
      System.out.println("VALID_REPLACEMENT_ADMISSIONS=" + harness.admittedStrategies().size());
      System.out.println("INVALID_ROUTE_LEAKS=" + invalidRouteLeaks);
      System.out.println("RESULT=PASS");
    }
  }

  private static StrategyCard withRegisteredRequest(StrategyCard source, boolean bindClaim) {
    ToolRequest request =
        new ToolRequest(
            JsonNodeFactory.instance.objectNode(),
            JsonNodeFactory.instance.objectNode(),
            "bounded_integer_search",
            2,
            "Bounded falsification of the declared critical claim.",
            source.strategyId() + "-check");
    CriticalClaim claim = source.criticalClaims().getFirst();
    CriticalClaim updated =
        new CriticalClaim(
            claim.claimId(),
            bindClaim ? List.of(request.requestId()) : List.of(),
            claim.falsificationTest(),
            claim.necessity(),
            request.kind(),
            claim.statement(),
            claim.status());
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        List.of(request),
        source.calculationEvidenceRefs(),
        source.computationHints(),
        source.coreIdea(),
        List.of(updated),
        source.estimatedCost(),
        source.estimatedSuccess(),
        source.expectedLemmas(),
        source.falsificationTest(),
        source.independenceBasis(),
        source.inspirationProposalId(),
        source.keyOriginalStep(),
        source.parentStrategyIds(),
        source.prerequisites(),
        source.strategyId(),
        source.tags(),
        source.title(),
        source.mechanismOperations(),
        source.criticalClaimContextBindings());
  }
}
