package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record SystemConfig(
    @JsonProperty(value = "system_name") String systemName,
    @JsonProperty(value = "agents", required = true) List<AgentConfig> agents,
    @JsonProperty(value = "budget") BudgetConfig budget,
    @JsonProperty(value = "scheduler") SchedulerConfig scheduler,
    @JsonProperty(value = "topology") TopologyConfig topology,
    @JsonProperty(value = "verification") VerificationConfig verification,
    @JsonProperty(value = "continuation") ContinuationConfig continuation,
    @JsonProperty(value = "deep_exploration_policy") DeepExplorationPolicyConfig deepExplorationPolicy,
    @JsonProperty(value = "computation") ComputationConfig computation,
    @JsonProperty(value = "concurrency") ConcurrencyConfig concurrency,
    @JsonProperty(value = "runtime") RuntimeConfig runtime
) implements ConfigModel {

  @JsonCreator
  public SystemConfig(String systemName, List<AgentConfig> agents, BudgetConfig budget, SchedulerConfig scheduler, TopologyConfig topology, VerificationConfig verification, ContinuationConfig continuation, DeepExplorationPolicyConfig deepExplorationPolicy, ComputationConfig computation, ConcurrencyConfig concurrency, RuntimeConfig runtime) {
    if (systemName == null) {
      systemName = "MathProofMesh";
    }
    systemName = ConfigValidation.trim(systemName);
    agents = ConfigValidation.required("agents", agents);
    agents = ConfigValidation.immutableList("agents", agents);
    if (budget == null) {
      budget = BudgetConfig.defaults();
    }
    if (scheduler == null) {
      scheduler = SchedulerConfig.defaults();
    }
    if (topology == null) {
      topology = TopologyConfig.defaults();
    }
    if (verification == null) {
      verification = VerificationConfig.defaults();
    }
    if (continuation == null) {
      continuation = ContinuationConfig.defaults();
    }
    if (deepExplorationPolicy == null) {
      deepExplorationPolicy = DeepExplorationPolicyConfig.defaults();
    }
    if (computation == null) {
      computation = ComputationConfig.defaults();
    }
    if (concurrency == null) {
      concurrency = ConcurrencyConfig.defaults();
    }
    if (runtime == null) {
      runtime = RuntimeConfig.defaults();
    }
    this.systemName = systemName;
    this.agents = agents;
    this.budget = budget;
    this.scheduler = scheduler;
    this.topology = topology;
    this.verification = verification;
    this.continuation = continuation;
    this.deepExplorationPolicy = deepExplorationPolicy;
    this.computation = computation;
    this.concurrency = concurrency;
    this.runtime = runtime;
    ConfigInvariants.validate(this);
  }

  public SystemConfig(
      String systemName,
      List<AgentConfig> agents,
      BudgetConfig budget,
      SchedulerConfig scheduler,
      TopologyConfig topology,
      VerificationConfig verification,
      ContinuationConfig continuation,
      DeepExplorationPolicyConfig deepExplorationPolicy,
      ComputationConfig computation,
      RuntimeConfig runtime) {
    this(
        systemName,
        agents,
        budget,
        scheduler,
        topology,
        verification,
        continuation,
        deepExplorationPolicy,
        computation,
        null,
        runtime);
  }

  @JsonProperty("agents")
  @Override
  public List<AgentConfig> agents() {
    return agents == null ? null : List.copyOf(agents);
  }


  public com.fasterxml.jackson.databind.node.ObjectNode redactedTree(
      com.fasterxml.jackson.databind.ObjectMapper mapper) {
    com.fasterxml.jackson.databind.node.ObjectNode root = mapper.valueToTree(this);
    com.fasterxml.jackson.databind.node.ArrayNode redactedAgents =
        (com.fasterxml.jackson.databind.node.ArrayNode) root.path("agents");
    for (int index = 0; index < redactedAgents.size(); index++) {
      com.fasterxml.jackson.databind.node.ObjectNode agent =
          (com.fasterxml.jackson.databind.node.ObjectNode) redactedAgents.get(index);
      String status = agent.path("api_key_env").isTextual()
          ? "configured-via-env"
          : "inline-secret-redacted";
      agent.put("key_status", status);
      agent.remove("api_key");
    }
    return root;
  }

}
