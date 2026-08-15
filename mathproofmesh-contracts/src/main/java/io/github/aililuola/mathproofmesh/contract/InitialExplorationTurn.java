package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InitialExplorationTurn(
    @JsonProperty(value = "action", required = true) @ContractNonNull InitialExplorationAction action,
    @JsonProperty(value = "attempt") ProofAttempt attempt,
    @JsonProperty(value = "experiment_impact") FailureLevel experimentImpact,
    @JsonProperty(value = "experiment_spec") ExperimentSpec experimentSpec,
    @JsonProperty(value = "reason") @ContractNonNull String reason,
    @JsonProperty(value = "broker_artifact_use_manifest") BrokerArtifactUseManifest brokerArtifactUseManifest
) implements StrictContract {

  public InitialExplorationTurn {
    action = ContractValues.required("action", action);
    if (reason == null) {
      reason = "";
    }
    reason = ContractStrings.trim(reason);
  }

  public InitialExplorationTurn(
      InitialExplorationAction action,
      ProofAttempt attempt,
      FailureLevel experimentImpact,
      ExperimentSpec experimentSpec,
      String reason) {
    this(action, attempt, experimentImpact, experimentSpec, reason, null);
  }
}
