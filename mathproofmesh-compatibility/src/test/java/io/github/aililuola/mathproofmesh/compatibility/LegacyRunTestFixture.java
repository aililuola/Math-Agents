package io.github.aililuola.mathproofmesh.compatibility;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

final class LegacyRunTestFixture implements AutoCloseable {
  private final Path root;

  private LegacyRunTestFixture(Path root) {
    this.root = root;
  }

  static LegacyRunTestFixture create(String version, String status) throws IOException {
    Path root =
        fixtureTarget()
            .resolve("phase16-fixtures")
            .resolve(UUID.randomUUID().toString())
            .normalize();
    Files.createDirectories(root.resolve("artifacts"));
    Files.createDirectories(root.resolve("checkpoints"));
    Files.writeString(root.resolve("problem.txt"), "Prove that 1 + 1 = 2.\n", StandardCharsets.UTF_8);
    Files.writeString(
        root.resolve("artifacts/proof.txt"), "verified proof artifact\n", StandardCharsets.UTF_8);

    ObjectNode checkpoint0 = ContractObjectMapper.toTree(Map.of()).deepCopy();
    checkpoint0.put("checkpoint_id", "checkpoint-0");
    checkpoint0.put("parent_checkpoint_id", "");
    checkpoint0.put("status", "committed");
    checkpoint0.put("segment_index", 0);
    writeJson(root.resolve("checkpoints/checkpoint-0.json"), checkpoint0);

    ObjectNode checkpoint1 = ContractObjectMapper.toTree(Map.of()).deepCopy();
    checkpoint1.put("checkpoint_id", "checkpoint-1");
    checkpoint1.put("parent_checkpoint_id", "checkpoint-0");
    checkpoint1.put("status", "committed");
    checkpoint1.put("segment_index", 1);
    writeJson(root.resolve("checkpoints/checkpoint-1.json"), checkpoint1);

    ObjectNode latest = ContractObjectMapper.toTree(Map.of()).deepCopy();
    latest.put("latest_checkpoint_id", "checkpoint-1");
    writeJson(root.resolve("checkpoints/latest.json"), latest);

    ObjectNode run = ContractObjectMapper.toTree(Map.of()).deepCopy();
    run.put("version", version);
    run.put("run_id", "legacy-run");
    run.put("status", status);
    run.put("problem_path", "problem.txt");
    run.put("problem_hash", hash(root.resolve("problem.txt")));
    run.put("latest_checkpoint_id", "checkpoint-1");

    ArrayNode artifacts = run.putArray("artifacts");
    ObjectNode artifact = artifacts.addObject();
    artifact.put("path", "artifacts/proof.txt");
    artifact.put("sha256", hash(root.resolve("artifacts/proof.txt")));
    artifact.put("size_bytes", Files.size(root.resolve("artifacts/proof.txt")));

    ArrayNode claims = run.putArray("claims");
    ObjectNode claim = claims.addObject();
    claim.put("claim_id", "legacy-claim");
    claim.put("status", "FACT");
    claim.put("content_hash", "a".repeat(64));
    claim.put("audited", false);

    ArrayNode receipts = run.putArray("receipts");
    ObjectNode receipt = receipts.addObject();
    receipt.put("receipt_id", "legacy-receipt");
    receipt.put("status", "accepted");
    receipt.put("legacy_bypass", true);

    ArrayNode dependencies = run.putArray("dependencies");
    ObjectNode dependency = dependencies.addObject();
    dependency.put("kind", "legacy_external");
    dependency.put("target", "claim-7");

    writeJson(root.resolve("run.json"), run);
    return new LegacyRunTestFixture(root);
  }

  Path root() {
    return root;
  }

  void updateJson(String relativePath, Consumer<ObjectNode> update) throws IOException {
    Path file = root.resolve(relativePath);
    JsonNode parsed = ContractObjectMapper.parseTree(Files.readString(file, StandardCharsets.UTF_8));
    if (!(parsed instanceof ObjectNode object)) {
      throw new IllegalArgumentException("fixture document is not an object");
    }
    update.accept(object);
    writeJson(file, object);
  }

  void writeText(String relativePath, String content) throws IOException {
    Files.writeString(root.resolve(relativePath), content, StandardCharsets.UTF_8);
  }

  Map<String, String> hashes() throws IOException {
    Map<String, String> result = new LinkedHashMap<>();
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
        result.put(root.relativize(file).toString().replace('\\', '/'), hash(file));
      }
    }
    return Map.copyOf(result);
  }

  @Override
  public void close() throws IOException {
    Path allowed = root.getParent().getParent().toAbsolutePath().normalize();
    Path resolved = root.toAbsolutePath().normalize();
    if (!resolved.startsWith(allowed)) {
      throw new IOException("refusing to delete fixture outside the module target");
    }
    if (!Files.exists(resolved)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(resolved)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  static String hash(Path path) throws IOException {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
      StringBuilder value = new StringBuilder(64);
      for (byte item : digest) {
        value.append(Character.forDigit((item >>> 4) & 0x0f, 16));
        value.append(Character.forDigit(item & 0x0f, 16));
      }
      return value.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  private static void writeJson(Path path, JsonNode value) throws IOException {
    Files.writeString(path, ContractObjectMapper.write(value) + "\n", StandardCharsets.UTF_8);
  }

  private static Path fixtureTarget() {
    Path shortRoot = Path.of("P:\\");
    if (Files.isDirectory(shortRoot)) {
      return shortRoot.resolve("target");
    }
    return Path.of("target").toAbsolutePath();
  }
}
