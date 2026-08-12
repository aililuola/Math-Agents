package io.github.aililuola.mathproofmesh.inspiration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.InspirationOutcome;
import io.github.aililuola.mathproofmesh.contract.NegativeAnalogyRecord;
import io.github.aililuola.mathproofmesh.contract.VerifiedExperienceRecord;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Project- and tenant-local learning store; no global user profile exists. */
public final class CrossRunLearningStore {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path projectRoot;
  private final Path root;
  private final boolean requireFinalCitation;

  public CrossRunLearningStore(
      Path projectRoot, Path configuredPath, String tenantId, boolean requireFinalCitation) {
    this.projectRoot =
        java.util.Objects.requireNonNull(projectRoot, "projectRoot")
            .toAbsolutePath()
            .normalize();
    Path candidate =
        configuredPath.isAbsolute()
            ? configuredPath
            : this.projectRoot.resolve(configuredPath);
    String tenant = safeTenant(tenantId);
    this.root = candidate.toAbsolutePath().normalize().resolve(tenant).normalize();
    this.requireFinalCitation = requireFinalCitation;
    if (!root.startsWith(this.projectRoot)) {
      throw new IllegalArgumentException(
          "cross-run learning path must stay inside project_root");
    }
  }

  public synchronized PersistResult persist(
      Collection<VerifiedExperienceRecord> experiences,
      Collection<NegativeAnalogyRecord> negatives,
      Collection<InspirationOutcome> outcomes,
      boolean runVerified)
      throws IOException {
    List<VerifiedExperienceRecord> admittedExperiences =
        (experiences == null ? List.<VerifiedExperienceRecord>of() : experiences).stream()
            .filter(item -> runVerified && item.verified())
            .filter(item -> !requireFinalCitation || item.citedByFinalProof())
            .toList();
    List<NegativeAnalogyRecord> admittedNegatives =
        negatives == null ? List.of() : List.copyOf(negatives);
    List<InspirationOutcome> admittedOutcomes =
        (outcomes == null ? List.<InspirationOutcome>of() : outcomes).stream()
            .filter(item -> item.materializationAction() != null)
            .toList();
    Files.createDirectories(root);
    writeMerged("verified_experiences.json", admittedExperiences, "record_id");
    writeMerged("negative_analogies.json", admittedNegatives, "record_id");
    writeMerged("outcomes.json", admittedOutcomes, "proposal_id");
    return new PersistResult(
        admittedExperiences.size(), admittedNegatives.size(), admittedOutcomes.size());
  }

  public Path root() {
    return root;
  }

  public boolean isProjectLocal() {
    return root.startsWith(projectRoot);
  }

  private void writeMerged(String name, Collection<?> values, String idField)
      throws IOException {
    Path destination = root.resolve(name);
    Map<String, JsonNode> merged = new LinkedHashMap<>();
    if (Files.isRegularFile(destination)) {
      JsonNode existing = JSON.readTree(Files.readString(destination, StandardCharsets.UTF_8));
      if (existing != null && existing.isArray()) {
        for (JsonNode item : existing) {
          String key = item.path(idField).asText("");
          if (!key.isBlank()) {
            merged.put(key, item);
          }
        }
      }
    }
    for (Object value : values) {
      JsonNode item = JSON.valueToTree(value);
      String key = item.path(idField).asText("");
      if (!key.isBlank()) {
        merged.putIfAbsent(key, item);
      }
    }
    List<JsonNode> ordered = new ArrayList<>(merged.values());
    ordered.sort(
        java.util.Comparator.comparing(item -> item.path(idField).asText("")));
    Path temporary = root.resolve(name + ".tmp");
    Files.writeString(
        temporary, CanonicalJson.canonicalize(ordered), StandardCharsets.UTF_8);
    try {
      Files.move(
          temporary,
          destination,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
      Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static String safeTenant(String value) {
    String tenant = value == null ? "" : value.strip();
    if (!tenant.matches("[A-Za-z0-9._-]{1,64}") || tenant.equals(".") || tenant.equals("..")) {
      throw new IllegalArgumentException("tenant id must be a bounded local identifier");
    }
    return tenant;
  }

  public record PersistResult(int experiences, int negatives, int outcomes) {}
}
