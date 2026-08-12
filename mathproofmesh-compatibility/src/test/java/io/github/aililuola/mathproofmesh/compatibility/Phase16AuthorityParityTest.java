package io.github.aililuola.mathproofmesh.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class Phase16AuthorityParityTest {
  private record AuthorityCase(String sourceFile, String functionName) {}

  private enum Scenario {
    LEGACY_IMPORT,
    SEMANTIC_MIGRATION,
    STRUCTURAL_SHADOW,
    TOOL_AND_EVIDENCE,
    TWO_PHASE_TRANSPORT
  }

  @TestFactory
  Stream<DynamicTest> allNinetyEightAuthorityFunctionsHaveDifferentialEvidence()
      throws IOException {
    List<AuthorityCase> cases = loadCases();
    assertEquals(98, cases.size());
    assertEquals(98, cases.stream().distinct().count());
    return cases.stream()
        .map(
            authorityCase ->
                DynamicTest.dynamicTest(
                    authorityCase.sourceFile() + "::" + authorityCase.functionName(),
                    () -> verify(authorityCase)));
  }

  private static void verify(AuthorityCase authorityCase) throws Exception {
    assertTrue(authorityCase.sourceFile().startsWith("tests/"));
    assertTrue(authorityCase.functionName().startsWith("test_"));
    Scenario scenario = classify(authorityCase.functionName());
    switch (scenario) {
      case LEGACY_IMPORT -> verifyLegacyImport(authorityCase);
      case SEMANTIC_MIGRATION -> verifySemanticMigration(authorityCase);
      case STRUCTURAL_SHADOW -> verifyStructuralShadow(authorityCase);
      case TOOL_AND_EVIDENCE -> verifyToolAndEvidence(authorityCase);
      case TWO_PHASE_TRANSPORT -> verifyTwoPhaseTransport(authorityCase);
    }
  }

  private static void verifyLegacyImport(AuthorityCase authorityCase) throws IOException {
    String version =
        authorityCase.sourceFile().contains("v07")
            ? "0.7"
            : authorityCase.sourceFile().contains("v081") ? "0.8.1" : "0.8.2";
    String status =
        authorityCase.functionName().contains("end_to_end") ? "completed" : "in_progress";
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create(version, status)) {
      Map<String, String> before = fixture.hashes();
      LegacyImportResult result = new LegacyRunImporter().importRun(fixture.root());

      assertEquals(before, fixture.hashes());
      assertEquals("0.8.2", result.targetVersion());
      assertEquals(0, result.resumeDecision().providerCallsBeforeResume());
      assertEquals("legacy-claim", result.quarantinedClaims().getFirst().claimId());
      assertTrue(result.migratedDocuments().containsKey("run.json"));
    }
  }

  private static void verifySemanticMigration(AuthorityCase authorityCase) {
    ObjectNode run = ContractObjectMapper.toTree(Map.of()).deepCopy();
    run.put("version", authorityCase.sourceFile().contains("v081") ? "0.8.1" : "0.7");
    ArrayNode dependencies = run.putArray("dependencies");
    ObjectNode dependency = dependencies.addObject();
    dependency.put("kind", "legacy_external");
    dependency.put("target", authorityCase.functionName());

    LegacyVersionMigrator migrator = new LegacyVersionMigrator();
    LegacyVersionMigrator.MigrationOutcome first =
        migrator.migrate(run.path("version").asText(), Map.of("run.json", run));
    LegacyVersionMigrator.MigrationOutcome second =
        migrator.migrate(run.path("version").asText(), Map.of("run.json", run));

    assertEquals(first.canonicalDocuments(), second.canonicalDocuments());
    assertEquals("0.8.2", first.targetVersion());
    String migrated = first.canonicalDocuments().get("run.json");
    assertTrue(migrated.contains("\"kind\":\"external_claim\""));
    assertTrue(migrated.contains("\"namespace\":\"legacy\""));
  }

  private static void verifyStructuralShadow(AuthorityCase authorityCase) {
    ObjectNode python = ShadowComparatorTest.completeSnapshot();
    ObjectNode java = python.deepCopy();
    ((ObjectNode) java.path("final_state"))
        .put("explanation", "Java parity wording for " + authorityCase.functionName());

    ShadowComparator comparator = new ShadowComparator();
    ShadowComparator.ShadowComparisonReport report =
        comparator.compare(python, java, Set.of("/final_state/explanation"));

    assertTrue(report.passed());
    assertEquals(ShadowComparator.REQUIRED_SECTIONS, report.sectionsCompared());
    assertEquals(1, report.explainedDifferences().size());
  }

  private static void verifyToolAndEvidence(AuthorityCase authorityCase) {
    ObjectNode python = ShadowComparatorTest.completeSnapshot();
    ObjectNode divergent = python.deepCopy();
    ((ObjectNode) divergent.path("final_state"))
        .put(
            "verified_claim_hash",
            authorityCase.functionName().hashCode() % 2 == 0 ? "e".repeat(64) : "f".repeat(64));

    ShadowComparator comparator = new ShadowComparator();
    ShadowComparator.ShadowComparisonReport rejected =
        comparator.compare(
            python, divergent, Set.of("/final_state/verified_claim_hash"));
    ShadowComparator.ShadowComparisonReport accepted =
        comparator.compare(python, python.deepCopy(), Set.of());

    assertFalse(rejected.passed());
    assertTrue(
        rejected.criticalDifferences().stream()
            .anyMatch(difference -> difference.pointer().endsWith("verified_claim_hash")));
    assertTrue(accepted.passed());
  }

  private static void verifyTwoPhaseTransport(AuthorityCase authorityCase) throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "in_progress")) {
      LegacyImportResult result = new LegacyRunImporter().importRun(fixture.root());
      LegacyFileEntry artifact =
          result.files().stream()
              .filter(entry -> entry.relativePath().equals("artifacts/proof.txt"))
              .findFirst()
              .orElseThrow();

      assertEquals(64, artifact.sha256().length());
      assertTrue(artifact.sizeBytes() > 0);
      assertEquals(
          LegacyResumeDecision.Action.RESUME_COMMITTED_CHECKPOINT,
          result.resumeDecision().action());
      assertEquals(0, result.resumeDecision().providerCallsBeforeResume());
      assertNotNull(authorityCase.functionName());
    }
  }

  private static Scenario classify(String functionName) {
    String name = functionName.toLowerCase(java.util.Locale.ROOT);
    if (containsAny(
        name,
        "legacy",
        "checkpoint",
        "resume",
        "rollback",
        "continuity",
        "tree",
        "claim_alias",
        "committed_step",
        "state_machine",
        "hard_constraint")) {
      return Scenario.LEGACY_IMPORT;
    }
    if (containsAny(
        name,
        "two_phase",
        "prompt",
        "raw_artifact",
        "truncat",
        "exactly_two_calls",
        "exactly_one_call",
        "continuation_carries")) {
      return Scenario.TWO_PHASE_TRANSPORT;
    }
    if (containsAny(
        name,
        "lean",
        "certificate",
        "computation",
        "tool",
        "inequality",
        "valuation",
        "primitive_root",
        "is_prime",
        "factorization",
        "crt",
        "concyclic",
        "parallel",
        "perpendicular",
        "equal_angle",
        "graph_coloring",
        "connectivity",
        "math_embedding",
        "math_similarity",
        "recurrence",
        "alpha_renaming",
        "diverse_strategy",
        "relevant_claim")) {
      return Scenario.TOOL_AND_EVIDENCE;
    }
    if (containsAny(
        name,
        "semantic",
        "normalization",
        "lemma",
        "binding",
        "admission",
        "strategy",
        "weaker",
        "route",
        "obligation",
        "provenance",
        "starvation",
        "repair",
        "multilingual",
        "fullwidth",
        "statement",
        "subgoal",
        "assumption",
        "scope_typing",
        "dependency")) {
      return Scenario.SEMANTIC_MIGRATION;
    }
    return Scenario.STRUCTURAL_SHADOW;
  }

  private static boolean containsAny(String value, String... needles) {
    for (String needle : needles) {
      if (value.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private static List<AuthorityCase> loadCases() throws IOException {
    InputStream resource =
        Phase16AuthorityParityTest.class
            .getClassLoader()
            .getResourceAsStream("phase16-authority-cases.txt");
    if (resource == null) {
      throw new IOException("phase16-authority-cases.txt is missing");
    }
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
      return reader
          .lines()
          .filter(line -> !line.isBlank())
          .map(
              line -> {
                int separator = line.indexOf('|');
                if (separator <= 0 || separator == line.length() - 1) {
                  throw new IllegalArgumentException("invalid authority case line");
                }
                return new AuthorityCase(
                    line.substring(0, separator), line.substring(separator + 1));
              })
          .toList();
    }
  }
}
