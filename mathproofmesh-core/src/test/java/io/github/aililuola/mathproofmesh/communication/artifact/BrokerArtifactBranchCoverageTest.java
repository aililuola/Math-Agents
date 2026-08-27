package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.BoundedObservationPayload;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactAuthority;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactEnvelope;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactPayload;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactType;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerBlockedInference;
import io.github.aililuola.mathproofmesh.contract.BrokerClaimSemanticContext;
import io.github.aililuola.mathproofmesh.contract.BrokerReusableConsequence;
import io.github.aililuola.mathproofmesh.contract.BrokerVerifiedEffectType;
import io.github.aililuola.mathproofmesh.contract.ExactExamplePayload;
import io.github.aililuola.mathproofmesh.contract.FormalCertificatePayload;
import io.github.aililuola.mathproofmesh.contract.ReusableConstructionPayload;
import io.github.aililuola.mathproofmesh.contract.ReviewedObstructionPayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedClaimPayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedCounterexamplePayload;
import io.github.aililuola.mathproofmesh.contract.VerifiedNoGoPayload;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactBranchCoverageTest {
  private static final BrokerClaimSemanticContext CONTEXT =
      BrokerArtifactTestFixtures.context("forall", "global", "positive");

  @Test
  void authorityMatrixRequiresTheExactTrustedSourceAndActiveProjection() {
    BrokerArtifactAuthorityResolver resolver = new BrokerArtifactAuthorityResolver();
    List<AuthorityCase> cases =
        List.of(
            new AuthorityCase(
                BrokerArtifactType.VERIFIED_CLAIM,
                new VerifiedClaimPayload(CONTEXT),
                BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED,
                BrokerArtifactAuthority.VERIFIED),
            new AuthorityCase(
                BrokerArtifactType.VERIFIED_COUNTEREXAMPLE,
                counterexample(),
                BrokerArtifactSourceKind.VERIFIED_COUNTEREXAMPLE,
                BrokerArtifactAuthority.REFUTED),
            new AuthorityCase(
                BrokerArtifactType.VERIFIED_NO_GO,
                noGo(),
                BrokerArtifactSourceKind.REFUTED_STATEMENT,
                BrokerArtifactAuthority.REFUTED),
            new AuthorityCase(
                BrokerArtifactType.REVIEWED_OBSTRUCTION,
                obstruction(),
                BrokerArtifactSourceKind.REVIEWED_PROOF_OBSTRUCTION,
                BrokerArtifactAuthority.REVIEWED_OPEN),
            new AuthorityCase(
                BrokerArtifactType.REUSABLE_CONSTRUCTION,
                new ReusableConstructionPayload(CONTEXT),
                BrokerArtifactSourceKind.VERIFIED_CONSTRUCTION,
                BrokerArtifactAuthority.VERIFIED),
            new AuthorityCase(
                BrokerArtifactType.EXACT_EXAMPLE,
                new ExactExamplePayload("path P4", CONTEXT),
                BrokerArtifactSourceKind.AUDITED_EXACT_EXAMPLE,
                BrokerArtifactAuthority.VERIFIED),
            new AuthorityCase(
                BrokerArtifactType.FORMAL_CERTIFICATE,
                new FormalCertificatePayload(CONTEXT, "certificate://tree"),
                BrokerArtifactSourceKind.TRUSTED_FORMAL_CERTIFICATE,
                BrokerArtifactAuthority.VERIFIED),
            new AuthorityCase(
                BrokerArtifactType.BOUNDED_OBSERVATION,
                new BoundedObservationPayload("checked through four vertices", CONTEXT),
                BrokerArtifactSourceKind.BOUNDED_EVIDENCE,
                BrokerArtifactAuthority.BOUNDED));

    for (AuthorityCase authorityCase : cases) {
      assertThat(resolver.resolve(request(authorityCase, true, true)))
          .contains(authorityCase.authority());
      assertThat(resolver.resolve(request(authorityCase, false, true))).isEmpty();
      assertThat(resolver.resolve(request(authorityCase, true, false))).isEmpty();
      assertThat(
              resolver.resolve(
                  request(
                      authorityCase.type(),
                      authorityCase.payload(),
                      BrokerArtifactSourceKind.MODEL_DECLARATION,
                      true,
                      true)))
          .isEmpty();
      assertThat(resolver.compatible(authorityCase.type(), authorityCase.authority())).isTrue();
    }

    AuthorityCase boundedExample =
        new AuthorityCase(
            BrokerArtifactType.EXACT_EXAMPLE,
            new ExactExamplePayload("path P4", CONTEXT),
            BrokerArtifactSourceKind.BOUNDED_EVIDENCE,
            BrokerArtifactAuthority.BOUNDED);
    assertThat(resolver.resolve(request(boundedExample, true, true)))
        .contains(BrokerArtifactAuthority.BOUNDED);
    assertThat(
            resolver.compatible(
                BrokerArtifactType.EXACT_EXAMPLE, BrokerArtifactAuthority.BOUNDED))
        .isTrue();
    assertThat(
            resolver.compatible(
                BrokerArtifactType.VERIFIED_CLAIM, BrokerArtifactAuthority.REFUTED))
        .isFalse();
  }

  @Test
  void targetingUsesEveryExactMathematicalNeedAndNeverTheSourceRoute() {
    BrokerArtifactTargetingService targeting = new BrokerArtifactTargetingService();
    BrokerArtifactCompilationRequest base =
        request(
            BrokerArtifactType.VERIFIED_CLAIM,
            new VerifiedClaimPayload(CONTEXT),
            BrokerArtifactSourceKind.CLAIM_COURT_VERIFIED,
            true,
            true);
    BrokerArtifactEnvelope artifact =
        compile(
            new BrokerArtifactCompilationRequest(
                base.problemHash(),
                base.rootGoalHash(),
                base.artifactType(),
                base.payload(),
                base.sourceKind(),
                base.sourceRouteId(),
                base.sourceAttemptId(),
                "claim-tree",
                base.sourceClaimRevisionId(),
                base.sourceObligationIds(),
                base.sourceProofStepIds(),
                base.evidenceRefs(),
                List.of(
                    new BrokerReusableConsequence(
                        "tree induction consequence",
                        List.of("target-tree"),
                        List.of("consequence-semantic"),
                        List.of("finite-tree"))),
                List.of(
                    new BrokerBlockedInference(
                        "the failed shortcut",
                        List.of("blocked-semantic"),
                        List.of("blocked-target"))),
                base.retainedVerifiedClaimIds(),
                base.nextExactObligationId(),
                base.roundCreated(),
                base.ttlRounds(),
                true,
                true));
    RouteMathematicalNeedProfile allNeeds =
        new RouteMathematicalNeedProfile(
            "route-target",
            Set.of("target-tree", "blocked-target"),
            Set.of("claim-tree", CONTEXT.claimSemanticHash()),
            Set.of(
                "claim-tree",
                CONTEXT.claimSemanticHash(),
                "tree-connected",
                "consequence-semantic",
                "blocked-semantic"),
            Set.of(),
            Set.of("finite-tree"),
            Set.of(),
            "epoch-target");

    BrokerArtifactRelevanceDecision decision = targeting.decide(artifact, allNeeds);
    assertThat(decision.relevant()).isTrue();
    assertThat(decision.reasons())
        .containsExactly(
            "EXACT_REQUIRED_CLAIM_ID",
            "EXACT_DEPENDENCY_CLAIM_ID",
            "EXACT_REQUIRED_CLAIM",
            "EXACT_DEPENDENCY_CLAIM",
            "DEPENDENCY_CLAIM_ID",
            "CANONICAL_TARGET",
            "CONSEQUENCE_CLAIM_KEY",
            "OBJECT_ROLE",
            "BLOCKED_CANONICAL_TARGET",
            "BLOCKED_DEPENDENCY");
    assertThat(targeting.decide(artifact, BrokerArtifactTestFixtures.related("route-source")).reasons())
        .containsExactly("SOURCE_ROUTE");
    assertThat(targeting.decide(artifact, BrokerArtifactTestFixtures.unrelated("route-other")))
        .extracting(
            BrokerArtifactRelevanceDecision::relevant,
            BrokerArtifactRelevanceDecision::priority)
        .containsExactly(false, 0);
  }

  @Test
  void specializedArtifactsMatchOnlyTheirExactTargetsAndAuthorityPriorities() {
    BrokerArtifactTargetingService targeting = new BrokerArtifactTargetingService();
    BrokerArtifactEnvelope counterexample =
        compile(
            request(
                BrokerArtifactType.VERIFIED_COUNTEREXAMPLE,
                counterexample(),
                BrokerArtifactSourceKind.VERIFIED_COUNTEREXAMPLE,
                true,
                true));
    assertRelevant(
        targeting,
        counterexample,
        profile(Set.of("counterexample-semantic"), Set.of(), Set.of(), Set.of()));
    assertRelevant(
        targeting,
        counterexample,
        profile(Set.of(), Set.of("counterexample-semantic"), Set.of(), Set.of()));
    BrokerArtifactRelevanceDecision counterexampleTarget =
        targeting.decide(
            counterexample,
            profile(Set.of(), Set.of(), Set.of("target-counterexample"), Set.of()));
    assertThat(counterexampleTarget.relevant()).isTrue();
    assertThat(counterexampleTarget.priority()).isGreaterThan(450);
    assertNotRelevant(targeting, counterexample, profile(Set.of(), Set.of(), Set.of(), Set.of()));

    BrokerArtifactEnvelope noGo =
        compile(
            request(
                BrokerArtifactType.VERIFIED_NO_GO,
                noGo(),
                BrokerArtifactSourceKind.REFUTED_STATEMENT,
                true,
                true));
    assertRelevant(
        targeting, noGo, profile(Set.of(), Set.of(CONTEXT.claimSemanticHash()), Set.of(), Set.of()));
    assertNotRelevant(targeting, noGo, profile(Set.of(), Set.of(), Set.of(), Set.of()));

    BrokerArtifactEnvelope obstruction =
        compile(
            request(
                BrokerArtifactType.REVIEWED_OBSTRUCTION,
                obstruction(),
                BrokerArtifactSourceKind.REVIEWED_PROOF_OBSTRUCTION,
                true,
                true));
    BrokerArtifactRelevanceDecision obstructionDecision =
        targeting.decide(
            obstruction, profile(Set.of(), Set.of(), Set.of(), Set.of("MISSING_JUSTIFICATION")));
    assertThat(obstructionDecision.priority()).isGreaterThan(200);
    assertNotRelevant(targeting, obstruction, profile(Set.of(), Set.of(), Set.of(), Set.of()));

    BrokerArtifactEnvelope bounded =
        compile(
            request(
                BrokerArtifactType.BOUNDED_OBSERVATION,
                new BoundedObservationPayload("checked through four vertices", CONTEXT),
                BrokerArtifactSourceKind.BOUNDED_EVIDENCE,
                true,
                true));
    BrokerArtifactRelevanceDecision boundedDecision =
        targeting.decide(
            bounded,
            profile(Set.of(CONTEXT.claimSemanticHash()), Set.of(), Set.of(), Set.of()));
    assertThat(boundedDecision.priority()).isBetween(100, 199);
  }

  @Test
  void effectVerificationCoversEachExplicitUseAndRejectsUnchangedState() {
    BrokerArtifactEffectVerifier verifier = new BrokerArtifactEffectVerifier();
    BrokerDeliveryBaseline baseline = baseline();
    BrokerArtifactEffectObservation combined =
        observation(
            Set.of("step-downstream"),
            Set.of("claim-downstream"),
            Set.of(),
            Set.of("obligation-downstream"),
            Set.of(),
            "focus-after",
            null,
            null,
            null,
            false,
            7.5d);
    BrokerArtifactEffectVerifier.Verification ordinary =
        verifier.verify(lineage(BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP), baseline, combined);
    assertThat(ordinary.effectTypes())
        .containsExactly(
            BrokerVerifiedEffectType.COMMITTED_STEP_REUSE,
            BrokerVerifiedEffectType.VERIFIED_CLAIM_DERIVED,
            BrokerVerifiedEffectType.OBLIGATION_CLOSED);
    assertThat(ordinary.affectedDownstreamIds())
        .containsExactly("step-downstream", "claim-downstream", "obligation-downstream");
    assertThat(ordinary.proofDebtReduction()).isEqualTo(2.5d);
    assertThat(ordinary.verified()).isTrue();

    assertEffect(
        verifier,
        baseline,
        BrokerArtifactUseKind.REFUTES_CLAIM,
        observation(
            Set.of(), Set.of(), Set.of("claim-downstream"), Set.of(), Set.of(), null,
            null, null, null, false, 10.0d),
        BrokerVerifiedEffectType.EXACT_CLAIM_REFUTED);
    assertEffect(
        verifier,
        baseline,
        BrokerArtifactUseKind.RETIRES_DEPENDENCY,
        observation(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of("claim-downstream"), null,
            null, null, null, false, 10.0d),
        BrokerVerifiedEffectType.DEPENDENCY_RETIRED);
    assertEffect(
        verifier,
        baseline,
        BrokerArtifactUseKind.SELECTS_FOCUS_OBLIGATION,
        observation(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "focus-after", null, null,
            null, false, 10.0d),
        BrokerVerifiedEffectType.FOCUS_CHANGED);
    assertEffect(
        verifier,
        baseline,
        BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
        observation(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, "repair-1", null,
            null, false, 10.0d),
        BrokerVerifiedEffectType.LOCAL_REPAIR_BOUND);
    assertEffect(
        verifier,
        baseline,
        BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT,
        observation(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, null, "pivot-1",
            null, false, 10.0d),
        BrokerVerifiedEffectType.SEMANTIC_PIVOT_BOUND);
    assertEffect(
        verifier,
        baseline,
        BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN,
        observation(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, null, null,
            "plan-1", false, 10.0d),
        BrokerVerifiedEffectType.COMPUTATION_PLAN_BOUND);
    assertEffect(
        verifier,
        baseline,
        BrokerArtifactUseKind.CITED_IN_FINAL_PROOF,
        observation(
            Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null, null, null,
            null, true, 10.0d),
        BrokerVerifiedEffectType.FINAL_PROOF_CITATION);

    BrokerArtifactEffectVerifier.Verification unchanged =
        verifier.verify(
            lineage(BrokerArtifactUseKind.SUPPORTS_CLAIM),
            baseline,
            observation(
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "focus-before", null,
                null, null, false, 12.0d));
    assertThat(unchanged.verified()).isFalse();
    assertThat(unchanged.effectTypes()).isEmpty();
    assertThat(unchanged.affectedDownstreamIds()).isEmpty();
    assertThat(unchanged.proofDebtReduction()).isZero();

    for (BrokerArtifactUseKind kind :
        List.of(
            BrokerArtifactUseKind.REFUTES_CLAIM,
            BrokerArtifactUseKind.RETIRES_DEPENDENCY,
            BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
            BrokerArtifactUseKind.TRIGGERS_SEMANTIC_PIVOT,
            BrokerArtifactUseKind.SUPPORTS_COMPUTATION_PLAN,
            BrokerArtifactUseKind.CITED_IN_FINAL_PROOF)) {
      assertNoEffect(verifier, baseline, kind, null);
    }
    assertNoEffect(
        verifier, baseline, BrokerArtifactUseKind.SELECTS_FOCUS_OBLIGATION, null);
    assertNoEffect(
        verifier,
        baseline,
        BrokerArtifactUseKind.SELECTS_FOCUS_OBLIGATION,
        "focus-before");
  }

  @Test
  void semanticIdentityIncludesEveryTypedContextAndExactCounterexampleWitness() {
    List<BrokerArtifactPayload> contextualPayloads =
        List.of(
            new VerifiedClaimPayload(CONTEXT),
            counterexample(),
            noGo(),
            new ReusableConstructionPayload(CONTEXT),
            new ExactExamplePayload("path P4", CONTEXT),
            new FormalCertificatePayload(CONTEXT, "certificate://tree"),
            new BoundedObservationPayload("checked through four vertices", CONTEXT));
    for (BrokerArtifactPayload payload : contextualPayloads) {
      assertThat(BrokerArtifactSemanticKey.context(payload)).isEqualTo(CONTEXT);
    }
    assertThat(BrokerArtifactSemanticKey.context(obstruction())).isNull();

    String firstWitness =
        BrokerArtifactSemanticKey.of(
            BrokerArtifactTestFixtures.PROBLEM_HASH,
            BrokerArtifactTestFixtures.ROOT_HASH,
            BrokerArtifactType.VERIFIED_COUNTEREXAMPLE,
            BrokerArtifactAuthority.REFUTED,
            counterexample(),
            "revision-1");
    String secondWitness =
        BrokerArtifactSemanticKey.of(
            BrokerArtifactTestFixtures.PROBLEM_HASH,
            BrokerArtifactTestFixtures.ROOT_HASH,
            BrokerArtifactType.VERIFIED_COUNTEREXAMPLE,
            BrokerArtifactAuthority.REFUTED,
            new VerifiedCounterexamplePayload(
                CONTEXT,
                "claim-counterexample",
                "counterexample-semantic",
                "cycle C4",
                List.of("experiment://counterexample"),
                List.of("target-counterexample")),
            "revision-1");
    String noGoKey =
        BrokerArtifactSemanticKey.of(
            BrokerArtifactTestFixtures.PROBLEM_HASH,
            BrokerArtifactTestFixtures.ROOT_HASH,
            BrokerArtifactType.VERIFIED_NO_GO,
            BrokerArtifactAuthority.REFUTED,
            noGo(),
            "revision-1");
    assertThat(firstWitness).isNotEqualTo(secondWitness).isNotEqualTo(noGoKey);
  }

  private static BrokerArtifactCompilationRequest request(
      AuthorityCase authorityCase, boolean authorityValid, boolean projectionActive) {
    return request(
        authorityCase.type(),
        authorityCase.payload(),
        authorityCase.sourceKind(),
        authorityValid,
        projectionActive);
  }

  private static BrokerArtifactCompilationRequest request(
      BrokerArtifactType type,
      BrokerArtifactPayload payload,
      BrokerArtifactSourceKind sourceKind,
      boolean authorityValid,
      boolean projectionActive) {
    return new BrokerArtifactCompilationRequest(
        BrokerArtifactTestFixtures.PROBLEM_HASH,
        BrokerArtifactTestFixtures.ROOT_HASH,
        type,
        payload,
        sourceKind,
        "route-source",
        "attempt-source",
        "claim-source",
        "revision-source",
        List.of("target-source"),
        List.of("step-source"),
        List.of("artifact://source"),
        List.of(),
        List.of(),
        List.of(),
        "target-source",
        0,
        20,
        authorityValid,
        projectionActive);
  }

  private static BrokerArtifactEnvelope compile(BrokerArtifactCompilationRequest request) {
    BrokerArtifactCompilationResult result = new BrokerArtifactCompiler().compile(request);
    assertThat(result.accepted()).isTrue();
    return result.artifact();
  }

  private static VerifiedCounterexamplePayload counterexample() {
    return new VerifiedCounterexamplePayload(
        CONTEXT,
        "claim-counterexample",
        "counterexample-semantic",
        "path P4",
        List.of("experiment://counterexample"),
        List.of("target-counterexample"));
  }

  private static VerifiedNoGoPayload noGo() {
    return new VerifiedNoGoPayload(CONTEXT, "claim-no-go", "the shortcut is invalid");
  }

  private static ReviewedObstructionPayload obstruction() {
    return new ReviewedObstructionPayload(
        "step-failed",
        "an unjustified implication",
        List.of("claim-retained"),
        "MISSING_JUSTIFICATION",
        "repairable",
        "prove the missing implication",
        "target-repair",
        List.of("audit://proof"));
  }

  private static RouteMathematicalNeedProfile profile(
      Set<String> required,
      Set<String> dependencies,
      Set<String> targets,
      Set<String> issueKinds) {
    return new RouteMathematicalNeedProfile(
        "route-target",
        targets,
        required,
        dependencies,
        Set.of(),
        Set.of(),
        issueKinds,
        "epoch-target");
  }

  private static void assertRelevant(
      BrokerArtifactTargetingService targeting,
      BrokerArtifactEnvelope artifact,
      RouteMathematicalNeedProfile profile) {
    assertThat(targeting.decide(artifact, profile).relevant()).isTrue();
  }

  private static void assertNotRelevant(
      BrokerArtifactTargetingService targeting,
      BrokerArtifactEnvelope artifact,
      RouteMathematicalNeedProfile profile) {
    assertThat(targeting.decide(artifact, profile).relevant()).isFalse();
  }

  private static BrokerDeliveryBaseline baseline() {
    return new BrokerDeliveryBaseline(
        "delivery-1",
        "route-target",
        "provider-request-1",
        0,
        10.0d,
        Set.of("obligation-downstream"),
        Set.of(),
        Set.of(),
        "epoch-before",
        "focus-before");
  }

  private static BrokerArtifactLineageRecord lineage(BrokerArtifactUseKind useKind) {
    BrokerArtifactLineageRecord lineage = new BrokerArtifactLineageRecord(
        "lineage-" + useKind.name(),
        "artifact-1",
        "delivery-1",
        useKind,
        List.of("step-downstream"),
        List.of("claim-downstream"),
        List.of("obligation-downstream"),
        null,
        null,
        "provider-request-1",
        false);
    return switch (useKind) {
      case TRIGGERS_LOCAL_REPAIR -> lineage.bindEffectTarget("repair-1");
      case TRIGGERS_SEMANTIC_PIVOT -> lineage.bindEffectTarget("pivot-1");
      case SUPPORTS_COMPUTATION_PLAN -> lineage.bindEffectTarget("plan-1");
      default -> lineage;
    };
  }

  private static BrokerArtifactEffectObservation observation(
      Set<String> steps,
      Set<String> verifiedClaims,
      Set<String> refutedClaims,
      Set<String> closedObligations,
      Set<String> retiredDependencies,
      String focus,
      String repair,
      String pivot,
      String computation,
      boolean cited,
      double proofDebt) {
    return new BrokerArtifactEffectObservation(
        steps,
        verifiedClaims,
        refutedClaims,
        closedObligations,
        retiredDependencies,
        focus,
        repair,
        pivot,
        computation,
        cited,
        proofDebt);
  }

  private static void assertEffect(
      BrokerArtifactEffectVerifier verifier,
      BrokerDeliveryBaseline baseline,
      BrokerArtifactUseKind kind,
      BrokerArtifactEffectObservation observation,
      BrokerVerifiedEffectType expected) {
    BrokerArtifactEffectVerifier.Verification verification =
        verifier.verify(lineage(kind), baseline, observation);
    assertThat(verification.effectTypes()).contains(expected);
    assertThat(verification.verified()).isTrue();
  }

  private static void assertNoEffect(
      BrokerArtifactEffectVerifier verifier,
      BrokerDeliveryBaseline baseline,
      BrokerArtifactUseKind kind,
      String focus) {
    BrokerArtifactEffectVerifier.Verification verification =
        verifier.verify(
            lineage(kind),
            baseline,
            observation(
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), focus, null, null, null,
                false, 10.0d));
    assertThat(verification.verified()).isFalse();
  }

  private record AuthorityCase(
      BrokerArtifactType type,
      BrokerArtifactPayload payload,
      BrokerArtifactSourceKind sourceKind,
      BrokerArtifactAuthority authority) {}
}
