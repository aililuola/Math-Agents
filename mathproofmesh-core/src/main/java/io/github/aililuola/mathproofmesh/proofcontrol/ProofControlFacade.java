package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
/**
 * Small composition root for proof control. Business decisions remain in the
 * focused services rather than accumulating in this facade.
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification =
        "This record is an explicit process-local composition root whose service identities "
            + "must be shared to preserve their idempotency ledgers")
public record ProofControlFacade(
    GoalAlignmentAnalyzer goalAlignment,
    ScopeGuard scopeGuard,
    InferenceRiskScanner inferenceRisks,
    StrategyBlueprintCompiler blueprintCompiler,
    RouteAdmissionGate routeAdmission,
    CommonModeAnalyzer commonMode,
    FalsificationService falsification,
    MessageUtilityController messageUtility,
    ProofControlGates gates,
    ControlActionDispatcher actions,
    ResumePlanner resume,
    MetaPivotController metaPivot,
    SemanticPivotController semanticPivots,
    ClaimLifecycleController claims) {
  public ProofControlFacade {
    java.util.Objects.requireNonNull(goalAlignment, "goalAlignment");
    java.util.Objects.requireNonNull(scopeGuard, "scopeGuard");
    java.util.Objects.requireNonNull(inferenceRisks, "inferenceRisks");
    java.util.Objects.requireNonNull(blueprintCompiler, "blueprintCompiler");
    java.util.Objects.requireNonNull(routeAdmission, "routeAdmission");
    java.util.Objects.requireNonNull(commonMode, "commonMode");
    java.util.Objects.requireNonNull(falsification, "falsification");
    java.util.Objects.requireNonNull(messageUtility, "messageUtility");
    java.util.Objects.requireNonNull(gates, "gates");
    java.util.Objects.requireNonNull(actions, "actions");
    java.util.Objects.requireNonNull(resume, "resume");
    java.util.Objects.requireNonNull(metaPivot, "metaPivot");
    java.util.Objects.requireNonNull(semanticPivots, "semanticPivots");
    java.util.Objects.requireNonNull(claims, "claims");
  }

  public static ProofControlFacade createDefault() {
    return new ProofControlFacade(
        new GoalAlignmentAnalyzer(),
        new ScopeGuard(),
        new InferenceRiskScanner(),
        new StrategyBlueprintCompiler(),
        new RouteAdmissionGate(),
        new CommonModeAnalyzer(),
        new FalsificationService(),
        new MessageUtilityController(),
        new ProofControlGates(),
        new ControlActionDispatcher(),
        new ResumePlanner(),
        new MetaPivotController(),
        new SemanticPivotController(),
        new ClaimLifecycleController());
  }
}
