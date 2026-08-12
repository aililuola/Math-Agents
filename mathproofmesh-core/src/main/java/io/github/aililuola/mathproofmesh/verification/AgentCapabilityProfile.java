package io.github.aililuola.mathproofmesh.verification;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Capability is empirical and role/domain scoped; self-reported confidence is ignored. */
public final class AgentCapabilityProfile {
  public static final Set<String> DOMAINS =
      Set.of(
          "number_theory",
          "combinatorics",
          "algebra",
          "inequalities",
          "geometry",
          "logic",
          "computation");
  public static final Set<String> ROLES =
      Set.of(
          "prover",
          "skeptic",
          "route_referee",
          "structural_verifier",
          "detailed_verifier",
          "analogy_agent",
          "construction_inventor",
          "representation_switchboard",
          "invariant_hypothesis_agent",
          "reverse_goal_analyzer",
          "meta_strategist",
          "inspiration_referee",
          "bridge_prover",
          "conflict_resolver",
          "tool_agent");

  private final Settings settings;
  private final Map<CellKey, CapabilityCell> cells = new LinkedHashMap<>();
  private int ignoredSelfReports;

  public AgentCapabilityProfile(Settings settings) {
    this.settings = java.util.Objects.requireNonNull(settings, "settings");
  }

  public CapabilityCell get(String agentId, String domain, String role) {
    validateDimension(domain, role);
    CellKey key = new CellKey(agentId, domain, role);
    return cells.computeIfAbsent(
        key, ignored -> CapabilityCell.initial(agentId, domain, role));
  }

  public CapabilityCell update(
      String agentId,
      String domain,
      String role,
      CapabilityObservationKind kind,
      boolean success,
      Double selfReportedConfidence) {
    java.util.Objects.requireNonNull(kind, "kind");
    if (selfReportedConfidence != null) {
      ignoredSelfReports++;
    }
    CapabilityCell current = get(agentId, domain, role);
    double weightedSuccess = current.weightedSuccess() * settings.recencyDecay();
    double weightedTotal = current.weightedTotal() * settings.recencyDecay();
    double weight = weight(kind);
    weightedTotal += weight;
    if (success) {
      weightedSuccess += weight;
    }
    int observations = current.observations() + 1;
    int overturns =
        current.overturns()
            + (kind == CapabilityObservationKind.OVERTURN && !success ? 1 : 0);
    double score = current.score();
    if (observations >= settings.minObservationsBeforeTrustUpdate()) {
      double empirical = weightedSuccess / Math.max(weightedTotal, 1.0e-9);
      double penalty =
          Math.min(
              0.8,
              settings.overturnRatePenalty()
                  * overturns
                  / Math.max(1.0, observations));
      score = Math.max(0.0, Math.min(1.0, empirical - penalty));
    }
    CapabilityCell updated =
        new CapabilityCell(
            current.agentId(),
            domain,
            role,
            observations,
            weightedSuccess,
            weightedTotal,
            overturns,
            score);
    cells.put(new CellKey(agentId, domain, role), updated);
    return updated;
  }

  public double score(String agentId, String domain, String role) {
    return get(agentId, domain, role).score();
  }

  public String selectBest(
      Collection<String> candidates, String domain, String role) {
    return candidates.stream()
        .sorted(
            java.util.Comparator.<String>comparingDouble(
                    candidate -> -score(candidate, domain, role))
                .thenComparing(java.util.Comparator.naturalOrder()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("at least one candidate is required"));
  }

  public int ignoredSelfReports() {
    return ignoredSelfReports;
  }

  public List<CapabilityCell> cells() {
    return List.copyOf(cells.values());
  }

  public static String inferDomain(String... texts) {
    String text =
        String.join(" ", texts == null ? new String[0] : texts)
            .toLowerCase(java.util.Locale.ROOT);
    if (contains(text, "triangle", "circle", "angle", "collinear", "geometry")) {
      return "geometry";
    }
    if (contains(text, "prime", "integer", "divisib", "congruen", "modulo")) {
      return "number_theory";
    }
    if (contains(text, "graph", "count", "pigeonhole", "coloring")) {
      return "combinatorics";
    }
    if (contains(text, "inequal", "cauchy", "jensen", "bound")) {
      return "inequalities";
    }
    if (contains(text, "quantifier", "logical", "if and only if")) {
      return "logic";
    }
    if (contains(text, "algorithm", "enumerat", "program", "python")) {
      return "computation";
    }
    return "algebra";
  }

  private double weight(CapabilityObservationKind kind) {
    return switch (kind) {
      case MUTATION_BENCHMARK -> settings.mutationBenchmarkWeight();
      case TOOL_AGREEMENT -> settings.toolAgreementWeight();
      case FIRST_ERROR_ACCURACY -> settings.firstErrorAccuracyWeight();
      case OVERTURN -> settings.overturnRatePenalty();
      case RECENT_TASK ->
          Math.max(
              0.01,
              1.0
                  - settings.mutationBenchmarkWeight()
                  - settings.toolAgreementWeight()
                  - settings.firstErrorAccuracyWeight());
    };
  }

  private static boolean contains(String text, String... needles) {
    return java.util.Arrays.stream(needles).anyMatch(text::contains);
  }

  private static void validateDimension(String domain, String role) {
    if (!DOMAINS.contains(domain)) {
      throw new IllegalArgumentException("unsupported capability domain: " + domain);
    }
    if (!ROLES.contains(role)) {
      throw new IllegalArgumentException("unsupported capability role: " + role);
    }
  }

  public record Settings(
      int minObservationsBeforeTrustUpdate,
      double recencyDecay,
      double mutationBenchmarkWeight,
      double toolAgreementWeight,
      double firstErrorAccuracyWeight,
      double overturnRatePenalty) {

    public Settings {
      if (minObservationsBeforeTrustUpdate < 1
          || !unit(recencyDecay)
          || !unit(mutationBenchmarkWeight)
          || !unit(toolAgreementWeight)
          || !unit(firstErrorAccuracyWeight)
          || !unit(overturnRatePenalty)) {
        throw new IllegalArgumentException("capability settings are invalid");
      }
    }

    public static Settings defaults() {
      return new Settings(3, 0.95, 0.35, 0.25, 0.20, 0.20);
    }

    private static boolean unit(double value) {
      return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
  }

  private record CellKey(String agentId, String domain, String role) {}
}
