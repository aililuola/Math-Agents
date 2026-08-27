package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record UsageRecord(
    @JsonProperty(value = "estimated_cost_usd") @ContractNonNull Double estimatedCostUsd,
    @JsonProperty(value = "input_tokens") @ContractNonNull Integer inputTokens,
    @JsonProperty(value = "latency_ms") @ContractNonNull Double latencyMs,
    @JsonProperty(value = "output_tokens") @ContractNonNull Integer outputTokens,
    @JsonProperty(value = "total_tokens") @ContractNonNull Integer totalTokens
) implements StrictContract {

  public UsageRecord {
    if (estimatedCostUsd == null) {
      estimatedCostUsd = 0.0d;
    }
    ContractValues.minimum("estimated_cost_usd", estimatedCostUsd, 0.0);
    if (inputTokens == null) {
      inputTokens = 0;
    }
    ContractValues.minimum("input_tokens", inputTokens, 0);
    if (latencyMs == null) {
      latencyMs = 0.0d;
    }
    ContractValues.minimum("latency_ms", latencyMs, 0.0);
    if (outputTokens == null) {
      outputTokens = 0;
    }
    ContractValues.minimum("output_tokens", outputTokens, 0);
    if (totalTokens == null) {
      totalTokens = 0;
    }
    ContractValues.minimum("total_tokens", totalTokens, 0);
    int splitTotal = inputTokens + outputTokens;
    if (splitTotal != 0 && totalTokens != splitTotal) {
      totalTokens = splitTotal;
    }
  }

  public UsageRecord() {
    this(null, null, null, null, null);
  }
}
