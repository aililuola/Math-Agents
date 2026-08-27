package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record InspirationCallReservation(
    @JsonProperty(value = "consumed_calls") @ContractNonNull Integer consumedCalls,
    @JsonProperty(value = "overrun_calls") @ContractNonNull Integer overrunCalls,
    @JsonProperty(value = "phase_calls") @ContractNonNull Map<String, Integer> phaseCalls,
    @JsonProperty(value = "proposer_calls") @ContractNonNull Integer proposerCalls,
    @JsonProperty(value = "referee_calls") @ContractNonNull Integer refereeCalls,
    @JsonProperty(value = "released_calls") @ContractNonNull Integer releasedCalls,
    @JsonProperty(value = "reservation_id") @ContractNonNull String reservationId,
    @JsonProperty(value = "reserved_calls", required = true) @ContractNonNull Integer reservedCalls,
    @JsonProperty(value = "round_index", required = true) @ContractNonNull Integer roundIndex,
    @JsonProperty(value = "route_attempt_calls") @ContractNonNull Integer routeAttemptCalls,
    @JsonProperty(value = "skeptic_calls") @ContractNonNull Integer skepticCalls,
    @JsonProperty(value = "status") @ContractNonNull String status,
    @JsonProperty(value = "task_id", required = true) @ContractNonNull String taskId,
    @JsonProperty(value = "trigger_id", required = true) @ContractNonNull String triggerId
) implements StrictContract {

  public InspirationCallReservation {
    if (consumedCalls == null) {
      consumedCalls = 0;
    }
    ContractValues.minimum("consumed_calls", consumedCalls, 0);
    if (overrunCalls == null) {
      overrunCalls = 0;
    }
    ContractValues.minimum("overrun_calls", overrunCalls, 0);
    if (phaseCalls == null) {
      phaseCalls = Map.of();
    }
    phaseCalls = ImmutableCollections.mapOrEmpty(phaseCalls);
    if (proposerCalls == null) {
      proposerCalls = 0;
    }
    ContractValues.minimum("proposer_calls", proposerCalls, 0);
    if (refereeCalls == null) {
      refereeCalls = 0;
    }
    ContractValues.minimum("referee_calls", refereeCalls, 0);
    if (releasedCalls == null) {
      releasedCalls = 0;
    }
    ContractValues.minimum("released_calls", releasedCalls, 0);
    if (reservationId == null) {
      reservationId = PythonCompatibleIdGenerator.newId("inspiration_budget");
    }
    reservationId = ContractStrings.trim(reservationId);
    reservedCalls = ContractValues.required("reserved_calls", reservedCalls);
    ContractValues.minimum("reserved_calls", reservedCalls, 0);
    roundIndex = ContractValues.required("round_index", roundIndex);
    ContractValues.minimum("round_index", roundIndex, 0);
    if (routeAttemptCalls == null) {
      routeAttemptCalls = 0;
    }
    ContractValues.minimum("route_attempt_calls", routeAttemptCalls, 0);
    if (skepticCalls == null) {
      skepticCalls = 0;
    }
    ContractValues.minimum("skeptic_calls", skepticCalls, 0);
    if (status == null) {
      status = "active";
    }
    status = ContractStrings.trim(status);
    ContractValues.oneOf("status", status, "active", "completed", "interrupted");
    taskId = ContractStrings.trim(taskId);
    taskId = ContractStrings.required("task_id", taskId);
    triggerId = ContractStrings.trim(triggerId);
    triggerId = ContractStrings.required("trigger_id", triggerId);
    int planned = proposerCalls + refereeCalls + skepticCalls + routeAttemptCalls;
    if (planned != reservedCalls) {
      throw new ContractValidationException(
          "inspiration reservation breakdown must match reserved_calls");
    }
  }

  @JsonIgnore
  public int remainingReservedCalls() {
    return Math.max(0, reservedCalls - consumedCalls);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public Map<String, Integer> phaseCalls() {
    return phaseCalls == null ? null : Map.copyOf(phaseCalls);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
