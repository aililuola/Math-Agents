package io.github.aililuola.mathproofmesh.communication.artifact;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactPayload;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.contract.BrokerClaimSemanticContext;
import io.github.aililuola.mathproofmesh.contract.BrokerReusableConsequence;
import io.github.aililuola.mathproofmesh.contract.QuantifierSpec;
import io.github.aililuola.mathproofmesh.contract.VariableBinding;
import io.github.aililuola.mathproofmesh.contract.VerifiedClaimPayload;
import java.util.List;
import java.util.Set;

final class BrokerArtifactTestFixtures {
  static final String PROBLEM_HASH = "1".repeat(64);
  static final String ROOT_HASH = "2".repeat(64);

  private BrokerArtifactTestFixtures() {}

  static BrokerClaimSemanticContext context(String quantifierKind, String scope, String polarity) {
    QuantifierSpec quantifier =
        new QuantifierSpec("x", "finite graphs", quantifierKind, 0, List.of(), "x");
    VariableBinding binding =
        new VariableBinding(List.of(), "x", "finite graphs", "claim", "x");
    return new BrokerClaimSemanticContext(
        "Every finite tree with at least two vertices has at least two leaves.",
        "the tree has at least two leaves",
        List.of("the graph is a finite tree with at least two vertices"),
        List.of(quantifier),
        List.of(binding),
        List.of(scope),
        polarity,
        "statement-" + quantifierKind + "-" + scope + "-" + polarity,
        "semantic-" + quantifierKind + "-" + scope + "-" + polarity,
        List.of("tree-connected"));
  }

  static BrokerArtifactCompilationRequest request(
      BrokerArtifactType type,
      BrokerArtifactPayload payload,
      BrokerArtifactSourceKind sourceKind,
      String sourceRoute,
      String claimId,
      String revisionId,
      boolean authorityValid) {
    return new BrokerArtifactCompilationRequest(
        PROBLEM_HASH,
        ROOT_HASH,
        type,
        payload,
        sourceKind,
        sourceRoute,
        "attempt-1",
        claimId,
        revisionId,
        List.of("target-tree"),
        List.of("step-tree"),
        List.of("artifact://proof/tree"),
        List.of(
            new BrokerReusableConsequence(
                "A leaf supplies the induction reduction.",
                List.of("target-tree"),
                List.of("semantic-forall-global-positive"),
                List.of("finite-tree"))),
        List.of(),
        List.of(claimId),
        "target-tree",
        0,
        20,
        authorityValid,
        true);
  }

  static BrokerArtifactEnvelope verifiedClaim() {
    BrokerClaimSemanticContext context = context("forall", "global", "positive");
    return new BrokerArtifactCompiler()
        .compile(
            request(
                BrokerArtifactType.VERIFIED_CLAIM,
                new VerifiedClaimPayload(context),
                BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED,
                "route-a",
                "claim-tree",
                "revision-tree",
                true))
        .artifact();
  }

  static RouteMathematicalNeedProfile related(String routeId) {
    return new RouteMathematicalNeedProfile(
        routeId,
        Set.of("target-tree"),
        Set.of("claim-tree", "semantic-forall-global-positive"),
        Set.of("claim-tree", "semantic-forall-global-positive"),
        Set.of(),
        Set.of("finite-tree"),
        Set.of(),
        "strategy-1");
  }

  static RouteMathematicalNeedProfile unrelated(String routeId) {
    return new RouteMathematicalNeedProfile(
        routeId,
        Set.of("target-matrix"),
        Set.of("claim-rank"),
        Set.of("claim-rank"),
        Set.of(),
        Set.of("linear-map"),
        Set.of(),
        "strategy-2");
  }

  static DeliveryScenario delivered(double debt) {
    MathematicalArtifactBroker broker = new MathematicalArtifactBroker();
    BrokerArtifactEnvelope artifact = verifiedClaim();
    BrokerArtifactPublishResult publication =
        broker.publish(artifact, List.of(related("route-b")), 0, 8);
    BrokerArtifactPromptBatch prompt =
        broker.consumeForPrompt(
            "route-b",
            "provider-request-1",
            0,
            8,
            debt,
            Set.of("target-tree"),
            Set.of(),
            Set.of(),
            "strategy-1",
            "target-tree");
    return new DeliveryScenario(broker, artifact, publication, prompt);
  }

  static BrokerArtifactUseManifest useManifest(
      BrokerArtifactEnvelope artifact, BrokerArtifactUseKind kind) {
    return new BrokerArtifactUseManifest(
        "provider-request-1",
        List.of(
            new BrokerArtifactUseClaim(
                artifact.artifactId(),
                kind,
                List.of("downstream-step"),
                List.of("downstream-claim"),
                List.of("target-tree"),
                "The verified tree lemma supplies this exact proof step.")));
  }

  record DeliveryScenario(
      MathematicalArtifactBroker broker,
      BrokerArtifactEnvelope artifact,
      BrokerArtifactPublishResult publication,
      BrokerArtifactPromptBatch prompt) {}
}
