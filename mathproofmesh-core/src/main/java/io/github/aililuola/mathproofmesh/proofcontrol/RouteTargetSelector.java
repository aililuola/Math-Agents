package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.Obligation;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObligationStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Deterministically selects an admissible mathematical obligation for a route. */
public final class RouteTargetSelector {
  public record Selection(
      String routeId, String obligationId, boolean admitted, double score, List<String> reasons) {
    public Selection {
      routeId = ProofControlModels.required(routeId, "routeId");
      obligationId = ProofControlModels.blankToNull(obligationId);
      reasons = List.copyOf(reasons);
    }
  }

  public Selection select(
      String routeId,
      List<Obligation> obligations,
      Set<String> dependencyClosedIds,
      Set<String> domainEligibleIds) {
    List<Obligation> candidates =
        (obligations == null ? List.<Obligation>of() : obligations).stream()
            .filter(
                obligation ->
                    obligation.status() == ObligationStatus.OPEN
                        || obligation.status() == ObligationStatus.REOPENED)
            .filter(obligation -> domainEligibleIds.contains(obligation.id()))
            .filter(
                obligation ->
                    obligation.assumptions().isEmpty()
                        || dependencyClosedIds.containsAll(obligation.assumptions()))
            .sorted(
                Comparator.comparingDouble(
                        (Obligation obligation) ->
                            -(obligation.priority() + obligation.centrality()))
                    .thenComparing(Obligation::id))
            .toList();
    if (candidates.isEmpty()) {
      return new Selection(
          routeId, null, false, 0.0d, List.of("no open dependency-closed domain-eligible target"));
    }
    Obligation selected = candidates.getFirst();
    return new Selection(
        routeId,
        selected.id(),
        true,
        selected.priority() + selected.centrality(),
        List.of("highest deterministic priority plus centrality score"));
  }
}
