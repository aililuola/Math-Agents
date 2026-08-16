package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact operations on explicitly enumerated finite sets and total maps. */
public final class FiniteSetMapFunctions {
  private FiniteSetMapFunctions() {}

  public static HandlerEvidence run(ExperimentSpec spec) {
    ObjectNode arguments = spec.arguments();
    String operation = requiredText(arguments, "operation");
    List<String> domain = values(arguments.get("domain"), "domain");
    List<String> codomain = values(arguments.get("codomain"), "codomain");
    if (new LinkedHashSet<>(domain).size() != domain.size()
        || new LinkedHashSet<>(codomain).size() != codomain.size()) {
      throw new IllegalArgumentException("domain and codomain must not contain duplicates");
    }
    Map<String, String> mapping = mapping(arguments.get("mapping"), domain, codomain);
    Map<String, List<String>> fibers = new HashMap<>();
    codomain.forEach(value -> fibers.put(value, new ArrayList<>()));
    mapping.forEach((key, value) -> fibers.get(value).add(key));
    boolean injective = fibers.values().stream().allMatch(value -> value.size() <= 1);
    boolean surjective = fibers.values().stream().allMatch(value -> !value.isEmpty());

    ObjectNode certificate = ComputationJson.object();
    certificate.put("operation", operation);
    certificate.set("domain", strings(domain));
    certificate.set("codomain", strings(codomain));
    ObjectNode mappingNode = certificate.putObject("mapping");
    domain.forEach(value -> mappingNode.put(value, mapping.get(value)));
    certificate.put("complete_finite_coverage", true);
    certificate.put("injective", injective);
    certificate.put("surjective", surjective);
    certificate.put("bijective", injective && surjective);
    fibers.values().stream()
        .filter(value -> value.size() > 1)
        .findFirst()
        .ifPresent(
            collision -> certificate.set("collision_witness", strings(collision)));
    fibers.entrySet().stream()
        .filter(entry -> entry.getValue().isEmpty())
        .map(Map.Entry::getKey)
        .findFirst()
        .ifPresent(value -> certificate.put("missing_image_witness", value));

    switch (operation) {
      case "injective", "surjective", "bijective" -> {
        // The booleans above are the complete decision certificate.
      }
      case "image" -> certificate.set("image", strings(new ArrayList<>(new LinkedHashSet<>(mapping.values()))));
      case "preimage" -> {
        String target = requiredText(arguments, "target");
        if (!codomain.contains(target)) {
          throw new IllegalArgumentException("preimage target must belong to the codomain");
        }
        certificate.put("target", target);
        certificate.set("preimage", strings(fibers.get(target)));
      }
      case "cardinality_equality" ->
          certificate.put("cardinality_equal", domain.size() == codomain.size());
      default -> throw new IllegalArgumentException("unsupported finite set-map operation: " + operation);
    }

    ObjectNode scope =
        ComputationJson.object()
            .put("complete_domain", true)
            .put("domain_size", domain.size())
            .put("codomain_size", codomain.size())
            .put("operation", operation);
    return new HandlerEvidence(
        ExperimentOutcome.CERTIFIED,
        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
        scope,
        null,
        certificate,
        true,
        domain.size(),
        false,
        List.of("The total finite mapping requires an independent coverage verifier."),
        null);
  }

  static List<String> values(JsonNode raw, String label) {
    ArrayNode array = ComputationJson.requiredArray(raw, label);
    List<String> values = new ArrayList<>(array.size());
    for (JsonNode item : array) {
      if (!item.isValueNode() || item.isContainerNode()) {
        throw new IllegalArgumentException(label + " entries must be scalar");
      }
      values.add(item.asText());
    }
    return List.copyOf(values);
  }

  static Map<String, String> mapping(
      JsonNode raw, List<String> domain, List<String> codomain) {
    if (raw == null || !raw.isObject()) {
      throw new IllegalArgumentException("mapping must be an object");
    }
    Map<String, String> result = new HashMap<>();
    raw.properties().forEach(entry -> result.put(entry.getKey(), entry.getValue().asText()));
    if (!result.keySet().equals(Set.copyOf(domain))) {
      throw new IllegalArgumentException("mapping must define every domain element exactly once");
    }
    if (!Set.copyOf(codomain).containsAll(result.values())) {
      throw new IllegalArgumentException("mapping values must belong to the codomain");
    }
    return Map.copyOf(result);
  }

  private static ArrayNode strings(List<String> values) {
    ArrayNode result = ComputationJson.array();
    values.forEach(result::add);
    return result;
  }

  private static String requiredText(ObjectNode value, String field) {
    String result = value.path(field).asText("").strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
