package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.DomainOperatorSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Auditable allowlist of domain operators used by active mechanisms. */
public final class DomainOperatorRegistry {
  private final List<DomainOperatorSpec> operators;

  public DomainOperatorRegistry() {
    operators =
        List.of(
            operator(
                "number_theory_modular",
                "number_theory",
                "representation",
                "Pass to residue classes modulo a bounded modulus",
                "encode the target in modular arithmetic",
                List.of("integer", "divisibility", "congruence"),
                List.of("modular"),
                List.of("modular_reduction")),
            operator(
                "combinatorics_graph",
                "combinatorics",
                "representation",
                "Encode incidences as a graph",
                "replace the incidence relation with a finite graph",
                List.of("incidence", "matching", "coloring"),
                List.of("graph"),
                List.of("encode")),
            operator(
                "geometry_coordinate",
                "geometry",
                "representation",
                "Introduce an audited coordinate model",
                "map points and lines to exact coordinates",
                List.of("point", "line", "circle"),
                List.of("coordinate"),
                List.of("encode")),
            operator(
                "algebra_auxiliary_polynomial",
                "algebra",
                "construction",
                "Construct an auxiliary polynomial",
                "encode roots and constraints in an auxiliary polynomial",
                List.of("root", "polynomial", "coefficient"),
                List.of("polynomial"),
                List.of("factor")),
            operator(
                "sequence_difference",
                "sequence",
                "mutation",
                "Pass to a difference sequence",
                "replace the sequence by consecutive differences",
                List.of("sequence", "recurrence", "monotone"),
                List.of("finite_state"),
                List.of("telescope")));
  }

  public List<DomainOperatorSpec> applicable(
      String domain,
      String problemText,
      String family,
      Set<String> forbiddenOperatorIds,
      int limit) {
    if (limit <= 0) {
      return List.of();
    }
    String normalizedDomain = normalize(domain);
    String text = normalize(problemText);
    Set<String> forbidden =
        forbiddenOperatorIds == null ? Set.of() : Set.copyOf(forbiddenOperatorIds);
    return operators.stream()
        .filter(item -> family == null || family.isBlank() || item.family().equals(family))
        .filter(item -> !forbidden.contains(item.operatorId()))
        .filter(
            item ->
                item.domain().equals(normalizedDomain)
                    || item.applicabilityTokens().stream().anyMatch(text::contains))
        .sorted(
            Comparator.comparing(
                    (DomainOperatorSpec item) -> !item.domain().equals(normalizedDomain))
                .thenComparing(DomainOperatorSpec::operatorId))
        .limit(limit)
        .toList();
  }

  public boolean admitted(String operatorId) {
    return operators.stream().anyMatch(item -> item.operatorId().equals(operatorId));
  }

  public List<DomainOperatorSpec> all() {
    return operators;
  }

  private static DomainOperatorSpec operator(
      String id,
      String domain,
      String family,
      String title,
      String transformation,
      List<String> tokens,
      List<String> representations,
      List<String> operations) {
    return new DomainOperatorSpec(
        tokens,
        domain,
        family,
        List.of("run the bounded inverse/preservation check"),
        List.of("verify all introduced equivalences"),
        List.of("mapping may lose a domain or boundary condition"),
        List.of(family),
        List.of("target_object"),
        operations,
        id,
        List.of("all source-domain assumptions are explicit"),
        representations,
        List.of("prove the transformation preserves and reflects the target"),
        List.of("exact_arithmetic"),
        title,
        transformation);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('-', '_').strip();
  }
}
