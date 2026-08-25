package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.CheckpointedResearchEnvelope;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointedStrategyCandidateIsolationTest {
  @Test
  void oneInvalidQuantifierCannotDiscardTheOtherCompleteStrategies(@TempDir Path directory) {
    AtomicInteger calls = new AtomicInteger();
    try (var fixture =
        ResearchCheckpointRunnerTestSupport.fixture(
            directory,
            "strategy-candidate-isolation",
            request -> {
              assertThat(calls.incrementAndGet()).isEqualTo(1);
              return envelopeResponse(strategySet("forall", "positive"));
            },
            1)) {
      var result =
          fixture
              .runner()
              .callCheckpointed(
                  "strategy-candidate-isolation",
                  "strategy-generation",
                  "general",
                  strategyPrompt(),
                  fixture.pool().get("agent-a"),
                  "breadth",
                  true,
                  "high",
                  capture -> {});

      assertThat(calls).hasValue(1);
      assertThat(result.result().repaired()).isTrue();
      assertThat(result.result().value().strategies())
          .extracting(StrategyCard::strategyId)
          .containsExactly("S-valid");
    }
  }

  @Test
  void nestedRepairPromptExplainsQuantifierKindWithoutGuessing(@TempDir Path directory) {
    AtomicInteger calls = new AtomicInteger();
    List<String> repairPrompts = new ArrayList<>();
    try (var fixture =
        ResearchCheckpointRunnerTestSupport.fixture(
            directory,
            "strategy-quantifier-repair",
            request -> {
              if (calls.incrementAndGet() == 1) {
                return envelopeResponse(strategySet("positive", "negative"));
              }
              repairPrompts.add(request.messages().getLast().content());
              return ResearchCheckpointRunnerTestSupport.response(
                  ContractObjectMapper.write(
                      ContractObjectMapper.read(strategySet("forall", "exists"), StrategySet.class)),
                  com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                  "stop",
                  20);
            },
            1)) {
      var result =
          fixture
              .runner()
              .callCheckpointed(
                  "strategy-quantifier-repair",
                  "strategy-generation",
                  "general",
                  strategyPrompt(),
                  fixture.pool().get("agent-a"),
                  "breadth",
                  true,
                  "high",
                  capture -> {});

      assertThat(calls).hasValue(2);
      assertThat(result.result().value().strategies()).hasSize(2);
      assertThat(repairPrompts)
          .singleElement()
          .asString()
          .contains(
              "quantifiers[].kind accepts only forall, exists, or exists_unique",
              "never infer or guess a quantifier from positive or negative");
    }
  }

  private static CheckpointedPromptBundle<StrategySet> strategyPrompt() {
    PromptBundle<StrategySet> original =
        new PromptBundle<>(
            "strategy_generation",
            "Return one strict strategy set.",
            "public strategy context",
            StrategySet.class,
            0.0d,
            4_096,
            false,
            PromptJsonSchema.forType(StrategySet.class));
    return new ResearchCheckpointedPromptFactory().checkpoint(original);
  }

  private static io.github.aililuola.mathproofmesh.provider.LLMResponse envelopeResponse(
      JsonNode result) {
    CheckpointedResearchEnvelope envelope =
        new CheckpointedResearchEnvelope(
            null, ResearchFindingUpdateBatch.empty(), result);
    return ResearchCheckpointRunnerTestSupport.response(
        ContractObjectMapper.write(envelope),
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
        "stop",
        20);
  }

  private static JsonNode strategySet(String firstKind, String secondKind) {
    return ContractObjectMapper.parseTree(
        """
        {
          "coverage_notes":"Two complete candidate strategies are represented.",
          "strategies":[
            {
              "strategy_id":"S-valid",
              "title":"First route",
              "core_idea":"Prove the first route.",
              "bottleneck":"Establish its critical claim.",
              "estimated_success":0.7,
              "falsification_test":"Search for a bounded counterexample.",
              "independence_basis":"Uses the first exact mechanism.",
              "critical_claim_context_bindings":[{
                "claim_id":"S-valid-C1",
                "claim_blueprint_node_id":"@claim",
                "local_assumption_node_ids":[],
                "local_assumptions":[],
                "quantifiers":[{
                  "display_name":"p",
                  "domain":"primes",
                  "kind":"%s",
                  "order":1,
                  "restrictions":[],
                  "variable_id":"S-valid-p"
                }],
                "variable_bindings":[],
                "scope_limitations":[],
                "polarity":"positive"
              }]
            },
            {
              "strategy_id":"S-invalid",
              "title":"Second route",
              "core_idea":"Prove the second route.",
              "bottleneck":"Establish its critical claim.",
              "estimated_success":0.6,
              "falsification_test":"Search for a bounded counterexample.",
              "independence_basis":"Uses the second exact mechanism.",
              "critical_claim_context_bindings":[{
                "claim_id":"S-invalid-C1",
                "claim_blueprint_node_id":"@claim",
                "local_assumption_node_ids":[],
                "local_assumptions":[],
                "quantifiers":[{
                  "display_name":"q",
                  "domain":"primes",
                  "kind":"%s",
                  "order":1,
                  "restrictions":[],
                  "variable_id":"S-invalid-q"
                }],
                "variable_bindings":[],
                "scope_limitations":[],
                "polarity":"positive"
              }]
            }
          ]
        }
        """.formatted(firstKind, secondKind));
  }
}
