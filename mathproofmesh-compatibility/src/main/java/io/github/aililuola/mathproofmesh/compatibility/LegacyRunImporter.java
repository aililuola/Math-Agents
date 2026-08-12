package io.github.aililuola.mathproofmesh.compatibility;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

public final class LegacyRunImporter {
  private static final long MAX_FILE_BYTES = 100L * 1024L * 1024L;
  private static final long MAX_TOTAL_BYTES = 1024L * 1024L * 1024L;
  private static final int MAX_FILES = 10_000;
  private static final Pattern DRIVE_ABSOLUTE = Pattern.compile("^[A-Za-z]:[\\\\/].*");
  private static final ObjectMapper JSON =
      JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

  private final LegacyVersionMigrator versionMigrator;
  private final ConcurrentMap<String, LegacyImportResult> imports = new ConcurrentHashMap<>();

  public LegacyRunImporter() {
    this(new LegacyVersionMigrator());
  }

  public LegacyRunImporter(LegacyVersionMigrator versionMigrator) {
    this.versionMigrator = Objects.requireNonNull(versionMigrator, "versionMigrator");
  }

  public LegacyImportResult importRun(Path requestedRoot) {
    Snapshot snapshot = snapshot(requestedRoot);
    LegacyImportResult existing = imports.get(snapshot.manifestHash());
    if (existing != null) {
      verifyUnchanged(snapshot.root(), snapshot.files());
      return existing;
    }

    JsonNode run = requireRunDocument(snapshot.documents());
    validateReferences(snapshot.root(), run);
    validateProblemHash(snapshot.files(), run);
    validateArtifacts(snapshot.files(), run);
    CheckpointValidation checkpoints = validateCheckpoints(snapshot.documents(), run);

    String sourceVersion =
        firstText(run, "version", "format_version", "schema_version").orElse("0.7");
    LegacyVersionMigrator.MigrationOutcome migration =
        versionMigrator.migrate(sourceVersion, snapshot.documents());
    String runStatus = firstText(run, "status", "run_status").orElse("in_progress");
    LegacyResumeDecision resumeDecision =
        LegacyResumeDecision.decide(runStatus, checkpoints.latestCommittedCheckpointId());

    String targetRunId = "legacy-" + snapshot.manifestHash().substring(0, 24);
    LegacyImportResult result =
        new LegacyImportResult(
            "import-" + snapshot.manifestHash(),
            snapshot.manifestHash(),
            snapshot.root().toString(),
            migration.sourceVersion(),
            migration.targetVersion(),
            targetRunId,
            "IMPORTED",
            snapshot.files(),
            migration.canonicalDocuments(),
            migration.quarantinedClaims(),
            migration.steps(),
            resumeDecision);

    verifyUnchanged(snapshot.root(), snapshot.files());
    LegacyImportResult raced = imports.putIfAbsent(snapshot.manifestHash(), result);
    return raced == null ? result : raced;
  }

  public int registeredImports() {
    return imports.size();
  }

  private static Snapshot snapshot(Path requestedRoot) {
    Objects.requireNonNull(requestedRoot, "requestedRoot");
    try {
      if (Files.isSymbolicLink(requestedRoot)) {
        throw new LegacyImportException("legacy run root cannot be a symbolic link");
      }
      Path root = requestedRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
      BasicFileAttributes rootAttributes =
          Files.readAttributes(
              root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!rootAttributes.isDirectory() || rootAttributes.isOther()) {
        throw new LegacyImportException("legacy run root must be a regular directory");
      }

      List<Path> discovered = discoverFiles(root);
      List<LegacyFileEntry> files = new ArrayList<>(discovered.size());
      Map<String, JsonNode> documents = new LinkedHashMap<>();
      long totalBytes = 0;
      for (Path file : discovered) {
        BasicFileAttributes attributes =
            Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
            || attributes.isSymbolicLink()
            || attributes.isOther()) {
          throw new LegacyImportException("legacy run contains a non-regular file");
        }
        if (attributes.size() > MAX_FILE_BYTES) {
          throw new LegacyImportException("legacy file exceeds the per-file size limit");
        }
        totalBytes = Math.addExact(totalBytes, attributes.size());
        if (totalBytes > MAX_TOTAL_BYTES) {
          throw new LegacyImportException("legacy run exceeds the total size limit");
        }

        String relative = relativePath(root, file);
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != attributes.size()) {
          throw new LegacyImportException("legacy file changed while being read: " + relative);
        }
        files.add(new LegacyFileEntry(relative, bytes.length, sha256(bytes)));
        if (relative.endsWith(".json")) {
          documents.put(relative, parseJson(bytes, relative));
        } else if (relative.endsWith(".jsonl")) {
          documents.put(relative, parseJsonLines(bytes, relative));
        }
      }
      files.sort(Comparator.comparing(LegacyFileEntry::relativePath));
      StringBuilder manifest = new StringBuilder();
      files.forEach(
          entry ->
              manifest
                  .append(entry.sha256())
                  .append("  ")
                  .append(entry.relativePath())
                  .append('\n'));
      return new Snapshot(
          root,
          List.copyOf(files),
          Map.copyOf(documents),
          sha256(manifest.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (IOException | ArithmeticException exception) {
      throw new LegacyImportException("could not read legacy run safely", exception);
    }
  }

  private static List<Path> discoverFiles(Path root) throws IOException {
    List<Path> files = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
              throws IOException {
            if (attributes.isSymbolicLink()
                || attributes.isOther()
                || Files.isSymbolicLink(dir)) {
              throw new LegacyImportException("legacy run contains a linked directory");
            }
            Path followed = dir.toRealPath();
            if (!followed.startsWith(root)) {
              throw new LegacyImportException("legacy directory escapes the selected root");
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (files.size() >= MAX_FILES) {
              throw new LegacyImportException("legacy run exceeds the file-count limit");
            }
            if (attributes.isSymbolicLink()
                || attributes.isOther()
                || Files.isSymbolicLink(file)) {
              throw new LegacyImportException("legacy run contains a linked or special file");
            }
            files.add(file);
            return FileVisitResult.CONTINUE;
          }
        });
    files.sort(Comparator.comparing(path -> relativePath(root, path)));
    return files;
  }

  private static JsonNode parseJson(byte[] bytes, String relativePath) {
    try {
      JsonNode node = JSON.readTree(bytes);
      if (node == null) {
        throw new LegacyImportException("empty JSON document: " + relativePath);
      }
      return node;
    } catch (JsonProcessingException exception) {
      throw new LegacyImportException("invalid JSON document: " + relativePath, exception);
    } catch (IOException exception) {
      throw new LegacyImportException("could not parse JSON document: " + relativePath, exception);
    }
  }

  private static JsonNode parseJsonLines(byte[] bytes, String relativePath) {
    ArrayNode lines = JSON.createArrayNode();
    String text = new String(bytes, StandardCharsets.UTF_8);
    int lineNumber = 0;
    for (String line : text.split("\\R", -1)) {
      lineNumber++;
      if (line.isBlank()) {
        continue;
      }
      try {
        lines.add(JSON.readTree(line));
      } catch (JsonProcessingException exception) {
        throw new LegacyImportException(
            "invalid JSONL document: " + relativePath + ":" + lineNumber, exception);
      }
    }
    return lines;
  }

  private static JsonNode requireRunDocument(Map<String, JsonNode> documents) {
    JsonNode run = documents.get("run.json");
    if (run == null || !run.isObject()) {
      throw new LegacyImportException("legacy run must contain an object-valued run.json");
    }
    return run;
  }

  private static void validateProblemHash(List<LegacyFileEntry> files, JsonNode run) {
    String expected =
        firstText(run, "problem_hash", "problem_integrity_hash")
            .orElseThrow(() -> new LegacyImportException("run.json has no problem hash"));
    String problemPath = firstText(run, "problem_path").orElse("problem.txt");
    LegacyFileEntry problem = requireManifestEntry(files, problemPath);
    if (!hashEquals(expected, problem.sha256())) {
      throw new LegacyImportException("problem hash does not match the legacy problem bytes");
    }
  }

  private static void validateArtifacts(List<LegacyFileEntry> files, JsonNode run) {
    JsonNode artifacts = run.path("artifacts");
    if (artifacts.isMissingNode()) {
      return;
    }
    if (!artifacts.isArray()) {
      throw new LegacyImportException("run artifacts must be an array");
    }
    for (JsonNode artifact : artifacts) {
      if (!artifact.isObject()) {
        throw new LegacyImportException("artifact entry must be an object");
      }
      String path =
          firstText(artifact, "path", "storage_path")
              .orElseThrow(() -> new LegacyImportException("artifact has no path"));
      String expectedHash =
          firstText(artifact, "sha256", "content_hash")
              .orElseThrow(() -> new LegacyImportException("artifact has no hash"));
      LegacyFileEntry entry = requireManifestEntry(files, path);
      if (!hashEquals(entry.sha256(), expectedHash)) {
        throw new LegacyImportException("artifact hash mismatch: " + path);
      }
      JsonNode expectedSize = artifact.has("size_bytes") ? artifact.get("size_bytes") : artifact.get("size");
      if (expectedSize != null
          && (!expectedSize.canConvertToLong()
              || expectedSize.longValue() != entry.sizeBytes())) {
        throw new LegacyImportException("artifact size mismatch: " + path);
      }
    }
  }

  private static CheckpointValidation validateCheckpoints(
      Map<String, JsonNode> documents, JsonNode run) {
    Map<String, JsonNode> checkpoints = new HashMap<>();
    documents.forEach(
        (path, node) -> collectCheckpoints(node, path, checkpoints));
    if (checkpoints.isEmpty()) {
      throw new LegacyImportException("legacy run has no checkpoint chain");
    }

    Map<String, String> parents = new HashMap<>();
    checkpoints.forEach(
        (id, checkpoint) -> {
          String parent = firstText(checkpoint, "parent_checkpoint_id", "parent_id").orElse("");
          if (!parent.isBlank() && !checkpoints.containsKey(parent)) {
            throw new LegacyImportException("checkpoint parent does not exist: " + parent);
          }
          parents.put(id, parent);
        });
    detectCheckpointCycles(parents);

    String latest =
        firstText(run, "latest_checkpoint_id")
            .orElseGet(() -> latestPointer(documents));
    if (latest.isBlank() || !checkpoints.containsKey(latest)) {
      throw new LegacyImportException("latest checkpoint pointer is missing or invalid");
    }
    String latestStatus = firstText(checkpoints.get(latest), "status").orElse("");
    if (!"committed".equals(asciiLowercase(latestStatus))) {
      throw new LegacyImportException("latest checkpoint is not committed");
    }
    return new CheckpointValidation(latest);
  }

  private static void collectCheckpoints(
      JsonNode node, String sourcePath, Map<String, JsonNode> checkpoints) {
    if (node.isObject()) {
      boolean looksLikeCheckpoint =
          node.has("checkpoint_id")
              && (node.has("status")
                  || node.has("parent_checkpoint_id")
                  || node.has("segment_index"));
      if (looksLikeCheckpoint) {
        String id = node.path("checkpoint_id").asText("");
        if (id.isBlank()) {
          throw new LegacyImportException("checkpoint has an empty id in " + sourcePath);
        }
        JsonNode previous = checkpoints.putIfAbsent(id, node);
        if (previous != null
            && !CanonicalJson.canonicalize(previous).equals(CanonicalJson.canonicalize(node))) {
          throw new LegacyImportException("conflicting duplicate checkpoint: " + id);
        }
      }
      node.properties()
          .forEach(entry -> collectCheckpoints(entry.getValue(), sourcePath, checkpoints));
    } else if (node.isArray()) {
      node.forEach(child -> collectCheckpoints(child, sourcePath, checkpoints));
    }
  }

  private static void detectCheckpointCycles(Map<String, String> parents) {
    Set<String> complete = new HashSet<>();
    for (String checkpoint : parents.keySet()) {
      if (complete.contains(checkpoint)) {
        continue;
      }
      Set<String> visiting = new HashSet<>();
      Deque<String> chain = new ArrayDeque<>();
      String current = checkpoint;
      while (!current.isBlank() && !complete.contains(current)) {
        if (!visiting.add(current)) {
          throw new LegacyImportException("checkpoint parent cycle detected at " + current);
        }
        chain.push(current);
        current = parents.getOrDefault(current, "");
      }
      complete.addAll(chain);
    }
  }

  private static String latestPointer(Map<String, JsonNode> documents) {
    JsonNode pointer = documents.get("checkpoints/latest.json");
    if (pointer == null) {
      pointer = documents.get("latest.json");
    }
    return pointer == null
        ? ""
        : firstText(pointer, "latest_checkpoint_id", "checkpoint_id").orElse("");
  }

  private static void validateReferences(Path root, JsonNode node) {
    validateReferences(root, node, "");
  }

  private static void validateReferences(Path root, JsonNode node, String fieldName) {
    if (node.isObject()) {
      node.properties()
          .forEach(
              entry ->
                  validateReferences(
                      root, entry.getValue(), asciiLowercase(entry.getKey())));
      return;
    }
    if (node.isArray()) {
      node.forEach(child -> validateReferences(root, child, fieldName));
      return;
    }
    if (!node.isTextual() || !isPathOrExternalReferenceField(fieldName)) {
      return;
    }
    String value = node.textValue().replace('\\', '/');
    if (value.isBlank()) {
      return;
    }
    if (value.startsWith("artifact://sha256/")) {
      String hash = value.substring("artifact://sha256/".length());
      if (!hash.matches("[0-9a-f]{64}")) {
        throw new LegacyImportException("invalid content-addressed artifact reference");
      }
      return;
    }
    if (value.contains("://")
        || value.startsWith("/")
        || DRIVE_ABSOLUTE.matcher(value).matches()) {
      throw new LegacyImportException("external or absolute reference is not trusted: " + fieldName);
    }
    Path resolved = root.resolve(value).normalize();
    if (!resolved.startsWith(root) || value.contains("../")) {
      throw new LegacyImportException("reference escapes the legacy run root: " + fieldName);
    }
  }

  private static boolean isPathOrExternalReferenceField(String fieldName) {
    return fieldName.equals("path")
        || fieldName.endsWith("_path")
        || fieldName.equals("uri")
        || fieldName.endsWith("_uri")
        || fieldName.equals("url")
        || fieldName.endsWith("_url")
        || fieldName.equals("artifact_ref")
        || fieldName.equals("external_ref");
  }

  private static LegacyFileEntry requireManifestEntry(
      List<LegacyFileEntry> files, String relativePath) {
    String normalized = normalizeRelativeReference(relativePath);
    return files.stream()
        .filter(entry -> entry.relativePath().equals(normalized))
        .findFirst()
        .orElseThrow(
            () -> new LegacyImportException("referenced legacy file is absent: " + normalized));
  }

  private static String normalizeRelativeReference(String value) {
    String normalized = value == null ? "" : value.replace('\\', '/');
    if (normalized.isBlank()
        || normalized.startsWith("/")
        || DRIVE_ABSOLUTE.matcher(normalized).matches()) {
      throw new LegacyImportException("legacy file reference must be relative");
    }
    Path parsed = Path.of(normalized).normalize();
    String portable = parsed.toString().replace('\\', '/');
    if (portable.equals("..") || portable.startsWith("../")) {
      throw new LegacyImportException("legacy file reference escapes the selected root");
    }
    return portable;
  }

  private static void verifyUnchanged(Path root, List<LegacyFileEntry> expected) {
    for (LegacyFileEntry entry : expected) {
      Path file = root.resolve(entry.relativePath()).normalize();
      if (!file.startsWith(root) || Files.isSymbolicLink(file)) {
        throw new LegacyImportException("legacy source changed to an unsafe path");
      }
      try {
        BasicFileAttributes attributes =
            Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()
            || attributes.size() != entry.sizeBytes()
            || !hashEquals(sha256(Files.readAllBytes(file)), entry.sha256())) {
          throw new LegacyImportException(
              "legacy source changed during import: " + entry.relativePath());
        }
      } catch (IOException exception) {
        throw new LegacyImportException(
            "could not revalidate legacy source: " + entry.relativePath(), exception);
      }
    }
  }

  private static java.util.Optional<String> firstText(JsonNode node, String... fields) {
    for (String field : fields) {
      JsonNode value = node.get(field);
      if (value != null && value.isTextual() && !value.textValue().isBlank()) {
        return java.util.Optional.of(value.textValue());
      }
    }
    return java.util.Optional.empty();
  }

  private static String relativePath(Path root, Path file) {
    String relative = root.relativize(file).toString().replace('\\', '/');
    if (relative.isBlank() || relative.startsWith("../")) {
      throw new LegacyImportException("legacy path is not confined to the selected root");
    }
    return relative;
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder value = new StringBuilder(64);
      for (byte item : digest) {
        value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
        value.append(Character.forDigit(item & 0x0f, 16));
      }
      return value.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }

  private static boolean hashEquals(String left, String right) {
    if (left == null || right == null) {
      return false;
    }
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
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

  private record Snapshot(
      Path root,
      List<LegacyFileEntry> files,
      Map<String, JsonNode> documents,
      String manifestHash) {}

  private record CheckpointValidation(String latestCommittedCheckpointId) {}
}
