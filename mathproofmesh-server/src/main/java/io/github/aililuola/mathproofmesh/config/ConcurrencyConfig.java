package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Provider-work concurrency policy, independent from call and token budgets. */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ConcurrencyConfig(
    @JsonProperty("enabled") Boolean enabled,
    @JsonProperty("research_slots") Integer researchSlots,
    @JsonProperty("coordination_slots") Integer coordinationSlots,
    @JsonProperty("max_in_flight_tasks") Integer maxInFlightTasks,
    @JsonProperty("max_focused_parallel_roles") Integer maxFocusedParallelRoles,
    @JsonProperty("reserve_coordination_capacity") Boolean reserveCoordinationCapacity,
    @JsonProperty("allow_coordination_borrowing") Boolean allowCoordinationBorrowing,
    @JsonProperty("telemetry_sample_millis") Integer telemetrySampleMillis,
    @JsonProperty("lease_timeout_seconds") Integer leaseTimeoutSeconds)
    implements ConfigModel {

  @JsonCreator
  public ConcurrencyConfig {
    enabled = enabled == null || enabled;
    researchSlots = researchSlots == null ? 1 : researchSlots;
    coordinationSlots = coordinationSlots == null ? 0 : coordinationSlots;
    maxInFlightTasks = maxInFlightTasks == null ? 1 : maxInFlightTasks;
    maxFocusedParallelRoles = maxFocusedParallelRoles == null ? 1 : maxFocusedParallelRoles;
    reserveCoordinationCapacity =
        reserveCoordinationCapacity != null && reserveCoordinationCapacity;
    allowCoordinationBorrowing =
        allowCoordinationBorrowing != null && allowCoordinationBorrowing;
    telemetrySampleMillis = telemetrySampleMillis == null ? 25 : telemetrySampleMillis;
    leaseTimeoutSeconds = leaseTimeoutSeconds == null ? 30 : leaseTimeoutSeconds;
    ConfigValidation.minimum("research_slots", researchSlots, 1);
    ConfigValidation.maximum("research_slots", researchSlots, 128);
    ConfigValidation.minimum("coordination_slots", coordinationSlots, 0);
    ConfigValidation.maximum("coordination_slots", coordinationSlots, 128);
    ConfigValidation.minimum("max_in_flight_tasks", maxInFlightTasks, 1);
    ConfigValidation.maximum("max_in_flight_tasks", maxInFlightTasks, 256);
    ConfigValidation.minimum("max_focused_parallel_roles", maxFocusedParallelRoles, 1);
    ConfigValidation.maximum("max_focused_parallel_roles", maxFocusedParallelRoles, 32);
    ConfigValidation.minimum("telemetry_sample_millis", telemetrySampleMillis, 1);
    ConfigValidation.maximum("telemetry_sample_millis", telemetrySampleMillis, 60_000);
    ConfigValidation.minimum("lease_timeout_seconds", leaseTimeoutSeconds, 1);
    ConfigValidation.maximum("lease_timeout_seconds", leaseTimeoutSeconds, 3600);
    if (maxInFlightTasks < researchSlots) {
      throw new ConfigValidationException(
          "max_in_flight_tasks must be at least research_slots");
    }
    if (maxFocusedParallelRoles > maxInFlightTasks) {
      throw new ConfigValidationException(
          "max_focused_parallel_roles cannot exceed max_in_flight_tasks");
    }
  }

  public static ConcurrencyConfig defaults() {
    return new ConcurrencyConfig(null, null, null, null, null, null, null, null, null);
  }
}
