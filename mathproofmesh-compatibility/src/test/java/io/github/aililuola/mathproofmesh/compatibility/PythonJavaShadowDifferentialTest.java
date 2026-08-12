package io.github.aililuola.mathproofmesh.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PythonJavaShadowDifferentialTest {
  @Test
  void pythonAndJavaMockSnapshotsMatchAcrossEveryRequiredSection() throws Exception {
    String pythonSnapshot = runPythonOracle();
    String javaSnapshot =
        ContractObjectMapper.write(ShadowComparatorTest.completeSnapshot());

    ShadowComparator.ShadowComparisonReport report =
        new ShadowComparator().compare(pythonSnapshot, javaSnapshot, Set.of());

    assertTrue(report.passed());
    assertEquals(ShadowComparator.REQUIRED_SECTIONS, report.sectionsCompared());
    assertTrue(report.criticalDifferences().isEmpty());
    assertEquals(report.pythonSnapshotHash(), report.javaSnapshotHash());
  }

  private static String runPythonOracle() throws IOException, InterruptedException {
    Path root = projectRoot();
    String executable =
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")
            ? "python"
            : "python3";
    ProcessBuilder builder =
        new ProcessBuilder(
            executable,
            root.resolve("scripts/phase16-python-shadow-oracle.py").toString());
    builder.directory(root.toFile());
    builder.redirectErrorStream(true);
    builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
    builder.environment().put("PYTHONUTF8", "1");
    Process process = builder.start();
    boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS);
    if (!exited) {
      process.destroyForcibly();
      throw new IOException("phase-16 Python shadow oracle timed out");
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    if (process.exitValue() != 0) {
      throw new IOException("phase-16 Python shadow oracle failed: " + output);
    }
    return output;
  }

  private static Path projectRoot() throws IOException {
    Path shortRoot = Path.of("P:\\");
    if (Files.isRegularFile(shortRoot.resolve("MIGRATION_PLAN.md"))) {
      return shortRoot;
    }
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null && !Files.isRegularFile(current.resolve("MIGRATION_PLAN.md"))) {
      current = current.getParent();
    }
    if (current == null) {
      throw new IOException("could not locate the migration project root");
    }
    return current;
  }
}
