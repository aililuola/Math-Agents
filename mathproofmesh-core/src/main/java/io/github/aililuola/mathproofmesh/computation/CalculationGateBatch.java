package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.CalculationGateRecord;
import io.github.aililuola.mathproofmesh.contract.CalculationGateVerdict;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import java.util.List;

/** Immutable result of evaluating one strategy or a batch of proof steps. */
public record CalculationGateBatch(
    boolean passed,
    List<CalculationGateRecord> records,
    List<EvidenceRef> evidenceRefs) {

  public CalculationGateBatch {
    records = records == null ? List.of() : List.copyOf(records);
    evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
    boolean hasFailure =
        records.stream()
            .anyMatch(record -> record.verdict() != CalculationGateVerdict.PASSED);
    if (passed == hasFailure) {
      throw new IllegalArgumentException(
          "passed must be the inverse of the presence of a failed record");
    }
  }

  public List<CalculationGateRecord> failures() {
    return records.stream()
        .filter(record -> record.verdict() != CalculationGateVerdict.PASSED)
        .toList();
  }
}
