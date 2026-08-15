package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopRegisteredContractPreflightExecutionTest {
  @TempDir Path temp;

  @Test
  void exactRegisteredBoundedContractRefutesRequiredClaimBeforeAdmission() throws Exception {
    StrategyCard candidate = falsifiableStrategy();
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "registered-preflight")) {
      harness.freeze();
      harness.setStrategies(List.of(candidate));
      harness.generateAndAdmit();

      var report = harness.preflights().find(candidate.strategyId()).orElseThrow();
      long refuted =
          report.claims().stream()
              .filter(
                  claim ->
                      claim.status()
                          == io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimPreflightStatus.VERIFIED_REFUTED)
              .count();
      int plans = harness.preflightPlanCount();
      int executions = harness.preflightExecutionCount();
      long planCalls = harness.preflightPlanProviderCalls();
      System.out.println("PREFLIGHT_PLANS_GENERATED=" + plans);
      System.out.println("PREFLIGHT_PLAN_PROVIDER_CALLS=" + planCalls);
      System.out.println("REGISTERED_CONTRACT_EXECUTIONS=" + executions);
      System.out.println("VERIFIED_COUNTEREXAMPLES_FOUND=" + refuted);
      System.out.println("REFUTED_STRATEGY_ADMISSIONS=" + harness.admittedStrategies().size());
      assertThat(plans).isEqualTo(1);
      assertThat(planCalls).isEqualTo(1L);
      assertThat(executions).isEqualTo(1);
      assertThat(refuted).isEqualTo(1);
      assertThat(harness.admittedStrategies()).isEmpty();
    }
  }

  static StrategyCard falsifiableStrategy() {
    return registeredIntegerStrategy(
        "registered-preflight-strategy",
        "For every integer x in {0,1}, x is strictly less than 1.",
        "lt");
  }

  static StrategyCard registeredIntegerStrategy(
      String strategyId, String claim, String relation) {
    ObjectNode domains = JsonNodeFactory.instance.objectNode();
    domains.putObject("x").put("min", 0).put("max", 1);
    ObjectNode target = JsonNodeFactory.instance.objectNode();
    target.put("lhs", "x").put("rhs", "1").put("relation", relation);
    ObjectNode arguments = JsonNodeFactory.instance.objectNode();
    arguments.set("target", target);
    arguments.putArray("constraints");
    ToolRequest request =
        new ToolRequest(
            arguments,
            domains,
            "bounded_integer_search",
            2,
            "falsify the required finite-domain claim",
            "finite-domain-counterexample");
    CriticalClaim critical =
        new CriticalClaim(
            "finite-domain-required",
            List.of(request.requestId()),
            "Enumerate both declared integer values.",
            "required",
            "bounded_integer_search",
            claim,
            "needs_check");
    return DesktopStrategyMetadataTestSupport.complete(
        new StrategyCard(
        null,
        "Use the declared finite-domain assertion as the load-bearing bridge.",
        List.of(request),
        List.of(),
        List.of(),
        "Reduce the proof to the stated finite-domain assertion",
        List.of(critical),
        0.1d,
        0.99d,
        List.of(claim),
        "Run the registered exhaustive integer contract.",
        "A separately testable finite-set route.",
        null,
        null,
        List.of(),
        List.of(),
        strategyId,
            List.of("finite-set"),
            "Finite-domain bridge"));
  }
}
