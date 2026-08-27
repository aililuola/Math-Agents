package io.github.aililuola.mathproofmesh.desktop.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OlympiadProtocolDocumentLocatorTest {
  @TempDir Path temporaryDirectory;

  @Test
  void locatesTheSingleProtocolUsingOnlyStableAsciiFilenameMarkers() throws Exception {
    Path project = temporaryDirectory.resolve(".publish/Math-Agents");
    Files.createDirectories(project);
    Path protocol = temporaryDirectory.resolve("MathProofMesh_test_Benchmark_Codex_protocol.md");
    Files.writeString(protocol, "# protocol\n");

    assertEquals(protocol, OlympiadProtocolDocumentLocator.locate(project));
  }

  @Test
  void rejectsAnAmbiguousWorkspaceProtocolSelection() throws Exception {
    Path project = temporaryDirectory.resolve(".publish/Math-Agents");
    Files.createDirectories(project);
    Files.writeString(temporaryDirectory.resolve("MathProofMesh_a_Benchmark_Codex.md"), "a");
    Files.writeString(temporaryDirectory.resolve("MathProofMesh_b_Benchmark_Codex.md"), "b");

    assertThrows(
        IllegalStateException.class,
        () -> OlympiadProtocolDocumentLocator.locate(project));
  }
}
