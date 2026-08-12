package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CalculationGateRecord;
import io.github.aililuola.mathproofmesh.contract.CalculationGateVerdict;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic check for route-critical numerical premises.
 *
 * <p>The gate adds no model call: declarations are compiled into existing typed
 * handlers and checked before checkpoint review.
 */
public final class CriticalCalculationGate {
  private static final Pattern EXPLICIT_SEQUENCE =
      Pattern.compile(
          "(?i)\\b(?:terms?|values?|prefix)\\b[^.]{0,80}"
              + "[-+]?\\d+(?:\\s*,\\s*[-+]?\\d+){2,}");
  private static final Pattern EXPLICIT_EXTREMUM =
      Pattern.compile(
          "(?i)\\b(?:direct\\s+computation|computed|calculation)"
              + "\\b[^.]{0,80}\\b(?:minimum|maximum|min|max)\\b[^.]{0,20}[-+]?\\d+");
  private static final Set<ComputationMethod> RESULT_ONLY =
      Set.of(
          ComputationMethod.SYMPY_SIMPLIFY,
          ComputationMethod.POLYNOMIAL_FACTOR);

  private final ToolBroker tools;
  private final boolean enabled;
  private final boolean requireDeclarations;
  private final int maxChecksPerArtifact;

  public CriticalCalculationGate(ToolBroker tools) {
    this(tools, true, true, 8);
  }

  public CriticalCalculationGate(
      ToolBroker tools,
      boolean enabled,
      boolean requireDeclarations,
      int maxChecksPerArtifact) {
    this.tools = java.util.Objects.requireNonNull(tools, "tools");
    this.enabled = enabled;
    this.requireDeclarations = requireDeclarations;
    if (maxChecksPerArtifact < 1 || maxChecksPerArtifact > 64) {
      throw new IllegalArgumentException("maxChecksPerArtifact must be in [1, 64]");
    }
    this.maxChecksPerArtifact = maxChecksPerArtifact;
  }

  public static Optional<String> calculationTrigger(List<String> fragments) {
    if (fragments == null) {
      return Optional.empty();
    }
    for (String fragment : fragments) {
      if (fragment == null || fragment.isBlank()) {
        continue;
      }
      String normalized = fragment.toLowerCase(Locale.ROOT);
      if (normalized.contains("induction")
          || normalized.contains("am-gm")
          || normalized.contains("finite classification")
          || normalized.contains("candidate period")
          || normalized.contains("derive")
          || normalized.contains("prove")) {
        continue;
      }
      if (EXPLICIT_SEQUENCE.matcher(fragment).find()
          || EXPLICIT_EXTREMUM.matcher(fragment).find()) {
        return Optional.of(fragment.trim());
      }
    }
    return Optional.empty();
  }

  public CalculationGateBatch evaluateStrategy(
      StrategyCard strategy, String pathId, String requestedBy) {
    return evaluate(
        strategy.strategyId(),
        "strategy",
        List.of(
            strategy.title(),
            strategy.coreIdea(),
            strategy.bottleneck(),
            strategy.falsificationTest()),
        strategy.calculationChecks(),
        pathId,
        null,
        requestedBy);
  }

  public CalculationGateBatch evaluateSteps(
      List<ProofStep> steps,
      String scopeType,
      String pathId,
      String parentCheckpointId,
      String requestedBy) {
    if (!Set.of("proof_step", "final_step").contains(scopeType)) {
      throw new IllegalArgumentException("scopeType must be proof_step or final_step");
    }
    List<CalculationGateRecord> records = new ArrayList<>();
    List<EvidenceRef> evidence = new ArrayList<>();
    for (ProofStep step : steps) {
      List<String> fragments = new ArrayList<>();
      fragments.add(step.statement());
      fragments.add(step.justification());
      fragments.addAll(step.calculations());
      CalculationGateBatch batch =
          evaluate(
              step.stepId(),
              scopeType,
              fragments,
              step.calculationChecks(),
              pathId,
              parentCheckpointId,
              requestedBy);
      records.addAll(batch.records());
      evidence.addAll(batch.evidenceRefs());
    }
    return batch(records, evidence);
  }

  private CalculationGateBatch evaluate(
      String scopeId,
      String scopeType,
      List<String> fragments,
      List<ToolRequest> requests,
      String pathId,
      String parentCheckpointId,
      String requestedBy) {
    if (!enabled) {
      return new CalculationGateBatch(true, List.of(), List.of());
    }
    Optional<String> trigger = calculationTrigger(fragments);
    List<ToolRequest> checks = requests == null ? List.of() : List.copyOf(requests);
    if (checks.size() > maxChecksPerArtifact) {
      CalculationGateRecord record =
          record(
              scopeId,
              scopeType,
              pathId,
              trigger.orElse("declared calculation checks"),
              CalculationGateVerdict.INVALID_CONTRACT,
              "calculation check count exceeds the configured artifact bound",
              null,
              null,
              null,
              List.of());
      return new CalculationGateBatch(false, List.of(record), List.of());
    }
    if (checks.isEmpty()) {
      if (requireDeclarations && trigger.isPresent()) {
        CalculationGateRecord record =
            record(
                scopeId,
                scopeType,
                pathId,
                trigger.get(),
                CalculationGateVerdict.MISSING_DECLARATION,
                "route-critical computed values require a typed calculation declaration",
                null,
                null,
                null,
                List.of());
        return new CalculationGateBatch(false, List.of(record), List.of());
      }
      return new CalculationGateBatch(true, List.of(), List.of());
    }

    List<CalculationGateRecord> records = new ArrayList<>();
    List<EvidenceRef> evidence = new ArrayList<>();
    for (ToolRequest request : checks) {
      Evaluation evaluation =
          execute(
              stableRequest(request),
              pathId,
              parentCheckpointId,
              requestedBy);
      if (evaluation.evidenceRef() != null) {
        evidence.add(evaluation.evidenceRef());
      }
      records.add(
          record(
              scopeId,
              scopeType,
              pathId,
              trigger.orElse("declared calculation check"),
              evaluation.verdict(),
              evaluation.reason(),
              evaluation.spec(),
              evaluation.result(),
              request,
              evaluation.evidenceRef() == null
                  ? List.of()
                  : List.of(evaluation.evidenceRef())));
    }
    return batch(records, evidence);
  }

  private Evaluation execute(
      ToolRequest request,
      String pathId,
      String parentCheckpointId,
      String requestedBy) {
    ComputationMethod method;
    try {
      method = ComputationMethod.fromValue(request.kind());
    } catch (RuntimeException exception) {
      return Evaluation.failure(
          CalculationGateVerdict.INVALID_CONTRACT,
          "unknown typed calculation method");
    }
    if (RESULT_ONLY.contains(method)) {
      return Evaluation.failure(
          CalculationGateVerdict.INVALID_CONTRACT,
          method.value()
              + " is not an assertion-checking typed method accepted by "
              + "the critical calculation gate");
    }
    ExperimentSpec compiled;
    try {
      compiled =
          rebind(
              tools.compile(request),
              pathId,
              parentCheckpointId,
              requestedBy);
    } catch (RuntimeException exception) {
      return Evaluation.failure(
          CalculationGateVerdict.INVALID_CONTRACT,
          bounded(exception.getMessage()));
    }
    ComputationBroker broker = tools.computationBroker();
    ComputationBroker.PreparedDecision prepared =
        broker.decide(compiled, ComputationContext.initial(pathId, 1));
    if (prepared.decision().decision() != ComputationDecisionStatus.ALLOW) {
      return new Evaluation(
          CalculationGateVerdict.INVALID_CONTRACT,
          prepared.decision().reason(),
          prepared.spec(),
          null,
          null);
    }
    ExperimentResult result =
        broker.runExperiment(prepared.spec(), prepared.decision());
    if (result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND) {
      return new Evaluation(
          CalculationGateVerdict.REFUTED,
          "The independently replayed generated result refutes the declared premise.",
          prepared.spec(),
          result,
          evidenceRef(result));
    }
    if (result.outcome() == ExperimentOutcome.ERROR
        || result.outcome() == ExperimentOutcome.INCONCLUSIVE) {
      return new Evaluation(
          CalculationGateVerdict.INCONCLUSIVE,
          result.error() == null
              ? "The typed calculation was inconclusive."
              : bounded(result.error()),
          prepared.spec(),
          result,
          null);
    }
    return new Evaluation(
        CalculationGateVerdict.PASSED,
        "The declared finite calculation was checked within its exact scope.",
        prepared.spec(),
        result,
        evidenceRef(result));
  }

  private static ToolRequest stableRequest(ToolRequest request) {
    ObjectNode identity =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    identity.put("kind", request.kind());
    identity.put("purpose", request.purpose());
    identity.set("arguments", request.arguments());
    identity.set("domains", request.domains());
    identity.put("max_cases", request.maxCases());
    String stableId =
        "critical-" + CanonicalJson.stableHash(identity).substring(0, 24);
    return new ToolRequest(
        request.arguments(),
        request.domains(),
        request.kind(),
        request.maxCases(),
        request.purpose(),
        stableId);
  }

  private static ExperimentSpec rebind(
      ExperimentSpec spec,
      String pathId,
      String parentCheckpointId,
      String requestedBy) {
    return new ExperimentSpec(
        spec.arguments(),
        spec.assumptions(),
        spec.broadSearch(),
        spec.decisionIfConfirmed(),
        spec.decisionIfRefuted(),
        spec.domains(),
        spec.exactArithmetic(),
        null,
        spec.experimentId(),
        spec.maxCases(),
        spec.method(),
        spec.noncomputationalAlternative(),
        parentCheckpointId,
        pathId,
        spec.purpose(),
        spec.reasoningBasis(),
        null,
        requestedBy,
        spec.runtimeFingerprint(),
        spec.seed(),
        spec.targetClaim(),
        spec.typedToolGap(),
        spec.whyComputationIsNeeded());
  }

  private static EvidenceRef evidenceRef(ExperimentResult result) {
    return new EvidenceRef(
        "computation://sha256/" + result.resultHash(),
        result.resultHash(),
        result.method().value(),
        result.outcome().value()
            + " within the declared computation scope");
  }

  private static CalculationGateRecord record(
      String scopeId,
      String scopeType,
      String pathId,
      String trigger,
      CalculationGateVerdict verdict,
      String reason,
      ExperimentSpec spec,
      ExperimentResult result,
      ToolRequest request,
      List<EvidenceRef> evidenceRefs) {
    ObjectNode identity =
        com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    identity.put("scope_id", scopeId);
    identity.put("scope_type", scopeType);
    identity.put("path_id", pathId);
    identity.put("trigger", trigger);
    if (request != null) {
      identity.put("request_id", request.requestId());
    }
    String gateId =
        "calcgate-" + CanonicalJson.stableHash(identity).substring(0, 24);
    return new CalculationGateRecord(
        null,
        evidenceRefs,
        spec == null ? null : spec.experimentId(),
        gateId,
        pathId,
        reason,
        request,
        spec == null ? null : spec.requestHash(),
        result == null ? null : result.resultHash(),
        scopeId,
        scopeType,
        trigger,
        verdict);
  }

  private static CalculationGateBatch batch(
      List<CalculationGateRecord> records, List<EvidenceRef> evidence) {
    boolean passed =
        records.stream()
            .allMatch(record -> record.verdict() == CalculationGateVerdict.PASSED);
    return new CalculationGateBatch(passed, records, evidence);
  }

  private static String bounded(String message) {
    String value = message == null ? "invalid calculation contract" : message;
    return value.length() <= 1_000 ? value : value.substring(0, 1_000);
  }

  private record Evaluation(
      CalculationGateVerdict verdict,
      String reason,
      ExperimentSpec spec,
      ExperimentResult result,
      EvidenceRef evidenceRef) {

    static Evaluation failure(
        CalculationGateVerdict verdict, String reason) {
      return new Evaluation(verdict, reason, null, null, null);
    }
  }
}
