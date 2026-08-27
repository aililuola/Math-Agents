package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.List;

/** Deterministic composition for disjoint process-bound handler sets. */
public final class CompositeExternalComputationHandler
    implements ExternalComputationHandler {
  private final List<ExternalComputationHandler> handlers;

  public CompositeExternalComputationHandler(
      List<ExternalComputationHandler> handlers) {
    if (handlers == null || handlers.isEmpty() || handlers.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("at least one external handler is required");
    }
    this.handlers = List.copyOf(handlers);
    for (ComputationMethod method : ComputationMethod.values()) {
      long supporters = this.handlers.stream().filter(handler -> handler.supports(method)).count();
      if (supporters > 1) {
        throw new IllegalArgumentException(
            "multiple external handlers claim " + method.value());
      }
    }
  }

  @Override
  public boolean supports(ComputationMethod method) {
    return handlers.stream().anyMatch(handler -> handler.supports(method));
  }

  @Override
  public String toolIdentity(ComputationMethod method) {
    return select(method).toolIdentity(method);
  }

  @Override
  public HandlerEvidence execute(ExperimentSpec spec, ExperimentProgram program) {
    return select(spec.method()).execute(spec, program);
  }

  private ExternalComputationHandler select(ComputationMethod method) {
    return handlers.stream()
        .filter(handler -> handler.supports(method))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "no external handler supports " + method.value()));
  }
}
