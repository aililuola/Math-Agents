package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerClaimSemanticContext;
import io.github.aililuola.mathproofmesh.contract.BrokerPromptArtifact;
import io.github.aililuola.mathproofmesh.contract.ReviewedObstructionPayload;
import java.util.List;

public final class BrokerArtifactPromptProjectionService {
  public static final int MAX_ARTIFACTS = 8;
  private static final int MAX_CONSEQUENCES = 2;
  private static final int MAX_BLOCKED_INFERENCES = 2;

  public List<BrokerPromptArtifact> project(List<BrokerArtifactEnvelope> artifacts) {
    return artifacts.stream().limit(MAX_ARTIFACTS).map(this::project).toList();
  }

  public BrokerPromptArtifact project(BrokerArtifactEnvelope artifact) {
    BrokerClaimSemanticContext context = BrokerArtifactSemanticKey.context(artifact.payload());
    String statement;
    List<String> assumptions;
    List<io.github.aililuola.mathproofmesh.contract.QuantifierSpec> quantifiers;
    List<String> scope;
    String polarity;
    if (context != null) {
      statement = context.statement();
      assumptions = context.assumptions();
      quantifiers = context.quantifiers();
      scope = context.scopeLimitations();
      polarity = context.polarity();
    } else {
      ReviewedObstructionPayload obstruction = (ReviewedObstructionPayload) artifact.payload();
      statement = obstruction.failedInferenceStatement();
      assumptions = List.of();
      quantifiers = List.of();
      scope = List.of("exact failed proof step " + obstruction.exactFailedProofStepId());
      polarity = "negative";
    }
    return new BrokerPromptArtifact(
        artifact.artifactId(), artifact.artifactType(), artifact.authority(), statement,
        assumptions, quantifiers, scope, polarity, artifact.sourceClaimRevisionId(),
        artifact.evidenceRefs(), artifact.reusableConsequences().stream().limit(MAX_CONSEQUENCES).toList(),
        artifact.blockedInferences().stream().limit(MAX_BLOCKED_INFERENCES).toList(),
        artifact.nextExactObligationId(), allowedUses(artifact.artifactType()));
  }

  public static List<BrokerArtifactUseKind> allowedUses(BrokerArtifactType type) {
    return switch (type) {
      case VERIFIED_CLAIM -> List.of(
          BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP,
          BrokerArtifactUseKind.SUPPORTS_CLAIM,
          BrokerArtifactUseKind.CITED_IN_FINAL_PROOF);
      case VERIFIED_COUNTEREXAMPLE -> List.of(
          BrokerArtifactUseKind.REFUTES_CLAIM,
          BrokerArtifactUseKind.RETIRES_DEPENDENCY,
          BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT);
      case VERIFIED_NO_GO -> List.of(
          BrokerArtifactUseKind.RETIRES_DEPENDENCY,
          BrokerArtifactUseKind.SELECTS_FOCUS_OBLIGATION,
          BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT);
      case REVIEWED_OBSTRUCTION -> List.of(
          BrokerArtifactUseKind.SELECTS_FOCUS_OBLIGATION,
          BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
          BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT);
      case REUSABLE_CONSTRUCTION -> List.of(
          BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP,
          BrokerArtifactUseKind.SUPPORTS_CLAIM);
      case EXACT_EXAMPLE, BOUNDED_OBSERVATION ->
          List.of(BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN);
      case FORMAL_CERTIFICATE -> List.of(
          BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP,
          BrokerArtifactUseKind.SUPPORTS_CLAIM,
          BrokerArtifactUseKind.CITED_IN_FINAL_PROOF);
    };
  }

  public String instruction() {
    return "Merely receiving an artifact does not mean it was used. "
        + "Any mathematical use must cite artifact_id in broker_artifact_use_manifest and "
        + "declare a typed use; an artifact omitted from that manifest is recorded as NOT_USED. "
        + "A REVIEWED_OPEN obstruction cannot be used as a proved premise. "
        + "A BOUNDED observation cannot establish an unrestricted Claim.";
  }
}
