package io.github.aililuola.mathproofmesh.compatibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LegacyVersionMigrator {
  public static final String TARGET_VERSION = "0.8.2";

  public record MigrationOutcome(
      String sourceVersion,
      String targetVersion,
      Map<String, String> canonicalDocuments,
      List<QuarantinedLegacyClaim> quarantinedClaims,
      List<String> steps) {
    public MigrationOutcome {
      canonicalDocuments = Map.copyOf(canonicalDocuments);
      quarantinedClaims = List.copyOf(quarantinedClaims);
      steps = List.copyOf(steps);
    }
  }

  public MigrationOutcome migrate(String sourceVersion, Map<String, JsonNode> documents) {
    String normalizedVersion = normalizeVersion(sourceVersion);
    Objects.requireNonNull(documents, "documents");
    List<String> steps = migrationSteps(normalizedVersion);
    List<QuarantinedLegacyClaim> quarantined = new ArrayList<>();
    Map<String, String> migrated = new LinkedHashMap<>();

    documents.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              JsonNode copy = entry.getValue().deepCopy();
              migrateNode(copy, entry.getKey(), quarantined);
              if ("run.json".equals(entry.getKey()) && copy instanceof ObjectNode run) {
                migrateRunDocument(run, normalizedVersion, steps);
              }
              migrated.put(entry.getKey(), CanonicalJson.canonicalize(copy));
            });

    return new MigrationOutcome(
        normalizedVersion, TARGET_VERSION, migrated, quarantined, steps);
  }

  public static String normalizeVersion(String version) {
    String normalized =
        asciiLowercase(Objects.requireNonNullElse(version, "0.7").trim());
    if (normalized.startsWith("v")) {
      normalized = normalized.substring(1);
    }
    if (normalized.equals("0.7") || normalized.startsWith("0.7.")) {
      return "0.7";
    }
    if (normalized.equals("0.8") || normalized.equals("0.8.0")) {
      return "0.8.0";
    }
    if (normalized.equals("0.8.1")) {
      return "0.8.1";
    }
    if (normalized.equals(TARGET_VERSION)) {
      return TARGET_VERSION;
    }
    throw new LegacyImportException("unsupported legacy run version: " + version);
  }

  private static List<String> migrationSteps(String sourceVersion) {
    List<String> steps = new ArrayList<>();
    if ("0.7".equals(sourceVersion)) {
      steps.add("0.7-to-0.8.0-sidecar");
    }
    if ("0.7".equals(sourceVersion) || "0.8.0".equals(sourceVersion)) {
      steps.add("0.8.0-to-0.8.1-exactly-once-semantic-state");
    }
    if (!TARGET_VERSION.equals(sourceVersion)) {
      steps.add("0.8.1-to-0.8.2-checkpoint-dependency-namespace");
    }
    if (steps.isEmpty()) {
      steps.add("0.8.2-validated-without-rewrite");
    }
    return List.copyOf(steps);
  }

  private static void migrateRunDocument(
      ObjectNode run, String sourceVersion, List<String> steps) {
    run.put("version", TARGET_VERSION);
    if ("0.7".equals(sourceVersion)) {
      ObjectNode sidecar = run.withObject("/sidecar");
      putTextIfAbsent(sidecar, "protocol_version", "1.0");
      putTextIfAbsent(sidecar, "transport", "stdio-jsonl");
    }
    if ("0.7".equals(sourceVersion) || "0.8.0".equals(sourceVersion)) {
      putTextIfAbsent(run, "delivery_semantics", "exactly_once");
      putTextIfAbsent(run, "semantic_state_version", "0.8.1");
    }
    putTextIfAbsent(run, "checkpoint_namespace", "run-local");
    ObjectNode metadata = run.withObject("/legacy_import_provenance");
    metadata.put("source_version", sourceVersion);
    metadata.put("target_version", TARGET_VERSION);
    ArrayNode migrationSteps = metadata.putArray("migration_steps");
    steps.forEach(migrationSteps::add);
  }

  private static void migrateNode(
      JsonNode node, String sourceDocument, List<QuarantinedLegacyClaim> quarantined) {
    if (node instanceof ObjectNode object) {
      migrateDependency(object);
      migrateReceipt(object);
      quarantineClaim(object, sourceDocument, quarantined);
      List<JsonNode> children = new ArrayList<>();
      object.properties().forEach(entry -> children.add(entry.getValue()));
      children.forEach(child -> migrateNode(child, sourceDocument, quarantined));
    } else if (node instanceof ArrayNode array) {
      array.forEach(child -> migrateNode(child, sourceDocument, quarantined));
    }
  }

  private static void migrateDependency(ObjectNode object) {
    if (!object.has("kind")) {
      return;
    }
    String kind = object.path("kind").asText("");
    if ("legacy_external".equals(kind)) {
      object.put("kind", "external_claim");
      putTextIfAbsent(object, "namespace", "legacy");
      object.put("legacy_kind", "legacy_external");
    } else if (isDependencyKind(kind) && !object.has("namespace")) {
      object.put("namespace", namespaceFor(kind));
    }
  }

  private static boolean isDependencyKind(String kind) {
    return switch (kind) {
      case "local_step", "local_claim", "global_fact", "external_claim" -> true;
      default -> false;
    };
  }

  private static String namespaceFor(String kind) {
    return switch (kind) {
      case "local_step" -> "delta";
      case "local_claim" -> "attempt";
      case "global_fact" -> "broker";
      case "external_claim" -> "legacy";
      default -> throw new IllegalArgumentException("unsupported dependency kind");
    };
  }

  private static void migrateReceipt(ObjectNode object) {
    if (object.path("legacy_bypass").asBoolean(false)
        || object.path("claim_bypass").asBoolean(false)) {
      object.put("status", "quarantined");
      object.put("bypass_reactivated", false);
      object.put("migration_reason", "legacy receipt or claim bypass is not authoritative");
    }
  }

  private static void quarantineClaim(
      ObjectNode object,
      String sourceDocument,
      List<QuarantinedLegacyClaim> quarantined) {
    if (!object.has("claim_id") || !object.has("status")) {
      return;
    }
    String priorStatus = object.path("status").asText("");
    String normalizedStatus = asciiLowercase(priorStatus);
    boolean authorityClaim =
        "fact".equals(normalizedStatus) || "verified".equals(normalizedStatus);
    boolean audited =
        object.path("audited").asBoolean(false)
            || !object.path("verification_report_id").asText("").isBlank();
    if (!authorityClaim || audited) {
      return;
    }
    String claimId = object.path("claim_id").asText("");
    if (claimId.isBlank()) {
      throw new LegacyImportException("legacy authority claim has no claim_id");
    }
    String reason = "legacy authority claim lacks independent audit provenance";
    object.put("status", "quarantined");
    object.put("legacy_status", priorStatus);
    object.put("quarantine_reason", reason);
    quarantined.add(new QuarantinedLegacyClaim(claimId, priorStatus, reason, sourceDocument));
  }

  private static void putTextIfAbsent(ObjectNode object, String name, String value) {
    if (!object.has(name)) {
      object.put(name, value);
    }
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
