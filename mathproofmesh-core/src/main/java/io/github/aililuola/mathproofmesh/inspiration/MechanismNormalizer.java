package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative mechanism ontology. Unknown labels remain auditable extensions. */
public final class MechanismNormalizer {
  public static final String VERSION = "math-mechanism-ontology-v1";

  private static final Map<String, Map<String, String>> ALIASES =
      Map.of(
          "representation",
          Map.ofEntries(
              Map.entry("modular_arithmetic", "modular"),
              Map.entry("modular_congruence", "modular"),
              Map.entry("residue", "modular"),
              Map.entry("p_adic", "valuation"),
              Map.entry("hypergraph", "graph"),
              Map.entry("coordinate_geometry", "coordinate"),
              Map.entry("complex_plane", "complex"),
              Map.entry("direct_algebra", "algebraic")),
          "mechanism",
          Map.ofEntries(
              Map.entry("representation_switch", "representation_switch"),
              Map.entry("structural_analogy", "structural_analogy"),
              Map.entry("auxiliary_construction", "auxiliary_construction"),
              Map.entry("invariant_hypothesis", "invariant_hypothesis"),
              Map.entry("reverse_goal_analysis", "reverse_goal_analysis"),
              Map.entry("bridge_lemma", "bridge_lemma"),
              Map.entry("surprise", "surprise_exploration"),
              Map.entry("meta_replan", "persistent_meta_strategy")),
          "object",
          Map.ofEntries(
              Map.entry("auxiliary_sequence", "sequence"),
              Map.entry("bipartite_graph", "graph"),
              Map.entry("residue_classes", "residue_class"),
              Map.entry("valuation_profile", "valuation_vector"),
              Map.entry("potential", "potential_function"),
              Map.entry("state_space", "state")),
          "transformation",
          Map.ofEntries(
              Map.entry("reduce_mod_m", "modular_reduction"),
              Map.entry("goal_to_sufficient_condition", "backward_reduction"),
              Map.entry("quotient", "quotient"),
              Map.entry("lift", "lift"),
              Map.entry("encode", "encode"),
              Map.entry("dualize", "dualize"),
              Map.entry("telescope", "telescope")),
          "principle",
          Map.ofEntries(
              Map.entry("extremal_principle", "extremal"),
              Map.entry("minimal_counterexample", "descent"),
              Map.entry("invariance", "invariant"),
              Map.entry("finite_case_partition", "finite_partition"),
              Map.entry("power", "power_of_point"),
              Map.entry("parity", "parity")));

  public NoveltySignature normalize(NoveltySignature signature) {
    if (VERSION.equals(signature.normalizerVersion())) {
      return signature;
    }
    Map<String, List<String>> raw = new LinkedHashMap<>();
    raw.put("representation", signature.representationTags());
    raw.put("mechanism", signature.mechanismTags());
    raw.put("object", signature.coreObjects());
    raw.put("transformation", signature.keyTransformations());
    raw.put("principle", signature.proofPrinciples());
    Map<String, List<String>> canonical = new LinkedHashMap<>();
    Set<String> extensions = new LinkedHashSet<>(signature.extensionTags());
    int recognized = 0;
    int total = 0;
    for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
      List<String> values = new ArrayList<>();
      for (String value : entry.getValue()) {
        String tag = slug(value);
        if (tag.isEmpty()) {
          continue;
        }
        total++;
        String resolved = resolve(entry.getKey(), tag);
        if (resolved == null) {
          extensions.add(entry.getKey() + ":" + tag);
        } else {
          recognized++;
          if (!values.contains(resolved)) {
            values.add(resolved);
          }
        }
      }
      canonical.put(entry.getKey(), List.copyOf(values));
    }
    return new NoveltySignature(
        canonical.get("object"),
        List.copyOf(extensions),
        canonical.get("transformation"),
        canonical.get("mechanism"),
        recognized / (double) Math.max(1, total),
        null,
        VERSION,
        canonical.get("principle"),
        raw,
        canonical.get("representation"),
        signature.targetedObligationIds());
  }

  public NoveltySignature fromRouteTags(
      List<String> tags, List<String> targetedObligationIds) {
    List<String> safeTags = tags == null ? List.of() : List.copyOf(tags);
    return normalize(
        new NoveltySignature(
            safeTags,
            List.of(),
            safeTags,
            List.of("route_strategy"),
            null,
            null,
            null,
            safeTags,
            Map.of("route", safeTags),
            safeTags,
            targetedObligationIds));
  }

  private static String resolve(String dimension, String tag) {
    Map<String, String> aliases = ALIASES.get(dimension);
    if (aliases == null) {
      return null;
    }
    String exact = aliases.get(tag);
    if (exact != null) {
      return exact;
    }
    return aliases.entrySet().stream()
        .filter(entry -> ("_" + tag + "_").contains("_" + entry.getKey() + "_"))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private static String slug(String value) {
    String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
    text = text.replaceAll("[^a-z0-9]+", "_").replaceAll("_+", "_");
    return text.replaceAll("^_|_$", "");
  }
}
