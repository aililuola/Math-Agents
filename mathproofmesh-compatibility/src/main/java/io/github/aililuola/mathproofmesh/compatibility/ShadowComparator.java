package io.github.aililuola.mathproofmesh.compatibility;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ShadowComparator {
  public static final Set<String> REQUIRED_SECTIONS =
      Set.of(
          "problem_contract",
          "strategies",
          "messages",
          "deliveries",
          "memory",
          "proof_graph",
          "checkpoints",
          "recovery",
          "usage",
          "final_state");

  private static final Set<String> PROTECTED_ROOTS =
      Set.of(
          "problem_contract",
          "strategies",
          "messages",
          "deliveries",
          "memory",
          "proof_graph",
          "checkpoints",
          "recovery",
          "usage",
          "final_state");

  public record Difference(
      String pointer,
      String kind,
      String pythonValueHash,
      String javaValueHash,
      boolean critical,
      String explanation) {
    public Difference {
      pointer = Objects.requireNonNull(pointer, "pointer");
      kind = Objects.requireNonNull(kind, "kind");
      pythonValueHash = Objects.requireNonNull(pythonValueHash, "pythonValueHash");
      javaValueHash = Objects.requireNonNull(javaValueHash, "javaValueHash");
      explanation = Objects.requireNonNull(explanation, "explanation");
    }
  }

  public record ShadowComparisonReport(
      boolean passed,
      Set<String> sectionsCompared,
      List<Difference> explainedDifferences,
      List<Difference> criticalDifferences,
      String pythonSnapshotHash,
      String javaSnapshotHash) {
    public ShadowComparisonReport {
      sectionsCompared = Set.copyOf(sectionsCompared);
      explainedDifferences = List.copyOf(explainedDifferences);
      criticalDifferences = List.copyOf(criticalDifferences);
      pythonSnapshotHash = Objects.requireNonNull(pythonSnapshotHash, "pythonSnapshotHash");
      javaSnapshotHash = Objects.requireNonNull(javaSnapshotHash, "javaSnapshotHash");
      if (passed != criticalDifferences.isEmpty()) {
        throw new IllegalArgumentException("passed must reflect the critical difference list");
      }
    }
  }

  public ShadowComparisonReport compare(
      String pythonJson, String javaJson, Set<String> allowedNonDeterministicPointers) {
    return compare(
        ContractObjectMapper.parseTree(pythonJson),
        ContractObjectMapper.parseTree(javaJson),
        allowedNonDeterministicPointers);
  }

  public ShadowComparisonReport compare(
      JsonNode pythonSnapshot,
      JsonNode javaSnapshot,
      Set<String> allowedNonDeterministicPointers) {
    Objects.requireNonNull(pythonSnapshot, "pythonSnapshot");
    Objects.requireNonNull(javaSnapshot, "javaSnapshot");
    Set<String> allowed = normalizeAllowedPointers(allowedNonDeterministicPointers);
    List<Difference> explained = new ArrayList<>();
    List<Difference> critical = new ArrayList<>();
    Set<String> sections = new LinkedHashSet<>();

    if (!pythonSnapshot.isObject() || !javaSnapshot.isObject()) {
      critical.add(
          difference(
              "",
              "root-type",
              pythonSnapshot,
              javaSnapshot,
              true,
              "shadow snapshots must both be JSON objects"));
    } else {
      for (String section : REQUIRED_SECTIONS.stream().sorted().toList()) {
        boolean pythonHas = pythonSnapshot.has(section);
        boolean javaHas = javaSnapshot.has(section);
        if (pythonHas && javaHas) {
          sections.add(section);
        } else {
          critical.add(
              difference(
                  "/" + escape(section),
                  "missing-required-section",
                  pythonHas ? pythonSnapshot.get(section) : null,
                  javaHas ? javaSnapshot.get(section) : null,
                  true,
                  "required parity section must exist in both implementations"));
        }
      }
      walk("", pythonSnapshot, javaSnapshot, allowed, explained, critical);
    }

    return new ShadowComparisonReport(
        critical.isEmpty(),
        sections,
        explained,
        critical,
        CanonicalJson.stableHash(pythonSnapshot),
        CanonicalJson.stableHash(javaSnapshot));
  }

  private static void walk(
      String pointer,
      JsonNode python,
      JsonNode java,
      Set<String> allowed,
      List<Difference> explained,
      List<Difference> critical) {
    if (python == null || java == null) {
      recordDifference(pointer, "presence", python, java, allowed, explained, critical);
      return;
    }
    if (python.getNodeType() != java.getNodeType()) {
      recordDifference(pointer, "type", python, java, allowed, explained, critical);
      return;
    }
    if (python.isObject()) {
      Set<String> fields = new HashSet<>();
      python.fieldNames().forEachRemaining(fields::add);
      java.fieldNames().forEachRemaining(fields::add);
      fields.stream()
          .sorted()
          .forEach(
              field ->
                  walk(
                      pointer + "/" + escape(field),
                      python.get(field),
                      java.get(field),
                      allowed,
                      explained,
                      critical));
      return;
    }
    if (python.isArray()) {
      if (python.size() != java.size()) {
        recordDifference(pointer, "array-size", python, java, allowed, explained, critical);
        return;
      }
      for (int index = 0; index < python.size(); index++) {
        walk(
            pointer + "/" + index,
            python.get(index),
            java.get(index),
            allowed,
            explained,
            critical);
      }
      return;
    }
    if (!python.equals(java)) {
      recordDifference(pointer, "value", python, java, allowed, explained, critical);
    }
  }

  private static void recordDifference(
      String pointer,
      String kind,
      JsonNode python,
      JsonNode java,
      Set<String> allowed,
      List<Difference> explained,
      List<Difference> critical) {
    boolean declared = allowed.stream().anyMatch(item -> isAtOrBelow(pointer, item));
    boolean protectedValue = isProtected(pointer);
    if (declared && !protectedValue) {
      explained.add(
          difference(
              pointer,
              kind,
              python,
              java,
              false,
              "declared non-deterministic field"));
    } else {
      critical.add(
          difference(
              pointer,
              kind,
              python,
              java,
              true,
              protectedValue
                  ? "contract, identity, hash, or state differences cannot be waived"
                  : "difference is not declared non-deterministic"));
    }
  }

  private static Difference difference(
      String pointer,
      String kind,
      JsonNode python,
      JsonNode java,
      boolean critical,
      String explanation) {
    return new Difference(
        pointer,
        kind,
        valueHash(python),
        valueHash(java),
        critical,
        explanation);
  }

  private static String valueHash(JsonNode value) {
    return value == null ? "absent" : CanonicalJson.stableHash(value);
  }

  private static Set<String> normalizeAllowedPointers(Set<String> pointers) {
    Objects.requireNonNull(pointers, "allowedNonDeterministicPointers");
    Set<String> normalized = new HashSet<>();
    for (String pointer : pointers) {
      if (pointer == null || !pointer.startsWith("/") || pointer.contains("..")) {
        throw new IllegalArgumentException(
            "allowed non-deterministic fields must be explicit JSON pointers");
      }
      normalized.add(pointer.endsWith("/") ? pointer.substring(0, pointer.length() - 1) : pointer);
    }
    return Set.copyOf(normalized);
  }

  private static boolean isAtOrBelow(String pointer, String allowed) {
    return pointer.equals(allowed) || pointer.startsWith(allowed + "/");
  }

  private static boolean isProtected(String pointer) {
    String[] tokens = asciiLowercase(pointer).split("/");
    if (tokens.length == 2
        && PROTECTED_ROOTS.contains(tokens[1].replace("~1", "/").replace("~0", "~"))) {
      return true;
    }
    if (tokens.length == 0) {
      return false;
    }
    String leaf = tokens[tokens.length - 1].replace("~1", "/").replace("~0", "~");
    return leaf.equals("id")
        || leaf.endsWith("_id")
        || leaf.equals("hash")
        || leaf.endsWith("_hash")
        || leaf.equals("status")
        || leaf.endsWith("_status")
        || leaf.equals("state")
        || leaf.endsWith("_state")
        || leaf.contains("checkpoint")
        || leaf.contains("dependency")
        || leaf.contains("receipt");
  }

  private static String escape(String token) {
    return token.replace("~", "~0").replace("/", "~1");
  }

  private static String asciiLowercase(String value) {
    StringBuilder normalized = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      normalized.append(
          character >= 'A' && character <= 'Z' ? (char) (character + ('a' - 'A')) : character);
    }
    return normalized.toString();
  }
}
