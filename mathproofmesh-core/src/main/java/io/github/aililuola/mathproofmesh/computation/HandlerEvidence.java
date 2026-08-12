package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import java.util.List;

/** Evidence returned by a computation handler before it is bound to an experiment result. */
public record HandlerEvidence(
    ExperimentOutcome outcome,
    EvidenceStrength evidenceStrength,
    ObjectNode scope,
    ObjectNode counterexample,
    ObjectNode certificate,
    boolean exactArithmetic,
    int casesChecked,
    boolean independentlyVerified,
    List<String> verificationNotes,
    ObjectNode rawOutput) {

  public HandlerEvidence {
    if (outcome == null || evidenceStrength == null) {
      throw new IllegalArgumentException("outcome and evidenceStrength are required");
    }
    if (casesChecked < 0) {
      throw new IllegalArgumentException("casesChecked must be nonnegative");
    }
    scope = scope == null ? JsonNodeFactory.instance.objectNode() : scope.deepCopy();
    counterexample = counterexample == null ? null : counterexample.deepCopy();
    certificate = certificate == null ? null : certificate.deepCopy();
    verificationNotes = verificationNotes == null ? List.of() : List.copyOf(verificationNotes);
    rawOutput = rawOutput == null ? null : rawOutput.deepCopy();
    if (outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
        && (counterexample == null
            || evidenceStrength != EvidenceStrength.COUNTEREXAMPLE
            || !independentlyVerified)) {
      throw new IllegalArgumentException(
          "counterexamples require a payload, counterexample strength, and independent replay");
    }
    if (outcome == ExperimentOutcome.CERTIFIED
        && (certificate == null
            || (evidenceStrength != EvidenceStrength.EXHAUSTIVE_CERTIFICATE
                && evidenceStrength != EvidenceStrength.FORMAL_CERTIFICATE))) {
      throw new IllegalArgumentException("certified evidence requires a formal certificate");
    }
  }

  @Override
  public ObjectNode scope() {
    return scope.deepCopy();
  }

  @Override
  public ObjectNode counterexample() {
    return counterexample == null ? null : counterexample.deepCopy();
  }

  @Override
  public ObjectNode certificate() {
    return certificate == null ? null : certificate.deepCopy();
  }

  @Override
  public List<String> verificationNotes() {
    return List.copyOf(verificationNotes);
  }

  @Override
  public ObjectNode rawOutput() {
    return rawOutput == null ? null : rawOutput.deepCopy();
  }

  public static HandlerEvidence inconclusive(String note, ObjectNode scope) {
    return new HandlerEvidence(
        ExperimentOutcome.INCONCLUSIVE,
        EvidenceStrength.HEURISTIC,
        scope,
        null,
        null,
        false,
        0,
        false,
        List.of(note),
        null);
  }
}
