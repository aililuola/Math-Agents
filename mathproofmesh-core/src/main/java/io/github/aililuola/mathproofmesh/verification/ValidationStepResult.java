package io.github.aililuola.mathproofmesh.verification;

import java.util.List;

public record ValidationStepResult(
    ValidationLevel level,
    boolean executed,
    boolean passed,
    List<String> evidenceRefs,
    String diagnostic) {

  public ValidationStepResult {
    level = java.util.Objects.requireNonNull(level, "level");
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    diagnostic = diagnostic == null ? "" : diagnostic.trim();
    if (!executed && passed) {
      throw new IllegalArgumentException("an unexecuted validation cannot pass");
    }
  }

  public static ValidationStepResult passed(
      ValidationLevel level, List<String> evidenceRefs) {
    return new ValidationStepResult(level, true, true, evidenceRefs, "");
  }

  public static ValidationStepResult failed(
      ValidationLevel level, String diagnostic) {
    return new ValidationStepResult(level, true, false, List.of(), diagnostic);
  }

  public static ValidationStepResult missing(ValidationLevel level) {
    return new ValidationStepResult(
        level,
        false,
        false,
        List.of(),
        "required " + level.name().toLowerCase(java.util.Locale.ROOT) + " validation was not executed");
  }
}
