package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record RealizerControlConfig(
    @JsonProperty(value = "enabled") Boolean enabled,
    @JsonProperty(value = "max_realizer_repairs_per_structure") Integer maxRealizerRepairsPerStructure,
    @JsonProperty(value = "require_explicit_admissibility") Boolean requireExplicitAdmissibility,
    @JsonProperty(value = "require_well_founded_descent") Boolean requireWellFoundedDescent,
    @JsonProperty(value = "require_falsification_test") Boolean requireFalsificationTest,
    @JsonProperty(value = "preserve_abstract_proposal_after_candidate_failure") Boolean preserveAbstractProposalAfterCandidateFailure
) implements ConfigModel {

  @JsonCreator
  public RealizerControlConfig(Boolean enabled, Integer maxRealizerRepairsPerStructure, Boolean requireExplicitAdmissibility, Boolean requireWellFoundedDescent, Boolean requireFalsificationTest, Boolean preserveAbstractProposalAfterCandidateFailure) {
    if (enabled == null) {
      enabled = true;
    }
    if (maxRealizerRepairsPerStructure == null) {
      maxRealizerRepairsPerStructure = 2;
    }
    ConfigValidation.minimum("max_realizer_repairs_per_structure", maxRealizerRepairsPerStructure, 0);
    ConfigValidation.maximum("max_realizer_repairs_per_structure", maxRealizerRepairsPerStructure, 16);
    if (requireExplicitAdmissibility == null) {
      requireExplicitAdmissibility = true;
    }
    if (requireWellFoundedDescent == null) {
      requireWellFoundedDescent = true;
    }
    if (requireFalsificationTest == null) {
      requireFalsificationTest = true;
    }
    if (preserveAbstractProposalAfterCandidateFailure == null) {
      preserveAbstractProposalAfterCandidateFailure = true;
    }
    this.enabled = enabled;
    this.maxRealizerRepairsPerStructure = maxRealizerRepairsPerStructure;
    this.requireExplicitAdmissibility = requireExplicitAdmissibility;
    this.requireWellFoundedDescent = requireWellFoundedDescent;
    this.requireFalsificationTest = requireFalsificationTest;
    this.preserveAbstractProposalAfterCandidateFailure = preserveAbstractProposalAfterCandidateFailure;
  }

  public static RealizerControlConfig defaults() {
    return new RealizerControlConfig(null, null, null, null, null, null);
  }
}
