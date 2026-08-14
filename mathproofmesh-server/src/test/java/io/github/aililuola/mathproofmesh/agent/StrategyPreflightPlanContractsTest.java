package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import io.github.aililuola.mathproofmesh.contract.CriticalClaimPreflightPlan;
import io.github.aililuola.mathproofmesh.contract.StrategyPreflightPlan;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StrategyPreflightPlanContractsTest {
  @Test
  void acceptsOnlyRegisteredTypedPlansBoundToTheExactProblemAndClaim() {
    StrategyPreflightPlan plan =
        new StrategyPreflightPlan(
            "problem-hash",
            "strategy-a",
            List.of(
                new CriticalClaimPreflightPlan(
                    "claim-a",
                    "finite_graph_enumeration",
                    List.of("artifact://graph/input"),
                    List.of("input://graph/p4"))));

    assertThat(
            new StrategyPreflightPlanValidator()
                .validate(
                    plan,
                    "problem-hash",
                    "strategy-a",
                    Set.of("claim-a"),
                    Set.of("finite_graph_enumeration")))
        .isEqualTo(plan);
    assertThatThrownBy(
            () ->
                new StrategyPreflightPlanValidator()
                    .validate(
                        plan,
                        "other-problem",
                        "strategy-a",
                        Set.of("claim-a"),
                        Set.of("finite_graph_enumeration")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownToolsCodeDuplicateClaimsAndServerAuthorityFields() {
    StrategyPreflightPlan duplicate =
        new StrategyPreflightPlan(
            "problem-hash",
            "strategy-a",
            List.of(
                new CriticalClaimPreflightPlan("claim-a", "unknown", List.of(), List.of("def run(): pass")),
                new CriticalClaimPreflightPlan("claim-a", "unknown", List.of(), List.of())));
    StrategyPreflightPlanValidator validator = new StrategyPreflightPlanValidator();
    assertThatThrownBy(
            () ->
                validator.validate(
                    duplicate,
                    "problem-hash",
                    "strategy-a",
                    Set.of("claim-a"),
                    Set.of("registered")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ContractObjectMapper.read(
                    "{\"problem_hash\":\"problem-hash\",\"strategy_id\":\"strategy-a\","
                        + "\"claim_plans\":[],\"server_score\":0.99,\"mechanism_signature\":\"forged\"}",
                    StrategyPreflightPlan.class))
        .isInstanceOf(ContractValidationException.class);
  }
}
