package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record RouteMember(
    @JsonProperty(value = "agent_id", required = true) @ContractNonNull String agentId,
    @JsonProperty(value = "assigned_round", required = true) @ContractNonNull Integer assignedRound,
    @JsonProperty(value = "role", required = true) @ContractNonNull RouteRole role
) implements StrictContract {

  public RouteMember {
    agentId = ContractStrings.trim(agentId);
    agentId = ContractStrings.required("agent_id", agentId);
    assignedRound = ContractValues.required("assigned_round", assignedRound);
    ContractValues.minimum("assigned_round", assignedRound, 0);
    role = ContractValues.required("role", role);
  }
}
