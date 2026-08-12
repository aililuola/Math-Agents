package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Keeps an abstract reduction separate from replaceable concrete realizers. */
public final class AbstractRealizerController {
  public record AbstractStructure(
      String id,
      String routeId,
      String reduction,
      List<String> invariants,
      List<String> targetObligationIds,
      boolean viable,
      List<String> evidenceRefs) {
    public AbstractStructure {
      invariants = List.copyOf(invariants);
      targetObligationIds = List.copyOf(targetObligationIds);
      evidenceRefs = List.copyOf(evidenceRefs);
    }
  }

  public record Realizer(
      String id,
      String structureId,
      String routeId,
      String construction,
      List<String> admissibilityConditions,
      List<String> boundaryConditions,
      String descentMeasure,
      String expectedStrictDecrease,
      List<String> falsificationTests,
      String status,
      ProofControlModels.RealizerFailureType failureType,
      String failureReason,
      List<String> evidenceRefs) {
    public Realizer {
      admissibilityConditions = List.copyOf(admissibilityConditions);
      boundaryConditions = List.copyOf(boundaryConditions);
      falsificationTests = List.copyOf(falsificationTests);
      evidenceRefs = List.copyOf(evidenceRefs);
    }
  }

  public record RepairTask(
      String id,
      String structureId,
      String failedCandidateId,
      String operator,
      List<String> requiredConstraints,
      List<String> targetObligationIds) {
    public RepairTask {
      requiredConstraints = List.copyOf(requiredConstraints);
      targetObligationIds = List.copyOf(targetObligationIds);
    }
  }

  private final int repairBudget;
  private final Map<String, AbstractStructure> structures = new LinkedHashMap<>();
  private final Map<String, Realizer> realizers = new LinkedHashMap<>();
  private final Map<String, Integer> repairsByStructure = new LinkedHashMap<>();

  public AbstractRealizerController(int repairBudget) {
    if (repairBudget < 0) {
      throw new IllegalArgumentException("repairBudget must be nonnegative");
    }
    this.repairBudget = repairBudget;
  }

  public AbstractStructure extract(
      String routeId,
      String reduction,
      List<String> invariants,
      List<String> targetObligationIds) {
    String id =
        "abstract_structure_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "route", routeId,
                        "reduction", ProofIdentity.normalizeText(reduction),
                        "invariants", invariants,
                        "targets", targetObligationIds))
                .substring(0, 20);
    AbstractStructure structure =
        new AbstractStructure(
            id,
            ProofControlModels.required(routeId, "routeId"),
            ProofControlModels.required(reduction, "reduction"),
            invariants,
            targetObligationIds,
            true,
            List.of());
    structures.putIfAbsent(id, structure);
    return structures.get(id);
  }

  public Realizer register(
      String structureId,
      String routeId,
      String construction,
      List<String> admissibility,
      List<String> boundaries,
      String descentMeasure,
      String expectedDecrease,
      List<String> falsificationTests) {
    requireStructure(structureId);
    if (falsificationTests == null || falsificationTests.isEmpty()) {
      throw new IllegalArgumentException("realizer requires falsification tests");
    }
    String id =
        "realizer_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "structure", structureId,
                        "construction", ProofIdentity.normalizeText(construction),
                        "admissibility", admissibility,
                        "boundaries", boundaries))
                .substring(0, 20);
    if (realizers.containsKey(id)) {
      throw new IllegalArgumentException("duplicate realizer candidate");
    }
    Realizer realizer =
        new Realizer(
            id,
            structureId,
            routeId,
            ProofControlModels.required(construction, "construction"),
            admissibility,
            boundaries,
            ProofControlModels.required(descentMeasure, "descentMeasure"),
            ProofControlModels.required(expectedDecrease, "expectedDecrease"),
            falsificationTests,
            "candidate",
            null,
            null,
            List.of());
    realizers.put(id, realizer);
    return realizer;
  }

  public Realizer fail(
      String realizerId,
      ProofControlModels.RealizerFailureType type,
      String reason,
      String evidenceId) {
    Realizer current = requireRealizer(realizerId);
    Realizer failed =
        new Realizer(
            current.id(),
            current.structureId(),
            current.routeId(),
            current.construction(),
            current.admissibilityConditions(),
            current.boundaryConditions(),
            current.descentMeasure(),
            current.expectedStrictDecrease(),
            current.falsificationTests(),
            "failed",
            java.util.Objects.requireNonNull(type, "type"),
            ProofControlModels.required(reason, "reason"),
            List.of(ProofControlModels.required(evidenceId, "evidenceId")));
    realizers.put(realizerId, failed);
    return failed;
  }

  public Realizer verify(String realizerId, List<String> evidenceRefs) {
    Realizer current = requireRealizer(realizerId);
    if (evidenceRefs == null || evidenceRefs.isEmpty()) {
      throw new IllegalArgumentException("verified realizer requires evidence");
    }
    Realizer verified =
        new Realizer(
            current.id(),
            current.structureId(),
            current.routeId(),
            current.construction(),
            current.admissibilityConditions(),
            current.boundaryConditions(),
            current.descentMeasure(),
            current.expectedStrictDecrease(),
            current.falsificationTests(),
            "verified",
            null,
            null,
            evidenceRefs);
    realizers.put(realizerId, verified);
    AbstractStructure structure = requireStructure(current.structureId());
    structures.put(
        structure.id(),
        new AbstractStructure(
            structure.id(),
            structure.routeId(),
            structure.reduction(),
            structure.invariants(),
            structure.targetObligationIds(),
            true,
            evidenceRefs));
    return verified;
  }

  public boolean structureViable(String structureId) {
    AbstractStructure structure = requireStructure(structureId);
    // A concrete construction failure refutes only that realizer. The
    // abstract reduction remains viable until separately refuted.
    return structure.viable();
  }

  public RepairTask createRepairTask(String failedRealizerId) {
    Realizer failed = requireRealizer(failedRealizerId);
    if (!"failed".equals(failed.status())) {
      throw new IllegalArgumentException("repair requires a failed realizer");
    }
    int used = repairsByStructure.getOrDefault(failed.structureId(), 0);
    if (used >= repairBudget) {
      throw new IllegalStateException("realizer repair budget exhausted");
    }
    repairsByStructure.put(failed.structureId(), used + 1);
    AbstractStructure structure = requireStructure(failed.structureId());
    String id =
        "realizer_repair_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "structure", failed.structureId(),
                        "failed", failed.id(),
                        "attempt", used + 1))
                .substring(0, 20);
    List<String> constraints = new ArrayList<>(failed.admissibilityConditions());
    constraints.addAll(failed.boundaryConditions());
    return new RepairTask(
        id,
        failed.structureId(),
        failed.id(),
        "replace_realizer_preserve_structure",
        constraints.stream().distinct().sorted().toList(),
        structure.targetObligationIds());
  }

  private AbstractStructure requireStructure(String id) {
    AbstractStructure value = structures.get(id);
    if (value == null) {
      throw new IllegalArgumentException("unknown abstract structure: " + id);
    }
    return value;
  }

  private Realizer requireRealizer(String id) {
    Realizer value = realizers.get(id);
    if (value == null) {
      throw new IllegalArgumentException("unknown realizer: " + id);
    }
    return value;
  }
}
