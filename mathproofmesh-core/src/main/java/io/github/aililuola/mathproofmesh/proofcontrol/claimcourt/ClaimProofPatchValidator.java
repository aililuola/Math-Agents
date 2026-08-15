package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimProofAuditVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperation;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.ProofAuditIssue;
import io.github.aililuola.mathproofmesh.contract.ProofRepairability;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministically validates and applies a bounded proof-only patch. */
public final class ClaimProofPatchValidator {
  public record ValidationResult(
      boolean passed,
      List<String> failureCodes,
      List<ProofStep> proofSteps,
      List<String> dependencyClaimIds,
      List<EvidenceRef> evidenceRefs) {
    public ValidationResult {
      failureCodes = ClaimCourtValues.copy(failureCodes);
      proofSteps = ClaimCourtValues.copy(proofSteps);
      dependencyClaimIds = ClaimCourtValues.copy(dependencyClaimIds);
      evidenceRefs = ClaimCourtValues.copy(evidenceRefs);
    }

    @Override
    public List<String> failureCodes() {
      return List.copyOf(failureCodes);
    }

    @Override
    public List<ProofStep> proofSteps() {
      return List.copyOf(proofSteps);
    }

    @Override
    public List<String> dependencyClaimIds() {
      return List.copyOf(dependencyClaimIds);
    }

    @Override
    public List<EvidenceRef> evidenceRefs() {
      return List.copyOf(evidenceRefs);
    }
  }

  private final ClaimCourtConfig config;

  public ClaimProofPatchValidator(ClaimCourtConfig config) {
    this.config = java.util.Objects.requireNonNull(config, "config");
  }

  public ValidationResult validate(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord base,
      ClaimProofAuditDecision audit,
      ClaimProofPatch patch,
      Set<String> verifiedDependencyClaimIds) {
    java.util.Objects.requireNonNull(frozen, "frozen");
    java.util.Objects.requireNonNull(base, "base");
    java.util.Objects.requireNonNull(audit, "audit");
    java.util.Objects.requireNonNull(patch, "patch");
    Set<String> verifiedDependencies =
        verifiedDependencyClaimIds == null ? Set.of() : Set.copyOf(verifiedDependencyClaimIds);
    LinkedHashSet<String> failures = new LinkedHashSet<>();
    requireFrozenIdentity(frozen, base, audit, patch, failures);
    validateAuditBinding(audit, patch, failures);

    Map<String, ProofStep> original = index(base.proofSteps(), failures);
    Set<String> operationTargets = new LinkedHashSet<>();
    int insertedCount = 0;
    for (ClaimProofPatchOperation operation : patch.operations()) {
      operationTargets.add(operation.targetStepId());
      if (!original.containsKey(operation.targetStepId())) {
        failures.add("UNKNOWN_PATCH_TARGET_STEP");
      }
      if (operation.operationType()
              == io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType
                  .INSERT_STEP_BEFORE
          || operation.operationType()
              == io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType
                  .INSERT_STEP_AFTER) {
        insertedCount++;
      }
      if (operation.operationType()
              == io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType
                  .REBIND_VERIFIED_DEPENDENCY
          && !verifiedDependencies.contains(operation.verifiedDependencyClaimId())) {
        failures.add("UNVERIFIED_DEPENDENCY_ADDITION");
      }
      if (operation.operationType()
              == io.github.aililuola.mathproofmesh.contract.ClaimProofPatchOperationType
                  .DELETE_REDUNDANT_STEP
          && original.containsKey(operation.targetStepId())
          && hasEvidence(original.get(operation.targetStepId()))) {
        failures.add("VALID_EVIDENCE_DELETION");
      }
    }
    if (!operationTargets.equals(new LinkedHashSet<>(patch.changedStepIds()))) {
      failures.add("CHANGED_STEP_DECLARATION_MISMATCH");
    }
    int allowedByFraction =
        Math.max(1, (int) Math.ceil(base.proofSteps().size() * config.maxChangedStepFraction()));
    if (operationTargets.size() > config.maxChangedSteps()
        || operationTargets.size() > allowedByFraction) {
      failures.add("PATCH_CHANGED_STEP_LIMIT_EXCEEDED");
    }
    if (insertedCount > config.maxInsertedSteps()) {
      failures.add("PATCH_INSERTED_STEP_LIMIT_EXCEEDED");
    }
    if (!failures.isEmpty()) {
      return failed(failures, base);
    }

    List<ProofStep> updated = new ArrayList<>(base.proofSteps());
    List<EvidenceRef> evidence = new ArrayList<>(base.evidenceRefs());
    for (ClaimProofPatchOperation operation : patch.operations()) {
      applyOperation(updated, evidence, operation);
    }
    validateUniqueStepIds(updated, failures);
    validateDependencies(updated, verifiedDependencies, failures);
    if (hasCycle(updated)) {
      failures.add("PROOF_DEPENDENCY_CYCLE");
    }
    ensureUnchangedStepsPreserved(base.proofSteps(), updated, operationTargets, failures);
    if (!failures.isEmpty()) {
      return failed(failures, base);
    }
    return new ValidationResult(
        true, List.of(), updated, base.dependencyClaimIds(), evidence);
  }

  private static void requireFrozenIdentity(
      FrozenClaimSnapshot frozen,
      ClaimProofRevisionRecord base,
      ClaimProofAuditDecision audit,
      ClaimProofPatch patch,
      Set<String> failures) {
    if (!frozen.claimId().equals(base.claimId())
        || !frozen.claimId().equals(audit.claimId())
        || !frozen.claimId().equals(patch.claimId())
        || !frozen.claimSemanticHash().equals(base.claimSemanticHash())
        || !frozen.claimSemanticHash().equals(patch.claimSemanticHash())
        || !base.revisionId().equals(patch.baseProofRevisionId())
        || !base.proofHash().equals(patch.baseProofHash())) {
      failures.add("FROZEN_CLAIM_MUTATION");
    }
  }

  private static void validateAuditBinding(
      ClaimProofAuditDecision audit, ClaimProofPatch patch, Set<String> failures) {
    if (audit.verdict() != ClaimProofAuditVerdict.INVALID_REPAIRABLE) {
      failures.add("AUDIT_NOT_REPAIRABLE");
      return;
    }
    Set<String> auditIssueIds = new LinkedHashSet<>();
    Set<String> allowedSteps = new LinkedHashSet<>();
    for (ProofAuditIssue issue : audit.issues()) {
      auditIssueIds.add(issue.issueId());
      allowedSteps.add(issue.stepId());
      if (issue.touchesClaimStatement()) {
        failures.add("STATEMENT_REFORMULATION_REQUIRED");
      }
      if (issue.repairability() != ProofRepairability.LOCAL_PATCH
          && issue.repairability() != ProofRepairability.VERIFIED_DEPENDENCY_PATCH) {
        failures.add("NONLOCAL_REPAIRABILITY");
      }
    }
    if (!auditIssueIds.equals(new LinkedHashSet<>(patch.issueIds()))
        || !auditIssueIds.equals(new LinkedHashSet<>(patch.expectedResolvedIssueIds()))) {
      failures.add("ISSUE_BINDING_MISMATCH");
    }
    if (!allowedSteps.containsAll(patch.changedStepIds())) {
      failures.add("PATCH_TOUCHES_UNAUDITED_STEP");
    }
  }

  private static Map<String, ProofStep> index(
      List<ProofStep> steps, Set<String> failures) {
    Map<String, ProofStep> result = new LinkedHashMap<>();
    for (ProofStep step : steps) {
      if (result.putIfAbsent(step.stepId(), step) != null) {
        failures.add("DUPLICATE_BASE_STEP_ID");
      }
    }
    return result;
  }

  private static boolean hasEvidence(ProofStep step) {
    return !step.calculationEvidenceRefs().isEmpty()
        || !step.citations().isEmpty()
        || !step.calculations().isEmpty();
  }

  private static ValidationResult failed(
      Set<String> failures, ClaimProofRevisionRecord base) {
    return new ValidationResult(
        false,
        List.copyOf(failures),
        base.proofSteps(),
        base.dependencyClaimIds(),
        base.evidenceRefs());
  }

  private static void applyOperation(
      List<ProofStep> steps,
      List<EvidenceRef> evidence,
      ClaimProofPatchOperation operation) {
    int targetIndex = indexOf(steps, operation.targetStepId());
    ProofStep target = steps.get(targetIndex);
    switch (operation.operationType()) {
      case REPLACE_STEP_JUSTIFICATION ->
          steps.set(
              targetIndex,
              copyStep(
                  target,
                  target.statement(),
                  operation.replacementJustification(),
                  target.dependencies(),
                  target.calculationEvidenceRefs()));
      case REPLACE_STEP_STATEMENT ->
          steps.set(
              targetIndex,
              copyStep(
                  target,
                  operation.replacementStatement(),
                  target.justification(),
                  target.dependencies(),
                  target.calculationEvidenceRefs()));
      case INSERT_STEP_BEFORE -> steps.add(targetIndex, operation.insertedStep());
      case INSERT_STEP_AFTER -> steps.add(targetIndex + 1, operation.insertedStep());
      case DELETE_REDUNDANT_STEP -> steps.remove(targetIndex);
      case REBIND_VERIFIED_DEPENDENCY -> {
        List<String> dependencies = new ArrayList<>(target.dependencies());
        String claimDependency = "claim:" + operation.verifiedDependencyClaimId();
        if (!dependencies.contains(claimDependency)) {
          dependencies.add(claimDependency);
        }
        steps.set(
            targetIndex,
            copyStep(
                target,
                target.statement(),
                target.justification(),
                dependencies,
                target.calculationEvidenceRefs()));
      }
      case ADD_VERIFIED_EVIDENCE_REF -> {
        List<EvidenceRef> refs = new ArrayList<>(target.calculationEvidenceRefs());
        if (!refs.contains(operation.evidenceRef())) {
          refs.add(operation.evidenceRef());
        }
        if (!evidence.contains(operation.evidenceRef())) {
          evidence.add(operation.evidenceRef());
        }
        steps.set(
            targetIndex,
            copyStep(
                target,
                target.statement(),
                target.justification(),
                target.dependencies(),
                refs));
      }
    }
  }

  private static int indexOf(List<ProofStep> steps, String stepId) {
    for (int index = 0; index < steps.size(); index++) {
      if (steps.get(index).stepId().equals(stepId)) {
        return index;
      }
    }
    throw new IllegalStateException("validated target step disappeared");
  }

  private static ProofStep copyStep(
      ProofStep source,
      String statement,
      String justification,
      List<String> dependencies,
      List<EvidenceRef> calculationEvidenceRefs) {
    return new ProofStep(
        source.branchLabel(),
        source.calculationChecks(),
        calculationEvidenceRefs,
        source.calculations(),
        source.citations(),
        source.confidence(),
        dependencies,
        source.dependencyRefs(),
        source.isKeyStep(),
        justification,
        statement,
        source.stepId(),
        source.stepType());
  }

  private static void validateUniqueStepIds(
      List<ProofStep> steps, Set<String> failures) {
    Set<String> ids = new HashSet<>();
    if (steps.stream().anyMatch(step -> !ids.add(step.stepId()))) {
      failures.add("DUPLICATE_PATCHED_STEP_ID");
    }
  }

  private static void validateDependencies(
      List<ProofStep> steps, Set<String> verifiedDependencies, Set<String> failures) {
    Set<String> stepIds =
        steps.stream().map(ProofStep::stepId).collect(java.util.stream.Collectors.toSet());
    for (ProofStep step : steps) {
      for (String dependency : step.dependencies()) {
        if (dependency.startsWith("claim:")
            && !verifiedDependencies.contains(dependency.substring("claim:".length()))) {
          failures.add("UNVERIFIED_DEPENDENCY_ADDITION");
        } else if (dependency.startsWith("step:")
            && !stepIds.contains(dependency.substring("step:".length()))) {
          failures.add("MISSING_LOCAL_STEP_DEPENDENCY");
        }
      }
    }
  }

  private static boolean hasCycle(List<ProofStep> steps) {
    Set<String> stepIds =
        steps.stream().map(ProofStep::stepId).collect(java.util.stream.Collectors.toSet());
    Map<String, List<String>> dependencies = new HashMap<>();
    for (ProofStep step : steps) {
      List<String> local = new ArrayList<>();
      for (String raw : step.dependencies()) {
        String target = raw.startsWith("step:") ? raw.substring("step:".length()) : raw;
        if (stepIds.contains(target)) {
          local.add(target);
        }
      }
      dependencies.put(step.stepId(), local);
    }
    Set<String> visiting = new HashSet<>();
    Set<String> visited = new HashSet<>();
    for (String stepId : stepIds) {
      if (cycleFrom(stepId, dependencies, visiting, visited)) {
        return true;
      }
    }
    return false;
  }

  private static boolean cycleFrom(
      String stepId,
      Map<String, List<String>> dependencies,
      Set<String> visiting,
      Set<String> visited) {
    if (visiting.contains(stepId)) {
      return true;
    }
    if (!visited.add(stepId)) {
      return false;
    }
    visiting.add(stepId);
    Deque<String> children = new ArrayDeque<>(dependencies.getOrDefault(stepId, List.of()));
    while (!children.isEmpty()) {
      if (cycleFrom(children.removeFirst(), dependencies, visiting, visited)) {
        return true;
      }
    }
    visiting.remove(stepId);
    return false;
  }

  private static void ensureUnchangedStepsPreserved(
      List<ProofStep> before,
      List<ProofStep> after,
      Set<String> changedStepIds,
      Set<String> failures) {
    Map<String, ProofStep> afterById = new LinkedHashMap<>();
    after.forEach(step -> afterById.put(step.stepId(), step));
    for (ProofStep original : before) {
      if (!changedStepIds.contains(original.stepId())
          && !original.equals(afterById.get(original.stepId()))) {
        failures.add("UNAUDITED_STEP_CHANGED");
      }
    }
  }
}
