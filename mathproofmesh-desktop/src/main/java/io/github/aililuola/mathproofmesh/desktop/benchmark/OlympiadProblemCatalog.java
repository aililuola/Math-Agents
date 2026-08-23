package io.github.aililuola.mathproofmesh.desktop.benchmark;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Loads only canonical problem text from the benchmark allowlist. */
public final class OlympiadProblemCatalog {
  private final Path benchmarkRoot;

  public OlympiadProblemCatalog(Path benchmarkRoot) {
    this.benchmarkRoot =
        Objects.requireNonNull(benchmarkRoot, "benchmarkRoot").toAbsolutePath().normalize();
  }

  public Map<String, ProblemPrompt> loadAll() {
    Map<String, ProblemPrompt> prompts = new LinkedHashMap<>();
    for (int problem = 1; problem <= 20; problem++) {
      ProblemPrompt prompt = load("P%02d".formatted(problem));
      prompts.put(prompt.problemId(), prompt);
    }
    return Map.copyOf(prompts);
  }

  @SuppressFBWarnings(
      value = "PATH_TRAVERSAL_IN",
      justification =
          "The problem identifier is validated to P01-P20 and the normalized path is checked "
              + "beneath the immutable benchmark root before reading.")
  public ProblemPrompt load(String problemId) {
    OlympiadBenchmarkPlan.problemNumber(problemId);
    Path path =
        benchmarkRoot.resolve("problems").resolve(problemId).resolve("problem.txt").normalize();
    if (!path.startsWith(benchmarkRoot) || !Files.isRegularFile(path)) {
      throw new IllegalArgumentException("canonical problem file is missing");
    }
    try {
      String prompt = normalize(Files.readString(path, StandardCharsets.UTF_8));
      OlympiadPromptPolicy.validateCanonicalProblem(prompt);
      return new ProblemPrompt(problemId, prompt, sha256(prompt));
    } catch (IOException exception) {
      throw new IllegalStateException("canonical problem file could not be read", exception);
    }
  }

  static String normalize(String value) {
    String normalized = Objects.requireNonNull(value, "value").replace("\r\n", "\n").replace('\r', '\n');
    while (normalized.endsWith("\n")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  public static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record ProblemPrompt(String problemId, String text, String sha256) {
    public ProblemPrompt {
      OlympiadBenchmarkPlan.problemNumber(problemId);
      text = Objects.requireNonNull(text, "text");
      sha256 = Objects.requireNonNull(sha256, "sha256");
      if (text.isBlank() || !sha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("invalid canonical problem prompt");
      }
    }
  }
}
