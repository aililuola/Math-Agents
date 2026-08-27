package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import java.util.List;

/** Immutable mathematical identity reviewed by every role in one claim-court case. */
public record FrozenClaimSnapshot(
    String courtCaseId,
    String problemHash,
    String rootGoalHash,
    String claimId,
    String claimStatementHash,
    String claimSemanticHash,
    String statement,
    String conclusion,
    List<String> assumptions,
    List<QuantifierSpec> quantifiers,
    List<VariableBinding> variableBindings,
    List<String> scopeLimitations,
    String polarity,
    List<String> dependencyClaimIds,
    String dependencySnapshotHash,
    String initialProofRevisionId,
    String sourceAttemptId,
    String sourceRouteId,
    String authorAgentId) {
  public FrozenClaimSnapshot {
    courtCaseId = ClaimCourtValues.required(courtCaseId, "courtCaseId");
    problemHash = ClaimCourtValues.required(problemHash, "problemHash");
    rootGoalHash = ClaimCourtValues.required(rootGoalHash, "rootGoalHash");
    claimId = ClaimCourtValues.required(claimId, "claimId");
    claimStatementHash = ClaimCourtValues.required(claimStatementHash, "claimStatementHash");
    claimSemanticHash = ClaimCourtValues.required(claimSemanticHash, "claimSemanticHash");
    statement = ClaimCourtValues.required(statement, "statement");
    conclusion = ClaimCourtValues.required(conclusion, "conclusion");
    assumptions = ClaimCourtValues.copy(assumptions);
    quantifiers = ClaimCourtValues.copy(quantifiers);
    variableBindings = ClaimCourtValues.copy(variableBindings);
    scopeLimitations = ClaimCourtValues.copy(scopeLimitations);
    polarity = ClaimCourtValues.required(polarity, "polarity");
    dependencyClaimIds = ClaimCourtValues.copy(dependencyClaimIds);
    dependencySnapshotHash =
        ClaimCourtValues.required(dependencySnapshotHash, "dependencySnapshotHash");
    initialProofRevisionId =
        ClaimCourtValues.required(initialProofRevisionId, "initialProofRevisionId");
    sourceAttemptId = ClaimCourtValues.required(sourceAttemptId, "sourceAttemptId");
    sourceRouteId = ClaimCourtValues.required(sourceRouteId, "sourceRouteId");
    authorAgentId = ClaimCourtValues.required(authorAgentId, "authorAgentId");
  }

  @Override
  public List<String> assumptions() {
    return List.copyOf(assumptions);
  }

  @Override
  public List<QuantifierSpec> quantifiers() {
    return List.copyOf(quantifiers);
  }

  @Override
  public List<VariableBinding> variableBindings() {
    return List.copyOf(variableBindings);
  }

  @Override
  public List<String> scopeLimitations() {
    return List.copyOf(scopeLimitations);
  }

  @Override
  public List<String> dependencyClaimIds() {
    return List.copyOf(dependencyClaimIds);
  }

  public String statementCaseId() {
    return "claim-statement-"
        + CanonicalJson.stableHash(List.of(problemHash, rootGoalHash, claimSemanticHash))
            .substring(0, 24);
  }
}
