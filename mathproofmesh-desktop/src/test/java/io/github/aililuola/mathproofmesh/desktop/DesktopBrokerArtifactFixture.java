package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactCompilationRequest;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactCompiler;
import io.github.aililuola.mathproofmesh.communication.artifact.BrokerArtifactSourceKind;
import io.github.aililuola.mathproofmesh.communication.artifact.MathematicalArtifactBroker;
import io.github.aililuola.mathproofmesh.communication.artifact.RouteMathematicalNeedProfile;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.contract.BrokerBlockedInference;
import io.github.aililuola.mathproofmesh.contract.BrokerClaimSemanticContext;
import io.github.aililuola.mathproofmesh.contract.BrokerReusableConsequence;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.ReviewedObstructionPayload;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import io.github.aililuola.mathproofmesh.contract.VerifiedClaimPayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedCounterexamplePayload;
import java.util.List;
import java.util.Set;

final class DesktopBrokerArtifactFixture {
  static final String PROBLEM_HASH = "9".repeat(64);
  static final String ROOT_HASH = "8".repeat(64);

  final MathematicalArtifactBroker broker;
  private final String problemHash;
  private final String rootHash;

  DesktopBrokerArtifactFixture() {
    this(new MathematicalArtifactBroker(), PROBLEM_HASH, ROOT_HASH);
  }

  DesktopBrokerArtifactFixture(
      MathematicalArtifactBroker broker, String problemHash, String rootHash) {
    this.broker = java.util.Objects.requireNonNull(broker, "broker");
    this.problemHash = java.util.Objects.requireNonNull(problemHash, "problemHash");
    this.rootHash = java.util.Objects.requireNonNull(rootHash, "rootHash");
  }

  BrokerArtifactEnvelope artifact(String suffix, String sourceRoute) {
    BrokerClaimSemanticContext context = context(suffix);
    BrokerArtifactCompilationRequest request =
        new BrokerArtifactCompilationRequest(
            problemHash,
            rootHash,
            BrokerArtifactType.VERIFIED_CLAIM,
            new VerifiedClaimPayload(context),
            BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED,
            sourceRoute,
            "attempt-" + suffix,
            "claim-" + suffix,
            "revision-" + suffix,
            List.of("target-" + suffix),
            List.of("source-step-" + suffix),
            List.of("artifact://proof/" + suffix),
            List.of(
                new BrokerReusableConsequence(
                    "The exact lemma can close target " + suffix + ".",
                    List.of("target-" + suffix),
                    List.of(context.claimSemanticHash()),
                    List.of("finite-tree"))),
            List.of(),
            List.of("claim-" + suffix),
            "target-" + suffix,
            0,
            30,
            true,
            true);
    return new BrokerArtifactCompiler().compile(request).artifact();
  }

  BrokerArtifactEnvelope counterexample(String suffix, String sourceRoute) {
    BrokerClaimSemanticContext context = context(suffix);
    return compile(
        suffix,
        sourceRoute,
        BrokerArtifactType.VERIFIED_COUNTEREXAMPLE,
        new VerifiedCounterexamplePayload(
            context,
            "claim-" + suffix,
            context.claimSemanticHash(),
            "The path on four vertices is an exact witness " + suffix + ".",
            List.of("artifact://counterexample/" + suffix),
            List.of("target-" + suffix)),
        BrokerArtifactSourceKind.VERIFIED_COUNTEREXAMPLE,
        List.of(),
        List.of(
            new BrokerBlockedInference(
                context.statement(),
                List.of(context.claimSemanticHash()),
                List.of("target-" + suffix))));
  }

  BrokerArtifactEnvelope obstruction(String suffix, String sourceRoute) {
    return compile(
        suffix,
        sourceRoute,
        BrokerArtifactType.REVIEWED_OBSTRUCTION,
        new ReviewedObstructionPayload(
            "failed-step-" + suffix,
            "Surjectivity was used as injectivity without the finite-cardinality bridge.",
            List.of("claim-retained-" + suffix),
            "MISSING_JUSTIFICATION",
            "LOCAL_PATCH",
            "Prove the finite-cardinality bridge.",
            "target-" + suffix,
            List.of("artifact://proof-audit/" + suffix)),
        BrokerArtifactSourceKind.REVIEWED_PROOF_OBSTRUCTION,
        List.of(),
        List.of(
            new BrokerBlockedInference(
                "Surjective therefore injective without finite-cardinality assumptions.",
                List.of("semantic-" + suffix),
                List.of("target-" + suffix))));
  }

  private BrokerArtifactEnvelope compile(
      String suffix,
      String sourceRoute,
      BrokerArtifactType type,
      io.github.aililuola.mathproofmesh.contract.BrokerArtifactPayload payload,
      BrokerArtifactSourceKind sourceKind,
      List<BrokerReusableConsequence> consequences,
      List<BrokerBlockedInference> blockedInferences) {
    BrokerArtifactCompilationRequest request =
        new BrokerArtifactCompilationRequest(
            problemHash,
            rootHash,
            type,
            payload,
            sourceKind,
            sourceRoute,
            "attempt-" + suffix,
            "claim-" + suffix,
            "revision-" + suffix,
            List.of("target-" + suffix),
            List.of("source-step-" + suffix),
            List.of("artifact://evidence/" + suffix),
            consequences,
            blockedInferences,
            List.of("claim-retained-" + suffix),
            "target-" + suffix,
            0,
            30,
            true,
            true);
    return new BrokerArtifactCompiler().compile(request).artifact();
  }

  RouteMathematicalNeedProfile related(String routeId, String suffix) {
    return new RouteMathematicalNeedProfile(
        routeId,
        Set.of("target-" + suffix),
        Set.of("claim-" + suffix, context(suffix).claimSemanticHash()),
        Set.of("claim-" + suffix, context(suffix).claimSemanticHash()),
        Set.of(),
        Set.of("finite-tree"),
        Set.of(),
        "strategy-" + routeId);
  }

  RouteMathematicalNeedProfile unrelated(String routeId) {
    return new RouteMathematicalNeedProfile(
        routeId,
        Set.of("target-linear-map"),
        Set.of("claim-rank"),
        Set.of("claim-rank"),
        Set.of(),
        Set.of("linear-map"),
        Set.of(),
        "strategy-" + routeId);
  }

  RouteMathematicalNeedProfile relatedObstruction(String routeId, String suffix) {
    return new RouteMathematicalNeedProfile(
        routeId,
        Set.of("target-" + suffix),
        Set.of(),
        Set.of("semantic-" + suffix),
        Set.of(),
        Set.of("finite-set"),
        Set.of("MISSING_JUSTIFICATION"),
        "strategy-" + routeId);
  }

  BrokerArtifactUseManifest use(
      String requestId, BrokerArtifactEnvelope artifact, BrokerArtifactUseKind kind) {
    return new BrokerArtifactUseManifest(
        requestId,
        List.of(
            new BrokerArtifactUseClaim(
                artifact.artifactId(),
                kind,
                List.of("step-use"),
                List.of("claim-derived"),
                List.of(artifact.nextExactObligationId()),
                "The exact artifact supplies step-use.")));
  }

  private static BrokerClaimSemanticContext context(String suffix) {
    return new BrokerClaimSemanticContext(
        "Every finite tree " + suffix + " has a leaf.",
        "the tree has a leaf",
        List.of("the graph is a finite tree"),
        List.of(new QuantifierSpec("G", "finite trees", "forall", 0, List.of(), "G")),
        List.of(new VariableBinding(List.of(), "G", "finite trees", "claim", "G")),
        List.of("global"),
        "positive",
        "statement-" + suffix,
        "semantic-" + suffix,
        List.of());
  }
}
