package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyPreflightPlanCompilerTest {
  @Test
  void bindsOnlyExplicitClaimReferencesToCompatibleTypedRequests() {
    ToolRequest integer = request("integer-request", "bounded_integer_search");
    ToolRequest graph = request("graph-request", "graph_certificate");
    StrategyCard strategy =
        strategy(
            List.of(integer, graph),
            List.of(
                claim("integer-claim", List.of("integer-request", "graph-request"), null),
                claim(
                    "graph-claim",
                    List.of("integer-request", "graph-request"),
                    "graph_certificate"),
                claim("blank-preference", List.of("integer-request"), ""),
                claim("missing-reference", List.of("missing-request"), null)));
    StrategyPreflightPlanCompiler compiler = new StrategyPreflightPlanCompiler();

    var plan = compiler.compile("problem-hash", strategy);

    assertThat(plan.claimPlans())
        .extracting(CriticalClaimPreflightPlan::computationContractId)
        .containsExactly(
            "integer-request", "graph-request", "integer-request", "");
    assertThat(compiler.registeredContractIds(strategy))
        .containsExactlyInAnyOrder("integer-request", "graph-request");
    assertThat(compiler.request(strategy, plan.claimPlans().getFirst()))
        .isEqualTo(integer);
    assertThat(
            compiler.request(
                strategy,
                new CriticalClaimPreflightPlan(
                    "missing", "", List.of(), List.of())))
        .isNull();
    assertThat(
            compiler.request(
                strategy,
                new CriticalClaimPreflightPlan(
                    "missing", "unknown-request", List.of(), List.of())))
        .isNull();
  }

  @Test
  void duplicateTypedRequestIdsFailClosedBeforePlanning() {
    ToolRequest first = request("duplicate", "bounded_integer_search");
    ToolRequest second = request("duplicate", "graph_certificate");
    StrategyCard strategy =
        strategy(
            List.of(first, second),
            List.of(claim("claim", List.of("duplicate"), null)));

    assertThatThrownBy(
            () -> new StrategyPreflightPlanCompiler().compile("problem-hash", strategy))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
  }

  private static ToolRequest request(String id, String kind) {
    return new ToolRequest(
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        kind,
        4,
        "bounded deterministic check",
        id);
  }

  private static CriticalClaim claim(
      String id, List<String> evidenceRefs, String preferredTool) {
    return new CriticalClaim(
        id,
        evidenceRefs,
        "Run the declared bounded contract.",
        "required",
        preferredTool,
        "The typed claim " + id + " holds.",
        "needs_check");
  }

  private static StrategyCard strategy(
      List<ToolRequest> requests, List<CriticalClaim> claims) {
    StrategyCard source =
        StrategyDiversityTestFixtures.strategy(
            "typed-strategy",
            "Typed strategy",
            "Use a typed bounded check",
            "The load-bearing claim holds.",
            0.7d);
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        requests,
        source.calculationEvidenceRefs(),
        source.computationHints(),
        source.coreIdea(),
        claims,
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
        source.title());
  }
}
