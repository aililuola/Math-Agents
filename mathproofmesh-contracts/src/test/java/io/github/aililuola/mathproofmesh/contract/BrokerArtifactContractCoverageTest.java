package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrokerArtifactContractCoverageTest {
  @Test
  void completeTypedArtifactFamilyPreservesItsExactSemanticContext() {
    List<String> assumptions = new ArrayList<>(List.of("G is a finite tree"));
    List<QuantifierSpec> quantifiers =
        List.of(new QuantifierSpec("G", "finite graphs", "forall", 0, List.of(), "G"));
    List<VariableBinding> bindings =
        List.of(new VariableBinding(List.of("graph"), "G", "finite graphs", "claim", "G"));
    BrokerClaimSemanticContext context =
        new BrokerClaimSemanticContext(
            "Every finite tree has a leaf.",
            "G has a leaf",
            assumptions,
            quantifiers,
            bindings,
            List.of("finite trees"),
            "positive",
            "statement-hash",
            "semantic-hash",
            List.of("claim-connected"));
    assumptions.add("mutated after construction");

    BrokerReusableConsequence consequence =
        new BrokerReusableConsequence(
            "A leaf supports induction.",
            List.of("target-induction"),
            List.of("semantic-hash"),
            List.of("finite-tree"));
    BrokerBlockedInference blocked =
        new BrokerBlockedInference(
            "Surjective does not imply injective without finiteness.",
            List.of("claim-injective"),
            List.of("target-bijection"));
    BrokerArtifactUseClaim use =
        new BrokerArtifactUseClaim(
            "artifact-tree",
            BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP,
            List.of("step-2"),
            List.of("claim-leaf"),
            List.of("target-induction"),
            "Use the leaf claim in the induction step.");
    BrokerArtifactUseManifest manifest =
        new BrokerArtifactUseManifest("request-tree", List.of(use));

    List<BrokerArtifactPayload> payloads =
        List.of(
            new VerifiedClaimPayload(context),
            new VerifiedCounterexamplePayload(
                context,
                "claim-hamiltonian",
                "semantic-hash",
                "the path P4",
                List.of("experiment://p4"),
                List.of("target-hamiltonian")),
            new VerifiedNoGoPayload(
                context, "claim-hamiltonian", "The universal Hamiltonian inference is false."),
            new ReviewedObstructionPayload(
                "step-surjective",
                "surjective implies injective",
                List.of("claim-surjective"),
                "MISSING_JUSTIFICATION",
                "repairable",
                "finite equal-cardinality bridge",
                "target-cardinality",
                List.of("audit://surjective")),
            new ReusableConstructionPayload(context),
            new ExactExamplePayload("the path P4", context),
            new FormalCertificatePayload(context, "certificate://tree"),
            new BoundedObservationPayload(
                "all graphs on at most four vertices were checked", context));

    BrokerArtifactEnvelope envelope =
        new BrokerArtifactEnvelope(
            "artifact-tree",
            "problem-hash",
            "root-goal-hash",
            BrokerArtifactType.VERIFIED_CLAIM,
            BrokerArtifactAuthority.VERIFIED,
            payloads.getFirst(),
            "route-a",
            "attempt-a",
            "claim-leaf",
            "revision-leaf",
            List.of("target-induction"),
            List.of("step-1"),
            List.of("proof://leaf"),
            List.of(consequence),
            List.of(blocked),
            List.of("claim-connected"),
            "target-induction",
            2,
            5,
            "artifact-semantic-hash",
            "artifact-content-hash",
            "1");
    BrokerPromptArtifact prompt =
        new BrokerPromptArtifact(
            envelope.artifactId(),
            envelope.artifactType(),
            envelope.authority(),
            context.statement(),
            context.assumptions(),
            context.quantifiers(),
            context.scopeLimitations(),
            context.polarity(),
            envelope.sourceClaimRevisionId(),
            envelope.evidenceRefs(),
            envelope.reusableConsequences(),
            envelope.blockedInferences(),
            envelope.nextExactObligationId(),
            List.of(
                BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP,
                BrokerArtifactUseKind.CITED_IN_FINAL_PROOF));

    assertEquals(List.of("G is a finite tree"), context.assumptions());
    assertEquals(1, context.quantifiers().size());
    assertEquals(1, context.variableBindings().size());
    assertEquals(List.of("finite trees"), context.scopeLimitations());
    assertEquals(List.of("claim-connected"), context.dependencyClaimIds());
    assertEquals(List.of("target-induction"), consequence.canonicalTargetIds());
    assertEquals(List.of("semantic-hash"), consequence.claimSemanticKeys());
    assertEquals(List.of("finite-tree"), consequence.objectRoleIds());
    assertEquals(List.of("claim-injective"), blocked.claimSemanticKeys());
    assertEquals(List.of("target-bijection"), blocked.canonicalTargetIds());
    assertEquals(List.of("step-2"), use.referencedProofStepIds());
    assertEquals(List.of("claim-leaf"), use.affectedClaimIds());
    assertEquals(List.of("target-induction"), use.affectedObligationIds());
    assertEquals(List.of(use), manifest.uses());
    assertEquals(List.of("target-induction"), envelope.sourceObligationIds());
    assertEquals(List.of("step-1"), envelope.sourceProofStepIds());
    assertEquals(List.of("proof://leaf"), envelope.evidenceRefs());
    assertEquals(List.of(consequence), envelope.reusableConsequences());
    assertEquals(List.of(blocked), envelope.blockedInferences());
    assertEquals(List.of("claim-connected"), envelope.retainedVerifiedClaimIds());
    assertEquals(List.of("G is a finite tree"), prompt.assumptions());
    assertEquals(1, prompt.quantifiers().size());
    assertEquals(List.of("finite trees"), prompt.scope());
    assertEquals(List.of("proof://leaf"), prompt.evidenceRefs());
    assertEquals(List.of(consequence), prompt.reusableConsequences());
    assertEquals(List.of(blocked), prompt.blockedInferences());
    assertEquals(
        List.of(
            BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP,
            BrokerArtifactUseKind.CITED_IN_FINAL_PROOF),
        prompt.allowedUseKinds());
    assertEquals(BrokerArtifactType.values().length, payloads.size());
    assertEquals(4, BrokerArtifactAuthority.values().length);
    assertEquals(6, BrokerArtifactReceiptStatus.values().length);
    assertEquals(10, BrokerVerifiedEffectType.values().length);
    assertFalse(BrokerArtifactUseKind.values().length == 0);
  }

  @Test
  void boundedAndUseManifestContractsFailClosed() {
    BrokerClaimSemanticContext unbounded =
        new BrokerClaimSemanticContext(
            "P(x)",
            "P(x)",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "positive",
            "statement-hash",
            "semantic-hash",
            List.of());
    ContractValidationException missingScope =
        assertThrows(
            ContractValidationException.class,
            () -> new BoundedObservationPayload("finite check", unbounded));
    assertTrue(missingScope.getMessage().contains("scope limitations"));

    BrokerArtifactUseClaim use =
        new BrokerArtifactUseClaim(
            "artifact-1",
            BrokerArtifactUseKind.SUPPORTS_CLAIM,
            List.of(),
            List.of("claim-1"),
            List.of(),
            "supports claim 1");
    ContractValidationException duplicateUse =
        assertThrows(
            ContractValidationException.class,
            () -> new BrokerArtifactUseManifest("request-1", Arrays.asList(use, use)));
    assertTrue(duplicateUse.getMessage().contains("duplicate artifact use claim"));
  }
}
