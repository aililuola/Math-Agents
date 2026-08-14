package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable semantic models shared by the proof-control services. */
public final class ProofControlModels {
  private ProofControlModels() {}

  @SuppressFBWarnings(
      value = "IMPROPER_UNICODE",
      justification = "Mode is an ASCII allowlist parsed with Locale.ROOT")
  public enum Mode {
    OFF,
    SHADOW,
    ACTIVE;

    public static Mode parse(String value) {
      return switch (required(value, "mode").toLowerCase(java.util.Locale.ROOT)) {
        case "off" -> OFF;
        case "shadow" -> SHADOW;
        case "active" -> ACTIVE;
        default -> throw new IllegalArgumentException(
            "unsupported proof-control mode: " + value);
      };
    }
  }

  public enum GoalRelation {
    EQUIVALENT,
    SUFFICIENT,
    NECESSARY_ONLY,
    HEURISTIC_ONLY,
    UNRELATED,
    UNKNOWN
  }

  public enum ScopeRelation {
    SAME,
    CLAIM_STRONGER,
    CLAIM_WEAKER,
    INCOMPARABLE,
    UNKNOWN
  }

  public enum ProofRole {
    CORE_BRIDGE,
    AUXILIARY_BOUND,
    NECESSARY_CONDITION,
    SUFFICIENT_CONDITION,
    EQUIVALENT_REDUCTION,
    TECHNICAL_LEMMA,
    SEARCH_HEURISTIC,
    COUNTEREXAMPLE
  }

  public enum AssumptionDomain {
    MATHEMATICAL,
    SEARCH,
    PROTOCOL,
    PROCESS,
    TOOL,
    VERIFICATION,
    SAFETY
  }

  public enum ObligationDomain {
    MATHEMATICAL,
    SEARCH,
    PROCESS,
    TOOL,
    VERIFICATION,
    PROTOCOL,
    SAFETY;

    public boolean mathematicalControlEligible() {
      return this == MATHEMATICAL;
    }
  }

  public enum ObligationKind {
    MAIN_GOAL,
    LEMMA,
    CONSTRUCTION,
    COMPUTATION_QUESTION,
    COUNTERMODEL,
    PROCESS_TASK
  }

  public enum ObligationStatus {
    OPEN,
    IN_PROGRESS,
    CLOSED,
    BLOCKED,
    DEFERRED,
    REFUTED,
    REOPENED
  }

  public enum IndexScope {
    ALL,
    EVENTUAL,
    FINITE_PREFIX,
    BOUNDED_RANGE,
    SINGLE_INSTANCE,
    UNKNOWN
  }

  public enum UniformityScope {
    UNIFORM,
    POINTWISE,
    EXISTS_PER_INSTANCE,
    UNKNOWN
  }

  public enum ObjectScope {
    FULL_OBJECT,
    PROJECTION,
    QUOTIENT,
    RESIDUE_CLASSES,
    SUBSTRUCTURE,
    UNKNOWN
  }

  public enum GateVerdict {
    PASS,
    BLOCK,
    REWRITE,
    SHADOW_BLOCK
  }

  public enum ControlActionType {
    CREATE_SUB_OBLIGATION,
    BIND_ROUTE_TARGET,
    REWRITE_BLUEPRINT,
    WEAKEN_TARGET,
    CREATE_MINIMAL_BRIDGE,
    CREATE_COUNTERMODEL_TASK,
    EXECUTE_COUNTERMODEL_TASK,
    ACTIVATE_INDUCTION_MEASURE,
    CREATE_ASSUMPTION_CHALLENGER,
    EXECUTE_ASSUMPTION_CHALLENGER,
    MATERIALIZE_BOTTLENECK_CLUSTER,
    MATERIALIZE_FALSIFICATION_TASK,
    SCHEDULE_ROUTE_UPDATE,
    DEFER_INSPIRATION_REVIEW,
    REASSIGN_INSPIRATION_REVIEW,
    EXECUTE_META_PIVOT,
    REQUEST_DIRECT_PREMISE_REVIEW
  }

  public enum ControlActionStatus {
    PROPOSED,
    ADMITTED,
    EXECUTING,
    APPLIED,
    REJECTED,
    DEFERRED,
    FAILED_RETRYABLE,
    FAILED_TERMINAL
  }

  public enum TaskStatus {
    CREATED,
    NEEDS_REWRITE,
    ASSIGNED,
    READY,
    RUNNING,
    COMPLETED,
    INCONCLUSIVE,
    DEFERRED,
    BLOCKED,
    FAILED,
    EXPIRED
  }

  public enum WakeConditionKind {
    PROVIDER_AVAILABLE,
    BUDGET_AVAILABLE,
    DEPENDENCY_FACT_AVAILABLE,
    OBLIGATION_STATE_CHANGED,
    REVIEWER_AVAILABLE,
    TASK_RECOMPILED,
    USER_INTERVENTION,
    CONFIG_CHANGED,
    ROUND_ADVANCED
  }

  public enum DependencyKind {
    LOCAL_STEP,
    LOCAL_CLAIM,
    GLOBAL_FACT,
    MESSAGE,
    OBLIGATION,
    TOOL_CERTIFICATE,
    FORMAL_CERTIFICATE,
    EXTERNAL_RESULT
  }

  public enum InferenceRiskType {
    NECESSARY_TO_SUFFICIENT,
    EVENTUAL_TO_GLOBAL,
    POINTWISE_TO_UNIFORM,
    FINITE_RANGE_TO_FINITE_STATE,
    IMAGE_INCLUSION_TO_SURJECTIVITY,
    PROJECTION_TO_ORIGINAL,
    LOCAL_TO_GLOBAL,
    EXISTENCE_TO_UNIFORM_EXISTENCE,
    PAIRWISE_TO_COMMON_WITNESS,
    EMPIRICAL_TO_UNIVERSAL,
    PARTIAL_PROPERTY_TO_TOTAL_PROPERTY,
    NONEMPTY_INTERSECTION_TO_SUBSET_CONTAINMENT,
    EXISTS_COMPONENT_TO_ALL_COMPONENTS,
    SOME_WITNESS_TO_ALL_WITNESSES,
    COVERAGE_TO_EXHAUSTIVENESS,
    AT_LEAST_ONE_TO_ONLY_FROM_SET,
    WRONG_DIRECTION,
    QUANTIFIER_SWAP,
    DEPENDENCY_MISSING,
    SCOPE_MISMATCH,
    AMBIGUOUS_SEMANTIC_LEAP
  }

  public enum SetRelationKind {
    NONEMPTY_INTERSECTION,
    SUBSET,
    SUPERSET,
    EQUALITY,
    COVER,
    PARTITION,
    UNKNOWN
  }

  public enum PropertyStrength {
    EXISTENTIAL,
    PARTIAL,
    UNIVERSAL,
    EXHAUSTIVE
  }

  public enum BlueprintNodeKind {
    GIVEN,
    CLAIM,
    LEMMA,
    CONSTRUCTION,
    CASE_SPLIT,
    COUNTERMODEL_TASK,
    COMPUTATION_TASK,
    TARGET
  }

  public enum RewriteSemanticVerdict {
    VALID,
    TAUTOLOGICAL,
    PLACEHOLDER,
    NO_EXECUTABLE_STEP,
    NO_TARGET,
    LOST_DOMAIN_MECHANISM,
    SCOPE_INVALID,
    DUPLICATE
  }

  public enum FailureClass {
    EXECUTION,
    BRIDGE,
    PLAN,
    FRAMING
  }

  public enum RealizerFailureType {
    ADMISSIBILITY,
    LOWER_BOUND,
    UPPER_BOUND,
    DEGENERACY,
    SCOPE,
    STRICT_DESCENT,
    UNKNOWN
  }

  public enum FalsificationCompilationStatus {
    EXECUTABLE,
    NEEDS_REWRITE,
    NON_AUTOMATABLE
  }

  public enum FalsificationOutcome {
    COUNTEREXAMPLE_FOUND,
    NOT_REFUTED_BOUNDED,
    INCONCLUSIVE,
    BLOCKED
  }

  public enum MessageExpectedEffect {
    CLOSE,
    REDUCE,
    REFUTE,
    REWRITE,
    PROVIDE_CONSTRUCTION
  }

  public enum BroadcastDecision {
    BROADCAST,
    KEEP_LOCAL,
    REJECT
  }

  public enum ResumeDecisionKind {
    RESUME_WORK,
    NO_RESUMABLE_WORK,
    REOPEN_REQUIRED
  }

  public enum MetaPivotStatus {
    NONE,
    REQUESTED,
    ADMITTED,
    EXECUTING,
    APPLIED,
    EVALUATED,
    FAILED
  }

  public enum MetaPivotEffect {
    EFFECTIVE,
    MATERIALIZED_NO_GAIN,
    PROPOSAL_ONLY,
    EMPTY,
    DEFERRED,
    FAILED
  }

  public record Quantifier(String kind, String domain, int order) {
    public Quantifier {
      kind = required(kind, "kind").toLowerCase(java.util.Locale.ROOT);
      domain = required(domain, "domain");
      if (order < 0) {
        throw new IllegalArgumentException("order must be nonnegative");
      }
    }
  }

  public record ScopeSignature(
      String subjectId,
      IndexScope indexScope,
      UniformityScope uniformity,
      ObjectScope objectScope,
      List<Quantifier> quantifiers,
      List<String> domainConstraints,
      List<String> exceptionalCases,
      double confidence) {
    public ScopeSignature {
      subjectId = required(subjectId, "subjectId");
      indexScope = Objects.requireNonNull(indexScope, "indexScope");
      uniformity = Objects.requireNonNull(uniformity, "uniformity");
      objectScope = Objects.requireNonNull(objectScope, "objectScope");
      quantifiers = copy(quantifiers);
      domainConstraints = copy(domainConstraints);
      exceptionalCases = copy(exceptionalCases);
      unit(confidence, "confidence");
    }

    @Override
    public List<Quantifier> quantifiers() {
      return List.copyOf(quantifiers);
    }

    @Override
    public List<String> domainConstraints() {
      return List.copyOf(domainConstraints);
    }

    @Override
    public List<String> exceptionalCases() {
      return List.copyOf(exceptionalCases);
    }
  }

  public record Obligation(
      String id,
      String statement,
      ObligationKind kind,
      ObligationStatus status,
      List<String> assumptions,
      List<String> routeIds,
      double priority,
      double centrality) {
    public Obligation {
      id = required(id, "id");
      statement = required(statement, "statement");
      kind = Objects.requireNonNull(kind, "kind");
      status = Objects.requireNonNull(status, "status");
      assumptions = copy(assumptions);
      routeIds = copy(routeIds);
      if (priority < 0.0d || centrality < 0.0d) {
        throw new IllegalArgumentException("priority and centrality must be nonnegative");
      }
    }

    @Override
    public List<String> assumptions() {
      return List.copyOf(assumptions);
    }

    @Override
    public List<String> routeIds() {
      return List.copyOf(routeIds);
    }
  }

  public record Strategy(
      String id,
      String title,
      String mechanism,
      List<String> prerequisites,
      List<String> criticalClaims,
      List<String> expectedLemmas,
      List<String> falsificationTests,
      List<String> domainObjects,
      String routeId) {
    public Strategy {
      id = required(id, "id");
      title = required(title, "title");
      mechanism = required(mechanism, "mechanism");
      prerequisites = copy(prerequisites);
      criticalClaims = copy(criticalClaims);
      expectedLemmas = copy(expectedLemmas);
      falsificationTests = copy(falsificationTests);
      domainObjects = copy(domainObjects);
      routeId = routeId == null ? "" : routeId.strip();
    }

    @Override
    public List<String> prerequisites() {
      return List.copyOf(prerequisites);
    }

    @Override
    public List<String> criticalClaims() {
      return List.copyOf(criticalClaims);
    }

    @Override
    public List<String> expectedLemmas() {
      return List.copyOf(expectedLemmas);
    }

    @Override
    public List<String> falsificationTests() {
      return List.copyOf(falsificationTests);
    }

    @Override
    public List<String> domainObjects() {
      return List.copyOf(domainObjects);
    }
  }

  public record GoalLink(
      String linkId,
      String subjectId,
      String targetObligationId,
      GoalRelation relation,
      ScopeRelation scopeRelation,
      List<String> implicationOutline,
      List<String> remainingObligationIds,
      List<String> requiredBridgeIds,
      double minimalityScore,
      double confidence,
      List<String> evidenceRefs) {
    public GoalLink {
      linkId = required(linkId, "linkId");
      subjectId = required(subjectId, "subjectId");
      targetObligationId = required(targetObligationId, "targetObligationId");
      relation = Objects.requireNonNull(relation, "relation");
      scopeRelation = Objects.requireNonNull(scopeRelation, "scopeRelation");
      implicationOutline = copy(implicationOutline);
      remainingObligationIds = copy(remainingObligationIds);
      requiredBridgeIds = copy(requiredBridgeIds);
      unit(minimalityScore, "minimalityScore");
      unit(confidence, "confidence");
      evidenceRefs = copy(evidenceRefs);
    }

    @Override
    public List<String> implicationOutline() {
      return List.copyOf(implicationOutline);
    }

    @Override
    public List<String> remainingObligationIds() {
      return List.copyOf(remainingObligationIds);
    }

    @Override
    public List<String> requiredBridgeIds() {
      return List.copyOf(requiredBridgeIds);
    }

    @Override
    public List<String> evidenceRefs() {
      return List.copyOf(evidenceRefs);
    }
  }

  public record RelationSignature(
      SetRelationKind setRelation,
      PropertyStrength propertyStrength,
      String semanticRole) {
    public RelationSignature {
      setRelation = Objects.requireNonNull(setRelation, "setRelation");
      propertyStrength = Objects.requireNonNull(propertyStrength, "propertyStrength");
      semanticRole = required(semanticRole, "semanticRole");
    }
  }

  public record InferenceRisk(
      String id,
      InferenceRiskType type,
      String subjectId,
      List<String> premiseIds,
      String conclusionId,
      double confidence,
      String reason,
      boolean open) {
    public InferenceRisk {
      id = required(id, "id");
      type = Objects.requireNonNull(type, "type");
      subjectId = required(subjectId, "subjectId");
      premiseIds = copy(premiseIds);
      conclusionId = conclusionId == null ? "" : conclusionId.strip();
      unit(confidence, "confidence");
      reason = required(reason, "reason");
    }

    @Override
    public List<String> premiseIds() {
      return List.copyOf(premiseIds);
    }

    public boolean blocksFactPromotion() {
      return open && confidence >= 0.5d;
    }
  }

  public record DependencyRef(
      DependencyKind kind,
      String targetId,
      String sourceAttemptId,
      String sourceDeltaId,
      String sourceRouteId,
      String contentHash,
      String migrationAudit) {
    public DependencyRef {
      kind = Objects.requireNonNull(kind, "kind");
      targetId = required(targetId, "targetId");
      sourceAttemptId = blankToNull(sourceAttemptId);
      sourceDeltaId = blankToNull(sourceDeltaId);
      sourceRouteId = blankToNull(sourceRouteId);
      contentHash = blankToNull(contentHash);
      migrationAudit = blankToNull(migrationAudit);
    }

    public String canonicalKey() {
      return kind.name().toLowerCase(java.util.Locale.ROOT) + ":" + targetId;
    }
  }

  public record WakeCondition(
      String id, WakeConditionKind kind, String targetId, int earliestRound,
      boolean satisfied) {
    public WakeCondition {
      id = required(id, "id");
      kind = Objects.requireNonNull(kind, "kind");
      targetId = targetId == null ? "" : targetId.strip();
      if (earliestRound < 0) {
        throw new IllegalArgumentException("earliestRound must be nonnegative");
      }
    }
  }

  public record ResumeDecision(
      String id,
      ResumeDecisionKind decision,
      String stateHash,
      List<String> pendingActionIds,
      List<String> wakeableTaskIds,
      List<String> deferredTaskIds,
      String interventionActionId,
      String reason) {
    public ResumeDecision {
      id = required(id, "id");
      decision = Objects.requireNonNull(decision, "decision");
      stateHash = required(stateHash, "stateHash");
      pendingActionIds = copy(pendingActionIds);
      wakeableTaskIds = copy(wakeableTaskIds);
      deferredTaskIds = copy(deferredTaskIds);
      interventionActionId = blankToNull(interventionActionId);
      reason = required(reason, "reason");
    }

    @Override
    public List<String> pendingActionIds() {
      return List.copyOf(pendingActionIds);
    }

    @Override
    public List<String> wakeableTaskIds() {
      return List.copyOf(wakeableTaskIds);
    }

    @Override
    public List<String> deferredTaskIds() {
      return List.copyOf(deferredTaskIds);
    }
  }

  public record Assumption(
      String id,
      String statement,
      Set<String> routeIds,
      AssumptionDomain domain,
      boolean independentlyVerified,
      Set<String> typedDependencyIds,
      double loadBearingScore) {
    public Assumption {
      id = required(id, "id");
      statement = required(statement, "statement");
      routeIds = routeIds == null ? Set.of() : Set.copyOf(routeIds);
      domain = Objects.requireNonNull(domain, "domain");
      typedDependencyIds =
          typedDependencyIds == null ? Set.of() : Set.copyOf(typedDependencyIds);
      unit(loadBearingScore, "loadBearingScore");
    }

    @Override
    public Set<String> routeIds() {
      return Set.copyOf(routeIds);
    }

    @Override
    public Set<String> typedDependencyIds() {
      return Set.copyOf(typedDependencyIds);
    }
  }

  public record AssumptionFamily(
      String id,
      String canonicalStatement,
      List<String> memberIds,
      Set<String> liveRouteIds,
      Set<String> typedDependencyClosure,
      double commonModeRisk,
      boolean dependencyCutset) {
    public AssumptionFamily {
      id = required(id, "id");
      canonicalStatement = required(canonicalStatement, "canonicalStatement");
      memberIds = copy(memberIds);
      liveRouteIds = liveRouteIds == null ? Set.of() : Set.copyOf(liveRouteIds);
      typedDependencyClosure =
          typedDependencyClosure == null ? Set.of() : Set.copyOf(typedDependencyClosure);
      unit(commonModeRisk, "commonModeRisk");
    }

    @Override
    public List<String> memberIds() {
      return List.copyOf(memberIds);
    }

    @Override
    public Set<String> liveRouteIds() {
      return Set.copyOf(liveRouteIds);
    }

    @Override
    public Set<String> typedDependencyClosure() {
      return Set.copyOf(typedDependencyClosure);
    }
  }

  public record MetaPivotOutcome(
      String pivotId,
      MetaPivotEffect effect,
      List<String> attemptedMechanisms,
      List<String> completedMechanisms,
      List<String> materialStateRefs,
      List<WakeCondition> wakeConditions,
      String reason) {
    public MetaPivotOutcome {
      pivotId = required(pivotId, "pivotId");
      effect = Objects.requireNonNull(effect, "effect");
      attemptedMechanisms = copy(attemptedMechanisms);
      completedMechanisms = copy(completedMechanisms);
      materialStateRefs = copy(materialStateRefs);
      wakeConditions = copy(wakeConditions);
      reason = required(reason, "reason");
    }

    @Override
    public List<String> attemptedMechanisms() {
      return List.copyOf(attemptedMechanisms);
    }

    @Override
    public List<String> completedMechanisms() {
      return List.copyOf(completedMechanisms);
    }

    @Override
    public List<String> materialStateRefs() {
      return List.copyOf(materialStateRefs);
    }

    @Override
    public List<WakeCondition> wakeConditions() {
      return List.copyOf(wakeConditions);
    }
  }

  static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }

  static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String result = value.strip();
    return result.isEmpty() ? null : result;
  }

  static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  static <K, V> Map<K, V> copy(Map<K, V> values) {
    return values == null ? Map.of() : Map.copyOf(values);
  }

  static void unit(double value, String field) {
    if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
      throw new IllegalArgumentException(field + " must be in [0, 1]");
    }
  }
}
