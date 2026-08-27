package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record BottleneckControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "mode") String mode,
    @JsonProperty(value = "compression_interval_rounds") Integer compressionIntervalRounds,
    @JsonProperty(value = "equivalence_threshold") Double equivalenceThreshold,
    @JsonProperty(value = "dominance_threshold") Double dominanceThreshold,
    @JsonProperty(value = "max_cluster_members") Integer maxClusterMembers,
    @JsonProperty(value = "preserve_original_nodes") Boolean preserveOriginalNodes,
    @JsonProperty(value = "min_open_growth_for_forced_compression") Integer minOpenGrowthForForcedCompression
) implements ConfigModel {

  @JsonCreator
  public BottleneckControlConfig(Boolean enabled, String mode, Integer compressionIntervalRounds, Double equivalenceThreshold, Double dominanceThreshold, Integer maxClusterMembers, Boolean preserveOriginalNodes, Integer minOpenGrowthForForcedCompression) {
    if (enabled == null) {
      enabled = true;
    }
    if (mode == null) {
      mode = "shadow";
    }
    mode = ConfigValidation.trim(mode);
    ConfigValidation.oneOf("mode", mode, "off", "shadow", "active");
    if (compressionIntervalRounds == null) {
      compressionIntervalRounds = 1;
    }
    ConfigValidation.minimum("compression_interval_rounds", compressionIntervalRounds, 1);
    ConfigValidation.maximum("compression_interval_rounds", compressionIntervalRounds, 16);
    if (equivalenceThreshold == null) {
      equivalenceThreshold = 0.84d;
    }
    ConfigValidation.minimum("equivalence_threshold", equivalenceThreshold, 0.0d);
    ConfigValidation.maximum("equivalence_threshold", equivalenceThreshold, 1.0d);
    if (dominanceThreshold == null) {
      dominanceThreshold = 0.9d;
    }
    ConfigValidation.minimum("dominance_threshold", dominanceThreshold, 0.0d);
    ConfigValidation.maximum("dominance_threshold", dominanceThreshold, 1.0d);
    if (maxClusterMembers == null) {
      maxClusterMembers = 64;
    }
    ConfigValidation.minimum("max_cluster_members", maxClusterMembers, 2);
    ConfigValidation.maximum("max_cluster_members", maxClusterMembers, 1000);
    if (preserveOriginalNodes == null) {
      preserveOriginalNodes = true;
    }
    if (minOpenGrowthForForcedCompression == null) {
      minOpenGrowthForForcedCompression = 8;
    }
    ConfigValidation.minimum("min_open_growth_for_forced_compression", minOpenGrowthForForcedCompression, 1);
    ConfigValidation.maximum("min_open_growth_for_forced_compression", minOpenGrowthForForcedCompression, 1000);
    this.enabled = enabled;
    this.mode = mode;
    this.compressionIntervalRounds = compressionIntervalRounds;
    this.equivalenceThreshold = equivalenceThreshold;
    this.dominanceThreshold = dominanceThreshold;
    this.maxClusterMembers = maxClusterMembers;
    this.preserveOriginalNodes = preserveOriginalNodes;
    this.minOpenGrowthForForcedCompression = minOpenGrowthForForcedCompression;
  }

  public static BottleneckControlConfig defaults() {
    return new BottleneckControlConfig(null, null, null, null, null, null, null, null);
  }
}
