package io.github.aililuola.mathproofmesh.verification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Executes every required level and treats missing handlers as failed evidence. */
public final class ValidationEscalationExecutor {

  public ValidationExecution execute(
      EscalationPlan plan,
      Map<ValidationLevel, Supplier<ValidationStepResult>> handlers) {
    java.util.Objects.requireNonNull(plan, "plan");
    Map<ValidationLevel, Supplier<ValidationStepResult>> safeHandlers =
        handlers == null ? Map.of() : Map.copyOf(handlers);
    List<ValidationStepResult> steps = new ArrayList<>();
    List<String> diagnostics = new ArrayList<>(plan.diagnostics());
    for (ValidationLevel level : plan.levels()) {
      Supplier<ValidationStepResult> handler = safeHandlers.get(level);
      ValidationStepResult result;
      if (handler == null) {
        result = ValidationStepResult.missing(level);
      } else {
        try {
          ValidationStepResult supplied = handler.get();
          if (supplied == null) {
            result = ValidationStepResult.failed(level, "validation handler returned no result");
          } else if (supplied.level() == level) {
            result = supplied;
          } else {
            result =
                new ValidationStepResult(
                    level,
                    supplied.executed(),
                    supplied.passed(),
                    supplied.evidenceRefs(),
                    supplied.diagnostic());
          }
        } catch (RuntimeException exception) {
          result =
              ValidationStepResult.failed(
                  level,
                  exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
      }
      steps.add(result);
      if (!result.diagnostic().isEmpty()) {
        diagnostics.add(result.diagnostic());
      }
    }
    boolean backendMissing =
        diagnostics.stream().anyMatch(item -> item.contains("remains pending"));
    boolean passed =
        !backendMissing && steps.stream().allMatch(item -> item.executed() && item.passed());
    return new ValidationExecution(
        plan,
        steps,
        passed,
        !plan.blocksFactPromotion() || passed,
        diagnostics);
  }
}
