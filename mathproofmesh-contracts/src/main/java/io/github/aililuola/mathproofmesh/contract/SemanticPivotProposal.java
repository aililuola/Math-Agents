package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Bounded, non-authoritative draft of a mathematical strategy-state transition. */
public record SemanticPivotProposal(
    @JsonProperty(value = "proposal_id") @ContractNonNull String proposalId,
    @JsonProperty(value = "proposer_agent_id", required = true) @ContractNonNull
        String proposerAgentId,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "root_goal_hash", required = true) @ContractNonNull String rootGoalHash,
    @JsonProperty(value = "route_id", required = true) @ContractNonNull String routeId,
    @JsonProperty(value = "source_strategy_id", required = true) @ContractNonNull
        String sourceStrategyId,
    @JsonProperty(value = "transformation_types") @ContractNonNull
        List<String> transformationTypes,
    @JsonProperty(value = "obstruction_ids") @ContractNonNull List<String> obstructionIds,
    @JsonProperty(value = "object_changes") @ContractNonNull List<ObjectChangeDraft> objectChanges,
    @JsonProperty(value = "direction_changes") @ContractNonNull
        List<DirectionChangeDraft> directionChanges,
    @JsonProperty(value = "assumption_changes") @ContractNonNull
        List<AssumptionChangeDraft> assumptionChanges,
    @JsonProperty(value = "claim_use_changes") @ContractNonNull
        List<ClaimUseChangeDraft> claimUseChanges,
    @JsonProperty(value = "obligation_changes") @ContractNonNull
        List<ObligationChangeDraft> obligationChanges,
    @JsonProperty(value = "proposed_strategy", required = true) @ContractNonNull
        StrategyCard proposedStrategy,
    @JsonProperty(value = "rationale", required = true) @ContractNonNull String rationale,
    @JsonProperty(value = "claimed_pivot_id") String claimedPivotId,
    @JsonProperty(value = "claimed_structural_delta_hash") String claimedStructuralDeltaHash)
    implements StrictContract {
  public static final int MAX_CHANGES_PER_KIND = 32;

  public SemanticPivotProposal {
    if (proposalId == null) {
      proposalId = PythonCompatibleIdGenerator.newId("semantic-pivot-proposal");
    }
    proposalId = required("proposal_id", proposalId);
    proposerAgentId = required("proposer_agent_id", proposerAgentId);
    problemHash = required("problem_hash", problemHash);
    rootGoalHash = required("root_goal_hash", rootGoalHash);
    routeId = required("route_id", routeId);
    sourceStrategyId = required("source_strategy_id", sourceStrategyId);
    transformationTypes = bounded("transformation_types", transformationTypes);
    obstructionIds = bounded("obstruction_ids", obstructionIds);
    objectChanges = bounded("object_changes", objectChanges);
    directionChanges = bounded("direction_changes", directionChanges);
    assumptionChanges = bounded("assumption_changes", assumptionChanges);
    claimUseChanges = bounded("claim_use_changes", claimUseChanges);
    obligationChanges = bounded("obligation_changes", obligationChanges);
    proposedStrategy = ContractValues.required("proposed_strategy", proposedStrategy);
    rationale = required("rationale", rationale);
    claimedPivotId = ContractStrings.trim(claimedPivotId);
    claimedStructuralDeltaHash = ContractStrings.trim(claimedStructuralDeltaHash);
  }

  @Override
  public List<String> transformationTypes() {
    return List.copyOf(transformationTypes);
  }

  @Override
  public List<String> obstructionIds() {
    return List.copyOf(obstructionIds);
  }

  @Override
  public List<ObjectChangeDraft> objectChanges() {
    return List.copyOf(objectChanges);
  }

  @Override
  public List<DirectionChangeDraft> directionChanges() {
    return List.copyOf(directionChanges);
  }

  @Override
  public List<AssumptionChangeDraft> assumptionChanges() {
    return List.copyOf(assumptionChanges);
  }

  @Override
  public List<ClaimUseChangeDraft> claimUseChanges() {
    return List.copyOf(claimUseChanges);
  }

  @Override
  public List<ObligationChangeDraft> obligationChanges() {
    return List.copyOf(obligationChanges);
  }

  private static String required(String name, String value) {
    return ContractStrings.required(name, ContractStrings.trim(value));
  }

  private static <T> List<T> bounded(String name, List<T> values) {
    List<T> copy = ImmutableCollections.listOrEmpty(values);
    if (copy.size() > MAX_CHANGES_PER_KIND) {
      throw new ContractValidationException(
          name + " exceeds " + MAX_CHANGES_PER_KIND + " entries");
    }
    return copy;
  }

  public record ObjectChangeDraft(
      @JsonProperty(value = "old_object_id") String oldObjectId,
      @JsonProperty(value = "old_description") String oldDescription,
      @JsonProperty(value = "disposition", required = true) @ContractNonNull String disposition,
      @JsonProperty(value = "new_object_id") String newObjectId,
      @JsonProperty(value = "new_description") String newDescription,
      @JsonProperty(value = "bridge_statement") String bridgeStatement,
      @JsonProperty(value = "evidence_refs") @ContractNonNull List<String> evidenceRefs) {
    public ObjectChangeDraft {
      oldObjectId = ContractStrings.trim(oldObjectId);
      oldDescription = ContractStrings.trim(oldDescription);
      disposition = required("disposition", disposition);
      newObjectId = ContractStrings.trim(newObjectId);
      newDescription = ContractStrings.trim(newDescription);
      bridgeStatement = ContractStrings.trim(bridgeStatement);
      evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    }

    @Override
    public List<String> evidenceRefs() {
      return List.copyOf(evidenceRefs);
    }
  }

  public record DirectionChangeDraft(
      @JsonProperty(value = "old_direction_signature", required = true) @ContractNonNull
          String oldDirectionSignature,
      @JsonProperty(value = "new_direction_signature", required = true) @ContractNonNull
          String newDirectionSignature,
      @JsonProperty(value = "mathematical_reason", required = true) @ContractNonNull
          String mathematicalReason,
      @JsonProperty(value = "evidence_refs") @ContractNonNull List<String> evidenceRefs) {
    public DirectionChangeDraft {
      oldDirectionSignature = required("old_direction_signature", oldDirectionSignature);
      newDirectionSignature = required("new_direction_signature", newDirectionSignature);
      mathematicalReason = required("mathematical_reason", mathematicalReason);
      evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    }

    @Override
    public List<String> evidenceRefs() {
      return List.copyOf(evidenceRefs);
    }
  }

  public record AssumptionChangeDraft(
      @JsonProperty(value = "old_assumption") String oldAssumption,
      @JsonProperty(value = "new_assumption") String newAssumption,
      @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
      @JsonProperty(value = "evidence_refs") @ContractNonNull List<String> evidenceRefs) {
    public AssumptionChangeDraft {
      oldAssumption = ContractStrings.trim(oldAssumption);
      newAssumption = ContractStrings.trim(newAssumption);
      reason = required("reason", reason);
      evidenceRefs = ImmutableCollections.listOrEmpty(evidenceRefs);
    }

    @Override
    public List<String> evidenceRefs() {
      return List.copyOf(evidenceRefs);
    }
  }

  public record ClaimUseChangeDraft(
      @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
      @JsonProperty(value = "claim_statement_hash", required = true) @ContractNonNull
          String claimStatementHash,
      @JsonProperty(value = "action", required = true) @ContractNonNull String action,
      @JsonProperty(value = "reason", required = true) @ContractNonNull String reason,
      @JsonProperty(value = "proposed_claim") ProposedClaimDraft proposedClaim) {
    public ClaimUseChangeDraft(
        String claimId, String claimStatementHash, String action, String reason) {
      this(claimId, claimStatementHash, action, reason, null);
    }

    public ClaimUseChangeDraft {
      claimId = required("claim_id", claimId);
      claimStatementHash = required("claim_statement_hash", claimStatementHash);
      action = required("action", action);
      reason = required("reason", reason);
    }
  }

  /** Complete non-authoritative payload required to materialize a new proposed Claim. */
  public record ProposedClaimDraft(
      @JsonProperty(value = "claim_id", required = true) @ContractNonNull String claimId,
      @JsonProperty(value = "statement", required = true) @ContractNonNull String statement,
      @JsonProperty(value = "statement_hash", required = true) @ContractNonNull
          String statementHash,
      @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
      @JsonProperty(value = "proof_step_refs") @ContractNonNull List<String> proofStepRefs,
      @JsonProperty(value = "dependency_claim_ids") @ContractNonNull
          List<String> dependencyClaimIds,
      @JsonProperty(value = "tags") @ContractNonNull List<String> tags) {
    public ProposedClaimDraft {
      claimId = required("claim_id", claimId);
      statement = required("statement", statement);
      statementHash = required("statement_hash", statementHash);
      assumptions = ImmutableCollections.listOrEmpty(assumptions);
      proofStepRefs = ImmutableCollections.listOrEmpty(proofStepRefs);
      dependencyClaimIds = ImmutableCollections.listOrEmpty(dependencyClaimIds);
      tags = ImmutableCollections.listOrEmpty(tags);
    }

    @Override
    public List<String> assumptions() {
      return List.copyOf(assumptions);
    }

    @Override
    public List<String> proofStepRefs() {
      return List.copyOf(proofStepRefs);
    }

    @Override
    public List<String> dependencyClaimIds() {
      return List.copyOf(dependencyClaimIds);
    }

    @Override
    public List<String> tags() {
      return List.copyOf(tags);
    }
  }

  public record ObligationChangeDraft(
      @JsonProperty(value = "obligation_id", required = true) @ContractNonNull String obligationId,
      @JsonProperty(value = "canonical_target_id") String canonicalTargetId,
      @JsonProperty(value = "action", required = true) @ContractNonNull String action,
      @JsonProperty(value = "proposed_statement") String proposedStatement,
      @JsonProperty(value = "proposed_kind") String proposedKind,
      @JsonProperty(value = "assumptions") @ContractNonNull List<String> assumptions,
      @JsonProperty(value = "dependency_ids") @ContractNonNull List<String> dependencyIds,
      @JsonProperty(value = "reason", required = true) @ContractNonNull String reason) {
    public ObligationChangeDraft {
      obligationId = required("obligation_id", obligationId);
      canonicalTargetId = ContractStrings.trim(canonicalTargetId);
      action = required("action", action);
      proposedStatement = ContractStrings.trim(proposedStatement);
      proposedKind = ContractStrings.trim(proposedKind);
      assumptions = ImmutableCollections.listOrEmpty(assumptions);
      dependencyIds = ImmutableCollections.listOrEmpty(dependencyIds);
      reason = required("reason", reason);
    }

    @Override
    public List<String> assumptions() {
      return List.copyOf(assumptions);
    }

    @Override
    public List<String> dependencyIds() {
      return List.copyOf(dependencyIds);
    }
  }
}
