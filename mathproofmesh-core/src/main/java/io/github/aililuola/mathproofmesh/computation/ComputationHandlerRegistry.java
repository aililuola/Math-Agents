package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/** Stable method-to-handler registry; typed Java handlers take precedence over sidecars. */
public final class ComputationHandlerRegistry {
  public static final String JAVA_TOOL_VERSION = "java-mathproofmesh-computation/0.8.0";

  private final Map<ComputationMethod, ComputationHandler> javaHandlers;
  private final ExternalComputationHandler externalHandler;

  public ComputationHandlerRegistry(ExternalComputationHandler externalHandler) {
    this.javaHandlers = javaHandlers();
    this.externalHandler = externalHandler;
  }

  public static ComputationHandlerRegistry javaOnly() {
    return new ComputationHandlerRegistry(null);
  }

  public boolean supports(ComputationMethod method) {
    return javaHandlers.containsKey(method)
        || (externalHandler != null && externalHandler.supports(method));
  }

  public HandlerEvidence execute(ExperimentSpec spec, ExperimentProgram program) {
    ComputationHandler javaHandler = javaHandlers.get(spec.method());
    if (javaHandler != null) {
      return javaHandler.execute(spec, program);
    }
    if (externalHandler != null && externalHandler.supports(spec.method())) {
      return externalHandler.execute(spec, program);
    }
    return HandlerEvidence.inconclusive(
        "No configured execution adapter is available for method " + spec.method().value() + ".",
        ComputationJson.object().put("method", spec.method().value()));
  }

  public String toolIdentity(ComputationMethod method) {
    if (javaHandlers.containsKey(method)) {
      return JAVA_TOOL_VERSION + "/" + method.value();
    }
    if (externalHandler != null && externalHandler.supports(method)) {
      return externalHandler.toolIdentity(method);
    }
    return JAVA_TOOL_VERSION + "/unavailable/" + method.value();
  }

  public Optional<ExternalComputationHandler> externalHandler() {
    return Optional.ofNullable(externalHandler);
  }

  private static Map<ComputationMethod, ComputationHandler> javaHandlers() {
    Map<ComputationMethod, ComputationHandler> result =
        new EnumMap<>(ComputationMethod.class);
    result.put(ComputationMethod.MODULAR_EXHAUSTIVE, noProgram(ModularFunctions::run));
    result.put(
        ComputationMethod.BOUNDED_INTEGER_SEARCH,
        noProgram(IntegerSearchFunctions::run));
    result.put(ComputationMethod.GRAPH_CERTIFICATE, noProgram(GraphFunctions::run));
    result.put(ComputationMethod.RECURRENCE_CHECK, noProgram(RecurrenceFunctions::run));
    result.put(
        ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
        noProgram(SequenceFunctions::runBoundedGreedySequence));
    result.put(
        ComputationMethod.CANDIDATE_PERIOD_CHECK,
        noProgram(SequenceFunctions::runCandidatePeriodCheck));
    result.put(ComputationMethod.EXACT_GEOMETRY, noProgram(GeometryFunctions::run));
    result.put(
        ComputationMethod.NUMBER_THEORY_CHECK,
        noProgram(NumberTheoryFunctions::run));
    return Map.copyOf(result);
  }

  private static ComputationHandler noProgram(
      java.util.function.Function<ExperimentSpec, HandlerEvidence> function) {
    return (spec, program) -> function.apply(spec);
  }
}
