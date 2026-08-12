package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record ProofGraphConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "mode") String mode,
    @JsonProperty(value = "max_nodes") Integer maxNodes,
    @JsonProperty(value = "max_edges") Integer maxEdges,
    @JsonProperty(value = "allow_cycles") Boolean allowCycles,
    @JsonProperty(value = "close_obligation_threshold") Double closeObligationThreshold,
    @JsonProperty(value = "shared_bottleneck_min_routes") Integer sharedBottleneckMinRoutes,
    @JsonProperty(value = "freeze_before_synthesis") Boolean freezeBeforeSynthesis
) implements ConfigModel {

  @JsonCreator
  public ProofGraphConfig(Boolean enabled, String mode, Integer maxNodes, Integer maxEdges, Boolean allowCycles, Double closeObligationThreshold, Integer sharedBottleneckMinRoutes, Boolean freezeBeforeSynthesis) {
    if (enabled == null) {
      enabled = false;
    }
    if (mode == null) {
      mode = "shadow";
    }
    mode = ConfigValidation.trim(mode);
    ConfigValidation.oneOf("mode", mode, "off", "shadow", "active");
    if (maxNodes == null) {
      maxNodes = 5000;
    }
    ConfigValidation.minimum("max_nodes", maxNodes, 100);
    ConfigValidation.maximum("max_nodes", maxNodes, 100000);
    if (maxEdges == null) {
      maxEdges = 20000;
    }
    ConfigValidation.minimum("max_edges", maxEdges, 100);
    ConfigValidation.maximum("max_edges", maxEdges, 500000);
    if (allowCycles == null) {
      allowCycles = false;
    }
    if (closeObligationThreshold == null) {
      closeObligationThreshold = 0.8d;
    }
    ConfigValidation.minimum("close_obligation_threshold", closeObligationThreshold, 0.0d);
    ConfigValidation.maximum("close_obligation_threshold", closeObligationThreshold, 1.0d);
    if (sharedBottleneckMinRoutes == null) {
      sharedBottleneckMinRoutes = 2;
    }
    ConfigValidation.minimum("shared_bottleneck_min_routes", sharedBottleneckMinRoutes, 2);
    ConfigValidation.maximum("shared_bottleneck_min_routes", sharedBottleneckMinRoutes, 32);
    if (freezeBeforeSynthesis == null) {
      freezeBeforeSynthesis = true;
    }
    this.enabled = enabled;
    this.mode = mode;
    this.maxNodes = maxNodes;
    this.maxEdges = maxEdges;
    this.allowCycles = allowCycles;
    this.closeObligationThreshold = closeObligationThreshold;
    this.sharedBottleneckMinRoutes = sharedBottleneckMinRoutes;
    this.freezeBeforeSynthesis = freezeBeforeSynthesis;
  }

  public static ProofGraphConfig defaults() {
    return new ProofGraphConfig(null, null, null, null, null, null, null, null);
  }
}
