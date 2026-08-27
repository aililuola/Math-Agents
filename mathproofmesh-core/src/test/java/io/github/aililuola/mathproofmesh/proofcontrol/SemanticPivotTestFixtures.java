package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewBatch;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotReviewDecision;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SemanticPivotTestFixtures {
  static final String PROBLEM_HASH = "problem-hash";
  static final String ROOT_HASH = "root-hash";
  static final String ROUTE_ID = "route-1";
  static final String SOURCE_ID = "strategy-source";
  static final String PROPOSED_ID = "strategy-pivot-epoch";
  static final String OBSTRUCTION_ID = "counterexample-1";
  static final String OLD_OBJECT = "prefix-hitting-family";
  static final String NEW_OBJECT = "global-minimal-hitting-family";
  static final String OLD_TARGET = "canonical-prefix-stability";
  static final String OLD_OBLIGATION = "obligation-prefix-stability";
  static final String NEW_OBLIGATION = "obligation-large-prime-reduction";
  static final String VERIFIED_CLAIM = "claim-hitting-set-equivalence";

  private SemanticPivotTestFixtures() {}

  static StrategyCard sourceStrategy() {
    return strategy(
        SOURCE_ID,
        List.of(),
        "Prove prefix minimal hitting sets stabilize",
        "Study monotonicity of prefix minimal hitting sets",
        "Prefix minimal hitting-set family H_n",
        List.of("Show H_n is monotone"));
  }

  static StrategyCard proposedStrategy() {
    return strategy(
        PROPOSED_ID,
        List.of(SOURCE_ID),
        "Reduce large primes in a global minimal hitting set",
        "Study inclusion-minimal hitting sets for the global support family",
        "Global inclusion-minimal hitting-set family",
        List.of("Establish the large-prime support reduction"));
  }

  static StrategyCard textOnlyStrategy() {
    return strategy(
        "strategy-text-only",
        List.of(SOURCE_ID),
        sourceStrategy().bottleneck(),
        sourceStrategy().coreIdea() + " Restated with different prose.",
        sourceStrategy().independenceBasis(),
        sourceStrategy().expectedLemmas());
  }

  static PivotObstructionRef obstruction() {
    return new PivotObstructionRef(
        OBSTRUCTION_ID,
        PivotEvidenceAuthority.VERIFIED_COUNTEREXAMPLE,
        "attempt-artifact://counterexample-1",
        ROUTE_ID,
        SOURCE_ID,
        OLD_TARGET,
        "statement-hash");
  }

  static MathematicalObjectChange objectReplacement() {
    return new MathematicalObjectChange(
        OLD_OBJECT,
        "finite prefix minimal hitting-set family H_n",
        PivotObjectDisposition.REPLACE,
        NEW_OBJECT,
        "inclusion-minimal hitting sets of the global support family",
        "The global family retains the hitting-set formulation while dropping false prefix monotonicity.",
        List.of(OBSTRUCTION_ID));
  }

  static PivotDirectionChange directionChange() {
    return new PivotDirectionChange(
        "prefix-stability-forward",
        "global-large-prime-reduction",
        "The counterexample invalidates prefix monotonicity, so reduce large primes globally.",
        List.of(OBSTRUCTION_ID));
  }

  static PivotClaimUseChange retainedClaim() {
    return new PivotClaimUseChange(
        VERIFIED_CLAIM,
        "verified-claim-hash",
        PivotClaimUsageAction.RETAIN_AS_VERIFIED_FACT,
        "The equivalence remains valid under the new global object.");
  }

  static List<PivotObligationChange> obligationChanges() {
    return List.of(
        new PivotObligationChange(
            OLD_OBLIGATION,
            OLD_TARGET,
            PivotObligationAction.RETIRE_FROM_STRATEGY_FOCUS,
            null,
            null,
            List.of(),
            List.of(),
            "The exact counterexample makes this direction non-load-bearing."),
        new PivotObligationChange(
            NEW_OBLIGATION,
            null,
            PivotObligationAction.ADD_NEW_OBLIGATION,
            "If p>a1 divides a_n, find j<n with Pi(a_j)=Pi(a_n) without p.",
            ObligationKind.SUBGOAL,
            List.of("p>a1", "p divides a_n"),
            List.of(),
            "This is the load-bearing reduction for the global target."));
  }

  static PivotDelta validDelta() {
    return PivotDelta.create(
        PROBLEM_HASH,
        ROOT_HASH,
        ROUTE_ID,
        SOURCE_ID,
        List.of(
            PivotTransformationType.OBJECT_REPLACEMENT,
            PivotTransformationType.TARGET_REFORMULATION,
            PivotTransformationType.REPRESENTATION_CHANGE),
        List.of(obstruction()),
        List.of(objectReplacement()),
        List.of(directionChange()),
        List.of(),
        List.of(retainedClaim()),
        obligationChanges(),
        proposedStrategy(),
        "Replace the refuted prefix-stability object with a global reduction object.");
  }

  static PivotDelta textOnlyDelta() {
    return PivotDelta.create(
        PROBLEM_HASH,
        ROOT_HASH,
        ROUTE_ID,
        SOURCE_ID,
        List.of(),
        List.of(obstruction()),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        textOnlyStrategy(),
        "Only prose changed.");
  }

  static PivotStructuralSignature sourceSignature() {
    return new PivotStructuralSignature(
        SOURCE_ID,
        Set.of(OLD_OBJECT),
        Set.of(OLD_TARGET),
        Set.of("assumption-hash"),
        Set.of(VERIFIED_CLAIM),
        Set.of(),
        Set.of("old-obligation-hash"),
        "prefix-stability-forward",
        "blueprint-old");
  }

  static PivotStructuralSignature proposedSignature() {
    return new PivotStructuralSignature(
        PROPOSED_ID,
        Set.of(NEW_OBJECT),
        Set.of("canonical-global-large-prime"),
        Set.of("assumption-hash"),
        Set.of(VERIFIED_CLAIM),
        Set.of(),
        Set.of("new-obligation-hash"),
        "global-large-prime-reduction",
        "blueprint-new");
  }

  static PivotStructuralSignature textOnlySignature() {
    PivotStructuralSignature source = sourceSignature();
    return new PivotStructuralSignature(
        textOnlyStrategy().strategyId(),
        source.activeObjectIds(),
        source.activeCanonicalTargetIds(),
        source.activeAssumptionHashes(),
        source.retainedVerifiedClaimIds(),
        source.proposedClaimHashes(),
        source.activeObligationSignatures(),
        source.directionSignature(),
        source.blueprintStructureHash());
  }

  static PivotAuthorityContext authority() {
    PivotObstructionRef obstruction = obstruction();
    return new PivotAuthorityContext(
        PROBLEM_HASH,
        ROOT_HASH,
        ROUTE_ID,
        SOURCE_ID,
        Map.of(
            OBSTRUCTION_ID,
            new PivotAuthorityContext.KnownObstruction(obstruction, PROBLEM_HASH, true)),
        Set.of(OLD_OBJECT),
        Set.of(OLD_TARGET),
        Set.of(OLD_OBLIGATION),
        Set.of(VERIFIED_CLAIM),
        Set.of(VERIFIED_CLAIM),
        Map.of(VERIFIED_CLAIM, "verified-claim-hash"),
        Set.of(),
        null,
        Set.of(),
        false,
        true);
  }

  static SemanticPivotReviewBatch acceptedReview(PivotDelta delta) {
    return review(delta, "reviewer", VerificationVerdict.PASS, 0.99d, true);
  }

  static SemanticPivotReviewBatch review(
      PivotDelta delta,
      String reviewer,
      VerificationVerdict verdict,
      double confidence,
      boolean dimensionsValid) {
    return new SemanticPivotReviewBatch(
        "review-report",
        reviewer,
        "proposer",
        List.of(
            new SemanticPivotReviewDecision(
                delta.pivotId(),
                verdict,
                confidence,
                dimensionsValid,
                dimensionsValid,
                dimensionsValid,
                dimensionsValid,
                dimensionsValid,
                dimensionsValid,
                dimensionsValid,
                List.of(),
                "Independent bounded semantic review.")),
        "artifact://review",
        new UsageRecord());
  }

  static SemanticPivotController.Preparation prepared() {
    return new SemanticPivotController()
        .prepare(
            validDelta(),
            sourceSignature(),
            proposedSignature(),
            authority(),
            "proposer",
            acceptedReview(validDelta()),
            0.9d);
  }

  private static StrategyCard strategy(
      String id,
      List<String> parents,
      String bottleneck,
      String coreIdea,
      String independenceBasis,
      List<String> expectedLemmas) {
    return new StrategyCard(
        null,
        bottleneck,
        List.of(),
        List.of(),
        List.of(),
        coreIdea,
        List.of(),
        0.4d,
        0.7d,
        expectedLemmas,
        "Search for a bounded counterexample to the load-bearing reduction.",
        independenceBasis,
        null,
        null,
        parents,
        List.of("positive integer sequence"),
        id,
        List.of("gcd", "hitting-set"),
        "Hitting-set route");
  }
}
