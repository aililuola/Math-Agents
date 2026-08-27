package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Creates a stable, immutable claim identity before any court provider is called. */
public final class ClaimFreezeService {
  public FrozenClaimSnapshot freeze(
      String problemHash,
      String rootGoalHash,
      String sourceRouteId,
      ClaimCard claim,
      FrozenClaimSemanticContext context) {
    java.util.Objects.requireNonNull(claim, "claim");
    FrozenClaimSemanticContext semanticContext =
        java.util.Objects.requireNonNull(context, "context");
    String problem = ClaimCourtValues.required(problemHash, "problemHash");
    String root = ClaimCourtValues.required(rootGoalHash, "rootGoalHash");
    String route = ClaimCourtValues.required(sourceRouteId, "sourceRouteId");
    String attempt = ClaimCourtValues.required(claim.sourceAttemptId(), "sourceAttemptId");
    String author = ClaimCourtValues.required(claim.sourceAgentId(), "authorAgentId");
    List<String> dependencies = claimDependencies(claim);
    LinkedHashSet<String> frozenAssumptions =
        new LinkedHashSet<>(semanticContext.assumptions());
    frozenAssumptions.addAll(claim.assumptions());
    List<String> assumptions = List.copyOf(frozenAssumptions);
    String statementHash = CanonicalJson.stableHash(claim.statement());
    String dependencyHash = CanonicalJson.stableHash(dependencies);
    String semanticHash =
        CanonicalJson.stableHash(
            Map.of(
                "statement", ClaimCourtValues.normalizedSemanticText(claim.statement()),
                "conclusion", ClaimCourtValues.normalizedSemanticText(claim.conclusion()),
                "assumptions", assumptions,
                "quantifiers", semanticContext.quantifiers(),
                "variable_bindings", semanticContext.variableBindings(),
                "scope_limitations", semanticContext.scopeLimitations(),
                "polarity", semanticContext.polarity(),
                "dependencies", dependencies));
    String proofHash = CanonicalJson.stableHash(claim.proofSteps());
    String initialRevisionId =
        ClaimProofRevisionIdentity.originalId(
            problem,
            root,
            semanticHash,
            proofHash);
    String statementCaseId =
        "claim-statement-"
            + CanonicalJson.stableHash(List.of(problem, root, semanticHash)).substring(0, 24);
    String caseId =
        "claim-court-"
            + CanonicalJson.stableHash(List.of(statementCaseId, initialRevisionId, proofHash))
                .substring(0, 24);
    return new FrozenClaimSnapshot(
        caseId,
        problem,
        root,
        claim.claimId(),
        statementHash,
        semanticHash,
        claim.statement(),
        claim.conclusion(),
        assumptions,
        semanticContext.quantifiers(),
        semanticContext.variableBindings(),
        semanticContext.scopeLimitations(),
        semanticContext.polarity(),
        dependencies,
        dependencyHash,
        initialRevisionId,
        attempt,
        route,
        author);
  }

  public void requireUnchanged(
      FrozenClaimSnapshot frozen, ClaimCard current, FrozenClaimSemanticContext context) {
    FrozenClaimSnapshot candidate =
        freeze(
            frozen.problemHash(),
            frozen.rootGoalHash(),
            frozen.sourceRouteId(),
            current,
            context);
    if (!frozen.claimId().equals(candidate.claimId())
        || !frozen.claimStatementHash().equals(candidate.claimStatementHash())
        || !frozen.claimSemanticHash().equals(candidate.claimSemanticHash())
        || !frozen.dependencySnapshotHash().equals(candidate.dependencySnapshotHash())) {
      throw new IllegalArgumentException("FROZEN_CLAIM_MUTATION");
    }
  }

  private static List<String> claimDependencies(ClaimCard claim) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String dependency : claim.dependencies()) {
      if (dependency.startsWith("claim:")) {
        result.add(dependency.substring("claim:".length()));
      } else if (!dependency.startsWith("step:")) {
        result.add(dependency);
      }
    }
    claim.dependencyRefs().stream()
        .filter(node -> "local_claim".equals(node.path("kind").asText()))
        .map(node -> node.path("target_id").asText())
        .filter(value -> !value.isBlank())
        .forEach(result::add);
    return List.copyOf(result);
  }
}
