package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record StrategyCard(
    @JsonProperty(value = "assigned_agent_id") String assignedAgentId,
    @JsonProperty(value = "bottleneck", required = true) @ContractNonNull String bottleneck,
    @JsonProperty(value = "calculation_checks") @ContractNonNull List<ToolRequest> calculationChecks,
    @JsonProperty(value = "calculation_evidence_refs") @ContractNonNull List<EvidenceRef> calculationEvidenceRefs,
    @JsonProperty(value = "computation_hints") @ContractNonNull List<ComputationHint> computationHints,
    @JsonProperty(value = "core_idea", required = true) @ContractNonNull String coreIdea,
    @JsonProperty(value = "critical_claims") @ContractNonNull List<CriticalClaim> criticalClaims,
    @JsonProperty(value = "estimated_cost") @ContractNonNull Double estimatedCost,
    @JsonProperty(value = "estimated_success", required = true) @ContractNonNull Double estimatedSuccess,
    @JsonProperty(value = "expected_lemmas") @ContractNonNull List<String> expectedLemmas,
    @JsonProperty(value = "falsification_test", required = true) @ContractNonNull String falsificationTest,
    @JsonProperty(value = "independence_basis", required = true) @ContractNonNull String independenceBasis,
    @JsonProperty(value = "inspiration_proposal_id") String inspirationProposalId,
    @JsonProperty(value = "key_original_step") String keyOriginalStep,
    @JsonProperty(value = "parent_strategy_ids") @ContractNonNull List<String> parentStrategyIds,
    @JsonProperty(value = "prerequisites") @ContractNonNull List<String> prerequisites,
    @JsonProperty(value = "strategy_id") @ContractNonNull String strategyId,
    @JsonProperty(value = "tags") @ContractNonNull List<String> tags,
    @JsonProperty(value = "title", required = true) @ContractNonNull String title,
    @JsonProperty(value = "mechanism_operations") @ContractNonNull
        List<MechanismOperationDeclaration> mechanismOperations,
    @JsonProperty(value = "critical_claim_context_bindings") @ContractNonNull
        List<CriticalClaimContextBinding> criticalClaimContextBindings
) implements StrictContract {

  public StrategyCard {
    assignedAgentId = ContractStrings.trim(assignedAgentId);
    bottleneck = ContractStrings.trim(bottleneck);
    bottleneck = ContractStrings.required("bottleneck", bottleneck);
    if (calculationChecks == null) {
      calculationChecks = List.of();
    }
    calculationChecks = ImmutableCollections.listOrEmpty(calculationChecks);
    if (calculationEvidenceRefs == null) {
      calculationEvidenceRefs = List.of();
    }
    calculationEvidenceRefs = ImmutableCollections.listOrEmpty(calculationEvidenceRefs);
    if (computationHints == null) {
      computationHints = List.of();
    }
    computationHints = ImmutableCollections.listOrEmpty(computationHints);
    coreIdea = ContractStrings.trim(coreIdea);
    coreIdea = ContractStrings.required("core_idea", coreIdea);
    if (criticalClaims == null) {
      criticalClaims = List.of();
    }
    criticalClaims = ImmutableCollections.listOrEmpty(criticalClaims);
    if (estimatedCost == null) {
      estimatedCost = 0.5d;
    }
    ContractValues.minimum("estimated_cost", estimatedCost, 0.0);
    ContractValues.maximum("estimated_cost", estimatedCost, 1.0);
    estimatedSuccess = ContractValues.required("estimated_success", estimatedSuccess);
    ContractValues.minimum("estimated_success", estimatedSuccess, 0.0);
    ContractValues.maximum("estimated_success", estimatedSuccess, 1.0);
    if (expectedLemmas == null) {
      expectedLemmas = List.of();
    }
    expectedLemmas = ImmutableCollections.listOrEmpty(expectedLemmas);
    falsificationTest = ContractStrings.trim(falsificationTest);
    falsificationTest = ContractStrings.required("falsification_test", falsificationTest);
    independenceBasis = ContractStrings.trim(independenceBasis);
    independenceBasis = ContractStrings.required("independence_basis", independenceBasis);
    inspirationProposalId = ContractStrings.trim(inspirationProposalId);
    keyOriginalStep = ContractStrings.trim(keyOriginalStep);
    if (parentStrategyIds == null) {
      parentStrategyIds = List.of();
    }
    parentStrategyIds = ImmutableCollections.listOrEmpty(parentStrategyIds);
    if (prerequisites == null) {
      prerequisites = List.of();
    }
    prerequisites = ImmutableCollections.listOrEmpty(prerequisites);
    if (strategyId == null) {
      strategyId = PythonCompatibleIdGenerator.newId("strategy");
    }
    strategyId = ContractStrings.trim(strategyId);
    if (tags == null) {
      tags = List.of();
    }
    tags = ImmutableCollections.listOrEmpty(tags);
    title = ContractStrings.trim(title);
    title = ContractStrings.required("title", title);
    mechanismOperations = ImmutableCollections.listOrEmpty(mechanismOperations);
    criticalClaimContextBindings =
        ImmutableCollections.listOrEmpty(criticalClaimContextBindings);
    if (criticalClaims.isEmpty()) {
      criticalClaims =
          List.of(
              new CriticalClaim(
                  ContractHashes.criticalClaimId(title, bottleneck),
                  null,
                  falsificationTest,
                  "required",
                  null,
                  bottleneck,
                  null));
    }
  }

  public StrategyCard(
      String assignedAgentId,
      String bottleneck,
      List<ToolRequest> calculationChecks,
      List<EvidenceRef> calculationEvidenceRefs,
      List<ComputationHint> computationHints,
      String coreIdea,
      List<CriticalClaim> criticalClaims,
      Double estimatedCost,
      Double estimatedSuccess,
      List<String> expectedLemmas,
      String falsificationTest,
      String independenceBasis,
      String inspirationProposalId,
      String keyOriginalStep,
      List<String> parentStrategyIds,
      List<String> prerequisites,
      String strategyId,
      List<String> tags,
      String title) {
    this(
        assignedAgentId,
        bottleneck,
        calculationChecks,
        calculationEvidenceRefs,
        computationHints,
        coreIdea,
        criticalClaims,
        estimatedCost,
        estimatedSuccess,
        expectedLemmas,
        falsificationTest,
        independenceBasis,
        inspirationProposalId,
        keyOriginalStep,
        parentStrategyIds,
        prerequisites,
        strategyId,
        tags,
        title,
        List.of(),
        List.of());
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<ToolRequest> calculationChecks() {
    return calculationChecks == null ? null : List.copyOf(calculationChecks);
  }

  public List<EvidenceRef> calculationEvidenceRefs() {
    return calculationEvidenceRefs == null ? null : List.copyOf(calculationEvidenceRefs);
  }

  public List<ComputationHint> computationHints() {
    return computationHints == null ? null : List.copyOf(computationHints);
  }

  public List<CriticalClaim> criticalClaims() {
    return criticalClaims == null ? null : List.copyOf(criticalClaims);
  }

  public List<String> expectedLemmas() {
    return expectedLemmas == null ? null : List.copyOf(expectedLemmas);
  }

  public List<String> parentStrategyIds() {
    return parentStrategyIds == null ? null : List.copyOf(parentStrategyIds);
  }

  public List<String> prerequisites() {
    return prerequisites == null ? null : List.copyOf(prerequisites);
  }

  public List<String> tags() {
    return tags == null ? null : List.copyOf(tags);
  }

  public List<MechanismOperationDeclaration> mechanismOperations() {
    return mechanismOperations == null ? null : List.copyOf(mechanismOperations);
  }

  public List<CriticalClaimContextBinding> criticalClaimContextBindings() {
    return criticalClaimContextBindings == null
        ? null
        : List.copyOf(criticalClaimContextBindings);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
