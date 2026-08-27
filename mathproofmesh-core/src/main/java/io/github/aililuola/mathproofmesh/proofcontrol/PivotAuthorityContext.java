package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.Map;
import java.util.Set;

/** Immutable trusted facts supplied to deterministic pivot audit by production owners. */
public record PivotAuthorityContext(
    String problemHash,
    String rootGoalHash,
    String routeId,
    String sourceStrategyId,
    Map<String, KnownObstruction> knownObstructions,
    Set<String> activeObjectIds,
    Set<String> activeCanonicalTargetIds,
    Set<String> knownObligationIds,
    Set<String> verifiedClaimIds,
    Set<String> knownClaimIds,
    Map<String, String> knownClaimStatementHashes,
    Set<String> permanentNegativeConflicts,
    String selectedFamilyId,
    Set<String> selectedCanonicalTargetIds,
    boolean focusedRecovery,
    boolean capacityAvailable) {
  public PivotAuthorityContext {
    problemHash = PivotValues.required(problemHash, "problemHash");
    rootGoalHash = PivotValues.required(rootGoalHash, "rootGoalHash");
    routeId = PivotValues.required(routeId, "routeId");
    sourceStrategyId = PivotValues.required(sourceStrategyId, "sourceStrategyId");
    knownObstructions = knownObstructions == null ? Map.of() : Map.copyOf(knownObstructions);
    activeObjectIds = activeObjectIds == null ? Set.of() : Set.copyOf(activeObjectIds);
    activeCanonicalTargetIds =
        activeCanonicalTargetIds == null ? Set.of() : Set.copyOf(activeCanonicalTargetIds);
    knownObligationIds = knownObligationIds == null ? Set.of() : Set.copyOf(knownObligationIds);
    verifiedClaimIds = verifiedClaimIds == null ? Set.of() : Set.copyOf(verifiedClaimIds);
    knownClaimIds = knownClaimIds == null ? Set.of() : Set.copyOf(knownClaimIds);
    knownClaimStatementHashes =
        knownClaimStatementHashes == null ? Map.of() : Map.copyOf(knownClaimStatementHashes);
    permanentNegativeConflicts =
        permanentNegativeConflicts == null ? Set.of() : Set.copyOf(permanentNegativeConflicts);
    selectedFamilyId = PivotValues.normalize(selectedFamilyId);
    selectedCanonicalTargetIds =
        selectedCanonicalTargetIds == null ? Set.of() : Set.copyOf(selectedCanonicalTargetIds);
  }

  public record KnownObstruction(
      PivotObstructionRef reference, String problemHash, boolean sourceArtifactLocated) {
    public KnownObstruction {
      reference = java.util.Objects.requireNonNull(reference, "reference");
      problemHash = PivotValues.required(problemHash, "problemHash");
    }
  }
}
