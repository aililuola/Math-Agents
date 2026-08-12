package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record SurpriseBudgetState(
    @JsonProperty(value = "cooldown_until_round") Integer cooldownUntilRound,
    @JsonProperty(value = "finalization_reserve_calls") @ContractNonNull Integer finalizationReserveCalls,
    @JsonProperty(value = "rejection_streak") @ContractNonNull Integer rejectionStreak,
    @JsonProperty(value = "reserved_calls") @ContractNonNull Integer reservedCalls,
    @JsonProperty(value = "total_calls") @ContractNonNull Integer totalCalls,
    @JsonProperty(value = "used_calls") @ContractNonNull Integer usedCalls
) implements StrictContract {

  public SurpriseBudgetState {
    ContractValues.minimum("cooldown_until_round", cooldownUntilRound, 0);
    if (finalizationReserveCalls == null) {
      finalizationReserveCalls = 0;
    }
    ContractValues.minimum("finalization_reserve_calls", finalizationReserveCalls, 0);
    if (rejectionStreak == null) {
      rejectionStreak = 0;
    }
    ContractValues.minimum("rejection_streak", rejectionStreak, 0);
    if (reservedCalls == null) {
      reservedCalls = 0;
    }
    ContractValues.minimum("reserved_calls", reservedCalls, 0);
    if (totalCalls == null) {
      totalCalls = 0;
    }
    ContractValues.minimum("total_calls", totalCalls, 0);
    if (usedCalls == null) {
      usedCalls = 0;
    }
    ContractValues.minimum("used_calls", usedCalls, 0);
  }

  public SurpriseBudgetState() {
    this(null, null, null, null, null, null);
  }

  @JsonIgnore
  public int remainingCalls() {
    return Math.max(0, totalCalls - usedCalls - reservedCalls);
  }
}
