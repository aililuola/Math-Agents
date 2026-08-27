package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stores salvageable mathematical near misses as non-authoritative hints. */
@SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "Failure labels are controlled enum-like audit values normalized with Locale.ROOT")
public final class NearMissLedger {
  public record Candidate(
      String routeId,
      String targetObligationId,
      String sourceTargetId,
      String abstractIdea,
      String concreteCandidate,
      List<String> preservedProperties,
      List<String> failedConstraints,
      String firstFailureType,
      List<String> salvageableComponents,
      List<String> verifierReportIds,
      double verifierConfidence) {
    public Candidate {
      preservedProperties = List.copyOf(preservedProperties);
      failedConstraints = List.copyOf(failedConstraints);
      salvageableComponents = List.copyOf(salvageableComponents);
      verifierReportIds = List.copyOf(verifierReportIds);
      ProofControlModels.unit(verifierConfidence, "verifierConfidence");
    }
  }

  public record NearMiss(
      String id,
      Candidate candidate,
      List<String> suggestedRepairOperators,
      List<String> suggestedInductionMeasures,
      String repairModule,
      boolean authoritative,
      boolean repaired) {
    public NearMiss {
      suggestedRepairOperators = List.copyOf(suggestedRepairOperators);
      suggestedInductionMeasures = List.copyOf(suggestedInductionMeasures);
    }
  }

  private final Map<String, NearMiss> records = new LinkedHashMap<>();

  public NearMiss record(Candidate candidate, boolean mathematicalFailure) {
    if (!mathematicalFailure
        || candidate.failedConstraints().isEmpty()
        || candidate.salvageableComponents().isEmpty()
        || candidate.abstractIdea().isBlank()) {
      return null;
    }
    String failure = candidate.firstFailureType().toLowerCase(Locale.ROOT);
    String repair =
        failure.contains("realizer") || failure.contains("admissib")
            ? "realizer_repair"
            : failure.contains("induction") || failure.contains("occurrence")
                ? "induction_selector"
                : failure.contains("scope") || failure.contains("goal")
                    ? "scope_goal_rewrite"
                    : failure.contains("bridge")
                        ? "minimal_bridge"
                        : "bounded_local_repair";
    List<String> operators =
        switch (repair) {
          case "realizer_repair" ->
              List.of("replace_realizer_preserve_structure");
          case "induction_selector" -> List.of("select_well_founded_measure");
          case "scope_goal_rewrite" -> List.of("weaken_target", "repair_scope");
          case "minimal_bridge" -> List.of("create_minimal_bridge");
          default -> List.of("repair_first_invalid_step");
        };
    List<String> measures =
        repair.equals("induction_selector") ? List.of("occurrence_count") : List.of();
    String id =
        "near_miss_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "route", candidate.routeId(),
                        "target", candidate.sourceTargetId(),
                        "failure", candidate.firstFailureType(),
                        "candidate", candidate.concreteCandidate()))
                .substring(0, 20);
    NearMiss record =
        new NearMiss(id, candidate, operators, measures, repair, false, false);
    records.putIfAbsent(id, record);
    return records.get(id);
  }

  public List<NearMiss> relevant(String routeId) {
    return records.values().stream()
        .filter(value -> value.candidate().routeId().equals(routeId))
        .sorted(Comparator.comparing(NearMiss::id))
        .toList();
  }

  public String promptHint(NearMiss record) {
    return "[NON_AUTHORITATIVE_NEAR_MISS] "
        + record.candidate().abstractIdea()
        + "; failed: "
        + String.join(", ", record.candidate().failedConstraints())
        + "; suggested repair: "
        + String.join(", ", record.suggestedRepairOperators());
  }

  public NearMiss markRepaired(String id, String evidenceId) {
    ProofControlModels.required(evidenceId, "evidenceId");
    NearMiss current =
        java.util.Objects.requireNonNull(records.get(id), "unknown near miss");
    NearMiss repaired =
        new NearMiss(
            current.id(),
            current.candidate(),
            current.suggestedRepairOperators(),
            current.suggestedInductionMeasures(),
            current.repairModule(),
            false,
            true);
    records.put(id, repaired);
    return repaired;
  }
}
