package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class PersistencePolicyTest {
  private static final Set<String> TABLES =
      Set.of(
          "run",
          "problem_contract",
          "strategy",
          "route",
          "route_member",
          "proof_attempt",
          "proof_step",
          "claim",
          "claim_dependency",
          "message",
          "message_delivery",
          "message_receipt",
          "message_utility",
          "memory_item",
          "memory_dependency",
          "memory_provenance",
          "memory_invalidation",
          "proof_obligation",
          "proof_graph_edge",
          "proof_checkpoint",
          "working_checkpoint",
          "verification_report",
          "referee_claim_ledger",
          "meta_review",
          "proof_control_state",
          "control_action",
          "inspiration_proposal",
          "inspiration_outcome",
          "experiment_spec",
          "experiment_result",
          "computation_cache",
          "artifact",
          "run_artifact",
          "provider_call",
          "usage_ledger",
          "event_log",
          "outbox_event",
          "inbox_event",
          "run_lease",
          "legacy_import");
  private static final Pattern JDBC_CALL = Pattern.compile("\\.sql\\s*\\(");
  private static final Pattern SQL_CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]*");

  @Test
  void serverUsesJdbcWithoutJpaHibernateOrH2() throws IOException {
    Path project = Path.of(System.getProperty("mathproofmesh.projectRoot"));
    String pom = Files.readString(project.resolve("mathproofmesh-server/pom.xml"));
    String production =
        readJava(project.resolve("mathproofmesh-server/src/main/java"));

    assertThat(pom)
        .doesNotContain("hibernate")
        .doesNotContain("jakarta.persistence")
        .doesNotContain("<artifactId>h2</artifactId>");
    assertThat(production)
        .doesNotContain("jakarta.persistence")
        .doesNotContain("javax.persistence")
        .doesNotContain("org.hibernate")
        .doesNotContain("EntityManager")
        .contains("FOR UPDATE SKIP LOCKED");
  }

  @Test
  void everyJdbcCallUsesAStaticSqlConstantOrLiteralTextBlock()
      throws IOException {
    Path project = Path.of(System.getProperty("mathproofmesh.projectRoot"));
    Path persistence =
        project.resolve(
            "mathproofmesh-server/src/main/java/io/github/aililuola/mathproofmesh/persistence");
    List<Path> sources;
    try (var paths = Files.walk(persistence)) {
      sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
    }

    for (Path source : sources) {
      assertNoConcatenatedSql(source, Files.readString(source));
    }
  }

  @Test
  void initialMigrationDefinesExactlyTheRequiredTableInventory()
      throws IOException {
    Path project = Path.of(System.getProperty("mathproofmesh.projectRoot"));
    String migration =
        Files.readString(
            project.resolve(
                "mathproofmesh-server/src/main/resources/db/migration/V1__initial_schema.sql"));
    Set<String> declared =
        Pattern.compile("(?m)^CREATE TABLE ([a-z_]+) \\(")
            .matcher(migration)
            .results()
            .map(result -> result.group(1))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    assertThat(declared).containsExactlyInAnyOrderElementsOf(TABLES);
    assertThat(migration)
        .contains("CREATE TRIGGER event_log_append_only")
        .contains("CREATE DOMAIN sha256_hex")
        .contains("CHECK (VALUE ~ '^[0-9a-f]{64}$')");
  }

  private static String readJava(Path root) throws IOException {
    List<Path> sources;
    try (var paths = Files.walk(root)) {
      sources = paths.filter(path -> path.toString().endsWith(".java")).toList();
    }
    StringBuilder result = new StringBuilder();
    for (Path source : sources) {
      result.append(Files.readString(source)).append('\n');
    }
    return result.toString();
  }

  private static void assertNoConcatenatedSql(Path source, String content) {
    var calls = JDBC_CALL.matcher(content);
    while (calls.find()) {
      int cursor = skipWhitespace(content, calls.end());
      if (content.startsWith("\"\"\"", cursor)) {
        int closing = content.indexOf("\"\"\"", cursor + 3);
        assertThat(closing)
            .as("closed SQL text block in %s", source)
            .isGreaterThan(cursor);
        int afterLiteral = skipWhitespace(content, closing + 3);
        assertThat(content.charAt(afterLiteral))
            .as("no Java concatenation after SQL text block in %s", source)
            .isEqualTo(')');
        continue;
      }

      var constant = SQL_CONSTANT.matcher(content);
      constant.region(cursor, content.length());
      assertThat(constant.lookingAt())
          .as("SQL argument must be a static constant in %s", source)
          .isTrue();
      int afterConstant = skipWhitespace(content, constant.end());
      assertThat(content.charAt(afterConstant))
          .as("no Java concatenation after SQL constant in %s", source)
          .isEqualTo(')');
    }
  }

  private static int skipWhitespace(String value, int offset) {
    int cursor = offset;
    while (cursor < value.length()
        && Character.isWhitespace(value.charAt(cursor))) {
      cursor++;
    }
    return cursor;
  }
}
