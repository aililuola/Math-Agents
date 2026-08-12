package io.github.aililuola.mathproofmesh.inspiration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Verified-only, project-local analogy retrieval with deterministic ranking. */
public final class LocalAnalogyLibrary {
  private static final long MAX_LIBRARY_BYTES = 4L * 1024L * 1024L;
  private static final int MAX_RECORDS = 10_000;
  private static final Pattern TOKEN = Pattern.compile("[a-z0-9_]+");
  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path path;
  private final boolean enabled;
  private final List<Record> records = new ArrayList<>();
  private final List<NegativeRecord> negatives = new ArrayList<>();
  private final List<String> diagnostics = new ArrayList<>();

  public LocalAnalogyLibrary(Path path, boolean enabled) {
    this.path = java.util.Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    this.enabled = enabled;
    reload();
  }

  public synchronized void reload() {
    records.clear();
    negatives.clear();
    diagnostics.clear();
    if (!enabled) {
      return;
    }
    if (!Files.isRegularFile(path)) {
      diagnostics.add("analogy library not found: " + path);
      return;
    }
    try {
      if (Files.size(path) > MAX_LIBRARY_BYTES) {
        diagnostics.add("analogy library exceeds the bounded size limit");
        return;
      }
      int lineNumber = 0;
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        lineNumber++;
        if (line.isBlank()) {
          continue;
        }
        if (records.size() + negatives.size() >= MAX_RECORDS) {
          diagnostics.add("analogy library record limit reached");
          break;
        }
        JsonNode node = JSON.readTree(line);
        if (!node.isObject()) {
          continue;
        }
        if (node.path("negative").asBoolean(false)) {
          negatives.add(
              new NegativeRecord(
                  text(node, "record_id", "negative_local_" + lineNumber),
                  text(node, "problem_hash", ""),
                  text(node, "source_record_id", ""),
                  text(node, "failure_reason", "rejected transfer")));
        } else if (node.path("verified").asBoolean(false)) {
          records.add(parseRecord(node, lineNumber));
        }
      }
    } catch (IOException | RuntimeException exception) {
      records.clear();
      negatives.clear();
      diagnostics.add("analogy library unavailable: " + exception.getMessage());
    }
  }

  public synchronized boolean addVerified(Record record) {
    java.util.Objects.requireNonNull(record, "record");
    if (!record.verified()) {
      throw new IllegalArgumentException("only verified experiences may enter the library");
    }
    if (records.stream().anyMatch(item -> item.recordId().equals(record.recordId()))) {
      return false;
    }
    records.add(record);
    return true;
  }

  public synchronized boolean addNegative(NegativeRecord record) {
    java.util.Objects.requireNonNull(record, "record");
    if (negatives.stream().anyMatch(item -> item.recordId().equals(record.recordId()))) {
      return false;
    }
    negatives.add(record);
    return true;
  }

  public synchronized List<Record> search(
      Query query, String problemHash, int topK) {
    if (topK <= 0 || records.isEmpty()) {
      return List.of();
    }
    Set<String> blocked =
        negatives.stream()
            .filter(
                item ->
                    item.sourceRecordId() != null
                        && !item.sourceRecordId().isBlank()
                        && (problemHash == null
                            || item.problemHash().isBlank()
                            || constantTimeEqual(item.problemHash(), problemHash)))
            .map(NegativeRecord::sourceRecordId)
            .collect(java.util.stream.Collectors.toSet());
    List<Scored> scored = new ArrayList<>();
    Set<String> queryTokens = tokens(query.queryText());
    for (Record record : records) {
      if (blocked.contains(record.recordId())) {
        continue;
      }
      double score = overlap(queryTokens, tokens(record.searchText()));
      score += 2.0d * intersection(query.objectTags(), record.objectTags());
      score += 2.0d * intersection(query.operationTags(), record.operationTags());
      score += 1.5d * intersection(query.mechanismTags(), record.mechanismTags());
      score += 1.5d * intersection(query.graphTags(), record.graphTags());
      score += 3.0d * intersection(query.obligationKinds(), record.obligationKinds());
      score += 2.5d * intersection(query.mechanismChain(), record.mechanismChain());
      score += 3.0d * intersection(query.graphMotifTags(), record.graphMotifTags());
      if (score > 0.0d) {
        scored.add(new Scored(score, record));
      }
    }
    return scored.stream()
        .sorted(
            Comparator.comparingDouble(Scored::score)
                .reversed()
                .thenComparing(item -> item.record().recordId()))
        .limit(topK)
        .map(Scored::record)
        .toList();
  }

  public synchronized List<String> diagnostics() {
    return List.copyOf(diagnostics);
  }

  public synchronized int verifiedSize() {
    return records.size();
  }

  public synchronized int negativeSize() {
    return negatives.size();
  }

  private static Record parseRecord(JsonNode node, int lineNumber) {
    return new Record(
        text(node, "record_id", "local_" + lineNumber),
        true,
        text(node, "problem_summary", ""),
        text(node, "proof_summary", ""),
        strings(node, "object_tags"),
        strings(node, "operation_tags"),
        strings(node, "mechanism_tags"),
        strings(node, "representation_tags"),
        strings(node, "proof_principles"),
        strings(node, "graph_tags"),
        strings(node, "obligation_kinds"),
        strings(node, "mechanism_chain"),
        strings(node, "obligation_graph_motif"),
        stringMap(node, "object_correspondence"),
        stringMap(node, "operation_correspondence"),
        strings(node, "transferable_lemmas"),
        strings(node, "non_transferable_conditions"),
        strings(node, "transfer_risks"),
        strings(node, "required_bridge_lemmas"));
  }

  private static String text(JsonNode node, String field, String fallback) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? fallback : value.asText(fallback).strip();
  }

  private static List<String> strings(JsonNode node, String field) {
    JsonNode values = node.get(field);
    if (values == null || !values.isArray()) {
      return List.of();
    }
    List<String> result = new ArrayList<>();
    for (JsonNode value : values) {
      String text = value.asText("").strip();
      if (!text.isEmpty() && !result.contains(text)) {
        result.add(text);
      }
    }
    return List.copyOf(result);
  }

  private static Map<String, String> stringMap(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isObject()) {
      return Map.of();
    }
    Map<String, String> result = new LinkedHashMap<>();
    value.properties()
        .forEach(
            entry -> {
              String text = entry.getValue().asText("").strip();
              if (!text.isEmpty()) {
                result.put(entry.getKey(), text);
              }
            });
    return Map.copyOf(result);
  }

  private static Set<String> tokens(String value) {
    Set<String> result = new LinkedHashSet<>();
    var matcher = TOKEN.matcher(value == null ? "" : value.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      result.add(matcher.group());
    }
    return result;
  }

  private static double overlap(Set<String> left, Set<String> right) {
    return left.stream().filter(right::contains).count();
  }

  private static int intersection(List<String> left, List<String> right) {
    Set<String> values =
        right.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(
            java.util.stream.Collectors.toSet());
    return (int)
        left.stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .filter(values::contains)
            .distinct()
            .count();
  }

  private record Scored(double score, Record record) {}

  public record Query(
      String queryText,
      List<String> objectTags,
      List<String> operationTags,
      List<String> mechanismTags,
      List<String> graphTags,
      List<String> obligationKinds,
      List<String> mechanismChain,
      List<String> graphMotifTags) {
    public Query {
      queryText = queryText == null ? "" : queryText;
      objectTags = copy(objectTags);
      operationTags = copy(operationTags);
      mechanismTags = copy(mechanismTags);
      graphTags = copy(graphTags);
      obligationKinds = copy(obligationKinds);
      mechanismChain = copy(mechanismChain);
      graphMotifTags = copy(graphMotifTags);
    }

    public List<String> objectTags() {
      return List.copyOf(objectTags);
    }

    public List<String> operationTags() {
      return List.copyOf(operationTags);
    }

    public List<String> mechanismTags() {
      return List.copyOf(mechanismTags);
    }

    public List<String> graphTags() {
      return List.copyOf(graphTags);
    }

    public List<String> obligationKinds() {
      return List.copyOf(obligationKinds);
    }

    public List<String> mechanismChain() {
      return List.copyOf(mechanismChain);
    }

    public List<String> graphMotifTags() {
      return List.copyOf(graphMotifTags);
    }
  }

  public record Record(
      String recordId,
      boolean verified,
      String problemSummary,
      String proofSummary,
      List<String> objectTags,
      List<String> operationTags,
      List<String> mechanismTags,
      List<String> representationTags,
      List<String> proofPrinciples,
      List<String> graphTags,
      List<String> obligationKinds,
      List<String> mechanismChain,
      List<String> graphMotifTags,
      Map<String, String> objectCorrespondence,
      Map<String, String> operationCorrespondence,
      List<String> transferableLemmas,
      List<String> nonTransferableConditions,
      List<String> transferRisks,
      List<String> requiredBridgeLemmas) {
    public Record {
      recordId = required(recordId, "recordId");
      problemSummary = problemSummary == null ? "" : problemSummary.strip();
      proofSummary = proofSummary == null ? "" : proofSummary.strip();
      objectTags = copy(objectTags);
      operationTags = copy(operationTags);
      mechanismTags = copy(mechanismTags);
      representationTags = copy(representationTags);
      proofPrinciples = copy(proofPrinciples);
      graphTags = copy(graphTags);
      obligationKinds = copy(obligationKinds);
      mechanismChain = copy(mechanismChain);
      graphMotifTags = copy(graphMotifTags);
      objectCorrespondence =
          objectCorrespondence == null ? Map.of() : Map.copyOf(objectCorrespondence);
      operationCorrespondence =
          operationCorrespondence == null ? Map.of() : Map.copyOf(operationCorrespondence);
      transferableLemmas = copy(transferableLemmas);
      nonTransferableConditions = copy(nonTransferableConditions);
      transferRisks = copy(transferRisks);
      requiredBridgeLemmas = copy(requiredBridgeLemmas);
    }

    public List<String> objectTags() {
      return List.copyOf(objectTags);
    }

    public List<String> operationTags() {
      return List.copyOf(operationTags);
    }

    public List<String> mechanismTags() {
      return List.copyOf(mechanismTags);
    }

    public List<String> representationTags() {
      return List.copyOf(representationTags);
    }

    public List<String> proofPrinciples() {
      return List.copyOf(proofPrinciples);
    }

    public List<String> graphTags() {
      return List.copyOf(graphTags);
    }

    public List<String> obligationKinds() {
      return List.copyOf(obligationKinds);
    }

    public List<String> mechanismChain() {
      return List.copyOf(mechanismChain);
    }

    public List<String> graphMotifTags() {
      return List.copyOf(graphMotifTags);
    }

    public Map<String, String> objectCorrespondence() {
      return Map.copyOf(objectCorrespondence);
    }

    public Map<String, String> operationCorrespondence() {
      return Map.copyOf(operationCorrespondence);
    }

    public List<String> transferableLemmas() {
      return List.copyOf(transferableLemmas);
    }

    public List<String> nonTransferableConditions() {
      return List.copyOf(nonTransferableConditions);
    }

    public List<String> transferRisks() {
      return List.copyOf(transferRisks);
    }

    public List<String> requiredBridgeLemmas() {
      return List.copyOf(requiredBridgeLemmas);
    }

    String searchText() {
      return String.join(
          " ",
          problemSummary,
          proofSummary,
          String.join(" ", objectTags),
          String.join(" ", operationTags),
          String.join(" ", mechanismTags),
          String.join(" ", obligationKinds),
          String.join(" ", mechanismChain));
    }
  }

  public record NegativeRecord(
      String recordId, String problemHash, String sourceRecordId, String failureReason) {
    public NegativeRecord {
      recordId = required(recordId, "recordId");
      problemHash = problemHash == null ? "" : problemHash.strip();
      sourceRecordId = sourceRecordId == null ? "" : sourceRecordId.strip();
      failureReason = required(failureReason, "failureReason");
    }
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }

  private static <T> List<T> copy(List<T> values) {
    return values == null ? List.of() : List.copyOf(values);
  }

  private static boolean constantTimeEqual(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }
}
