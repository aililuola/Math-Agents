package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.List;
import java.util.Objects;

/** Explicit change to an active mathematical object, distinct from prose renaming. */
public record MathematicalObjectChange(
    String oldObjectId,
    String oldDescription,
    PivotObjectDisposition disposition,
    String newObjectId,
    String newDescription,
    String bridgeStatement,
    List<String> evidenceRefs) {
  public MathematicalObjectChange {
    oldObjectId = PivotValues.normalize(oldObjectId);
    oldDescription = PivotValues.normalize(oldDescription);
    disposition = Objects.requireNonNull(disposition, "disposition");
    newObjectId = PivotValues.normalize(newObjectId);
    newDescription = PivotValues.normalize(newDescription);
    bridgeStatement = PivotValues.normalize(bridgeStatement);
    evidenceRefs = PivotValues.copy(evidenceRefs);
    switch (disposition) {
      case RETAIN, RETIRE_FROM_ACTIVE_STRATEGY -> {
        PivotValues.required(oldObjectId, "oldObjectId");
        if (newObjectId != null || newDescription != null) {
          throw new IllegalArgumentException("retained or retired object cannot declare a new object");
        }
      }
      case REPLACE -> {
        PivotValues.required(oldObjectId, "oldObjectId");
        PivotValues.required(newObjectId, "newObjectId");
        PivotValues.required(newDescription, "newDescription");
        PivotValues.required(bridgeStatement, "bridgeStatement");
      }
      case ADD -> {
        if (oldObjectId != null || oldDescription != null) {
          throw new IllegalArgumentException("added object cannot declare an old object");
        }
        PivotValues.required(newObjectId, "newObjectId");
        PivotValues.required(newDescription, "newDescription");
      }
    }
  }

  @Override
  public List<String> evidenceRefs() {
    return List.copyOf(evidenceRefs);
  }
}
