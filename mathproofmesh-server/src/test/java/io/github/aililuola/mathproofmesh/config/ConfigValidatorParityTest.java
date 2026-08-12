package io.github.aililuola.mathproofmesh.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class ConfigValidatorParityTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final StrictYamlConfigLoader LOADER = new StrictYamlConfigLoader();
  private static final SystemConfig MINIMAL =
      LOADER.read(
          "agents:\n"
              + "  - id: mock-agent\n"
              + "    provider: mock\n"
              + "    model: mock-model\n");

  @Test
  void agentKeySourceAndReasoningValidatorMatchesPython() {
    rejects(
        MINIMAL.agents().getFirst(),
        AgentConfig.class,
        node -> node.put("provider", "openai_compatible"));
    rejects(
        MINIMAL.agents().getFirst(),
        AgentConfig.class,
        node -> {
          node.put("thinking_enabled", false);
          node.put("reasoning_effort", "high");
        });
  }

  @Test
  void agentUserIdValidatorMatchesPython() {
    rejects(
        MINIMAL.agents().getFirst(),
        AgentConfig.class,
        node -> node.put("user_id", "not allowed"));
  }

  @Test
  void budgetSharesAndPathValidatorMatchesPython() {
    rejects(
        BudgetConfig.defaults(),
        BudgetConfig.class,
        node -> node.put("breadth_share", 0.31d));
    rejects(
        BudgetConfig.defaults(),
        BudgetConfig.class,
        node -> node.put("initial_paths", 9));
    rejects(
        BudgetConfig.defaults(),
        BudgetConfig.class,
        node -> node.put("strategies_to_generate", 3));
  }

  @Test
  void schedulerStagnationValidatorMatchesPython() {
    rejects(
        SchedulerConfig.defaults(),
        SchedulerConfig.class,
        node -> node.put("global_no_progress_rounds_before_stop", 2));
  }

  @Test
  void brokerMatchBandValidatorMatchesPython() {
    rejects(
        BrokerConfig.defaults(),
        BrokerConfig.class,
        node -> node.put("ambiguous_match_low", 0.9d));
  }

  @Test
  void inspirationValidatorMatchesPython() {
    rejects(
        InspirationConfig.defaults(),
        InspirationConfig.class,
        node -> node.put("surprise_budget_min_calls", 33));
    rejects(
        InspirationConfig.defaults(),
        InspirationConfig.class,
        node -> node.put("proposals_enter_fact_memory", true));
  }

  @Test
  void capabilityRejectsSelfReportedConfidenceLikePython() {
    rejects(
        AgentCapabilityConfig.defaults(),
        AgentCapabilityConfig.class,
        node -> node.put("use_self_reported_confidence", true));
  }

  @Test
  void hierarchicalTopologyValidatorMatchesPython() {
    rejects(
        TopologyConfig.defaults(),
        TopologyConfig.class,
        node -> {
          node.put("mode", "hierarchical_sparse");
          ((ObjectNode) node.path("proof_graph")).put("mode", "off");
          ((ObjectNode) node.path("route_teams")).put("enabled", true);
        });
  }

  @Test
  void proofControlTopologyValidatorMatchesPython() {
    rejects(
        TopologyConfig.defaults(),
        TopologyConfig.class,
        node -> ((ObjectNode) node.path("proof_control")).put("mode", "shadow"));
    rejects(
        TopologyConfig.defaults(),
        TopologyConfig.class,
        node ->
            ((ObjectNode)
                    ((ObjectNode) node.path("proof_control"))
                        .path("falsification_fast_lane"))
                .put("auto_fact_promotion", true));
  }

  @Test
  void leanImageValidatorMatchesPython() {
    rejects(
        VerificationConfig.defaults(),
        VerificationConfig.class,
        node -> node.put("lean_sandbox_image", "lean:latest"));
  }

  @Test
  void explorationTierLegacyNormalizationMatchesPython() {
    SystemConfig config =
        LOADER.read(
            "agents:\n"
                + "  - id: mock\n"
                + "    provider: mock\n"
                + "    model: mock\n"
                + "deep_exploration_policy:\n"
                + "  partial_repair_max_output_tokens: 2048\n"
                + "  tiers:\n"
                + "    - output_tokens: 2048\n"
                + "      answer_reserve_tokens: 512\n"
                + "      wall_timeout_seconds: 1\n");

    assertEquals(512, config.deepExplorationPolicy().tiers().getFirst().artifactRecoveryTokens());
  }

  @Test
  void explorationTierInvariantMatchesPython() {
    rejects(
        new ExplorationTierPolicyConfig(2048, 512),
        ExplorationTierPolicyConfig.class,
        node -> node.put("artifact_recovery_tokens", 2048));
  }

  @Test
  void deepExplorationPolicyValidatorMatchesPython() {
    rejects(
        DeepExplorationPolicyConfig.defaults(),
        DeepExplorationPolicyConfig.class,
        node -> {
          ArrayNode tiers = (ArrayNode) node.path("tiers");
          ((ObjectNode) tiers.get(1)).put("output_tokens", 64000);
        });
    rejects(
        DeepExplorationPolicyConfig.defaults(),
        DeepExplorationPolicyConfig.class,
        node -> node.put("allow_parallel_distinct_signatures", false));
  }

  @Test
  void computationPolicyValidatorMatchesPython() {
    rejects(
        ComputationConfig.defaults(),
        ComputationConfig.class,
        node -> node.put("soft_experiments_per_path", 7));
    rejects(
        ComputationConfig.defaults(),
        ComputationConfig.class,
        node -> {
          node.put("sandboxed_python_enabled", true);
          node.put("sandbox_image", "python:latest");
        });
  }

  @Test
  void runtimeLegacyTimeFieldsAreDiscardedLikePython() {
    SystemConfig config =
        LOADER.read(
            "agents:\n"
                + "  - id: mock\n"
                + "    provider: mock\n"
                + "    model: mock\n"
                + "runtime:\n"
                + "  reasoning_only_abort_seconds: 1\n"
                + "  reasoning_only_min_characters: 2\n");

    assertEquals(8, config.runtime().maxParallelCalls());
  }

  @Test
  void runtimeStageOutputValidatorMatchesPython() {
    rejects(
        RuntimeConfig.defaults(),
        RuntimeConfig.class,
        node ->
            ((ObjectNode) node.path("stage_output_token_limits"))
                .put("bad-stage", 255));
  }

  @Test
  void runtimeThinkingModeValidatorMatchesPython() {
    rejects(
        RuntimeConfig.defaults(),
        RuntimeConfig.class,
        node -> ((ObjectNode) node.path("stage_thinking_modes")).put("", "high"));
  }

  @Test
  void runtimeHttpStatusValidatorAndNormalizationMatchPython() {
    rejects(
        RuntimeConfig.defaults(),
        RuntimeConfig.class,
        node -> {
          ArrayNode statuses = (ArrayNode) node.path("provider_terminal_http_statuses");
          statuses.removeAll();
          statuses.add(399);
        });
    ObjectNode node = JSON.valueToTree(RuntimeConfig.defaults());
    ArrayNode statuses = (ArrayNode) node.path("provider_terminal_http_statuses");
    statuses.removeAll();
    statuses.add(503);
    statuses.add(401);
    statuses.add(503);
    RuntimeConfig normalized = LOADER.bindValue(node, RuntimeConfig.class);
    assertEquals(java.util.List.of(401, 503), normalized.providerTerminalHttpStatuses());
  }

  @Test
  void runtimeExplorationTierValidatorMatchesPython() {
    rejects(
        RuntimeConfig.defaults(),
        RuntimeConfig.class,
        node -> {
          ArrayNode tiers = (ArrayNode) node.path("exploration_output_token_tiers");
          tiers.removeAll();
          tiers.add(96000);
          tiers.add(64000);
        });
  }

  @Test
  void systemAgentValidatorMatchesPython() {
    rejects(
        MINIMAL,
        SystemConfig.class,
        node -> ((ObjectNode) node.path("agents").get(0)).put("enabled", false));
  }

  @Test
  void activeHierarchyRequiresContinuationLikePython() {
    rejects(
        MINIMAL,
        SystemConfig.class,
        node -> {
          ObjectNode topology = (ObjectNode) node.path("topology");
          topology.put("mode", "hierarchical_sparse");
          ((ObjectNode) topology.path("proof_graph")).put("mode", "off");
          ((ObjectNode) topology.path("typed_communication")).put("enabled", true);
        });
  }

  @Test
  void hierarchicalInspirationProtectsFinalizationReserveLikePython() {
    rejects(
        MINIMAL,
        SystemConfig.class,
        node -> {
          ObjectNode topology = (ObjectNode) node.path("topology");
          topology.put("mode", "hierarchical_sparse");
          ((ObjectNode) topology.path("proof_graph")).put("mode", "off");
          ObjectNode inspiration = (ObjectNode) topology.path("inspiration");
          inspiration.put("enabled", true);
          inspiration.put("mode", "active");
          ((ObjectNode) node.path("continuation")).put("enabled", true);
          ((ObjectNode) node.path("budget")).put("max_total_calls", 12);
        });
  }

  private static <T extends ConfigModel> void rejects(
      T valid, Class<T> type, Consumer<ObjectNode> mutation) {
    ObjectNode node = JSON.valueToTree(valid);
    mutation.accept(node);
    assertThrows(ConfigValidationException.class, () -> LOADER.bindValue(node, type));
  }
}
