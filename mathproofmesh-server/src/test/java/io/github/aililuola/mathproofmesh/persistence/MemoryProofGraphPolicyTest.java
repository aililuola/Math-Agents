package io.github.aililuola.mathproofmesh.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MemoryProofGraphPolicyTest {
  private static final Path ROOT =
      Path.of(System.getProperty("mathproofmesh.projectRoot"));

  @Test
  void migrationAddsTransactionalInvalidationAndReverseTraversalIndexes()
      throws IOException {
    String migration =
        Files.readString(
            ROOT.resolve(
                "mathproofmesh-server/src/main/resources/db/migration/"
                    + "V3__memory_and_proof_graph_authority.sql"));

    assertThat(migration)
        .contains("invalidated_reason")
        .contains("propagation_batch_id")
        .contains("needs_reverify")
        .contains("CREATE UNIQUE INDEX uq_memory_invalidation_batch_item")
        .contains("ix_memory_dependency_reverse")
        .contains("ix_proof_graph_edge_reverse");
  }

  @Test
  void repositoryUsesOneRecursiveClosureAndFourBulkProjectionReads()
      throws IOException {
    String repository =
        Files.readString(
            ROOT.resolve(
                "mathproofmesh-server/src/main/java/io/github/aililuola/"
                    + "mathproofmesh/persistence/JdbcMemoryProofGraphRepository.java"));

    assertThat(repository)
        .contains("WITH RECURSIVE affected(memory_id)")
        .contains("new LoadedProofGraph(")
        .contains("queryCount");
    assertThat(repository.split("queryCount\\+\\+", -1)).hasSize(5);
  }

  @Test
  void firstReleaseKeepsPostgresqlAuthorityAndHasNoNeo4jDependency()
      throws IOException {
    String parentPom = Files.readString(ROOT.resolve("pom.xml"));
    String serverPom = Files.readString(ROOT.resolve("mathproofmesh-server/pom.xml"));
    String corePom = Files.readString(ROOT.resolve("mathproofmesh-core/pom.xml"));

    assertThat(parentPom + serverPom + corePom)
        .doesNotContainIgnoringCase("neo4j")
        .contains("jgrapht-core")
        .contains("postgresql");
  }
}
