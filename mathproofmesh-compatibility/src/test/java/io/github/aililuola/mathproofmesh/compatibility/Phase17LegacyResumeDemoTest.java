package io.github.aililuola.mathproofmesh.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Phase17LegacyResumeDemoTest {
  @Test
  void completedAndInProgressOldRunsDemonstrateSafeResume() throws Exception {
    String terminalManifest;
    String activeManifest;
    try (LegacyRunTestFixture terminal =
            LegacyRunTestFixture.create("0.8.0", "completed");
        LegacyRunTestFixture active =
            LegacyRunTestFixture.create("0.7", "in_progress")) {
      Map<String, String> terminalBefore = terminal.hashes();
      Map<String, String> activeBefore = active.hashes();

      LegacyImportResult terminalResult =
          new LegacyRunImporter().importRun(terminal.root());
      LegacyImportResult activeResult =
          new LegacyRunImporter().importRun(active.root());

      assertEquals(terminalBefore, terminal.hashes());
      assertEquals(activeBefore, active.hashes());
      assertEquals(
          LegacyResumeDecision.Action.RETURN_TERMINAL,
          terminalResult.resumeDecision().action());
      assertEquals(
          LegacyResumeDecision.Action.RESUME_COMMITTED_CHECKPOINT,
          activeResult.resumeDecision().action());
      assertEquals(
          "checkpoint-1", activeResult.resumeDecision().checkpointId());
      assertEquals(
          0, terminalResult.resumeDecision().providerCallsBeforeResume());
      assertEquals(
          0, activeResult.resumeDecision().providerCallsBeforeResume());
      assertEquals(1, activeResult.quarantinedClaims().size());
      terminalManifest = terminalResult.sourceManifestHash();
      activeManifest = activeResult.sourceManifestHash();
    }

    Path report =
        Path.of(System.getProperty("mathproofmesh.projectRoot"))
            .resolve("target/benchmark-reports/phase17-legacy-resume-demo.json");
    Files.createDirectories(report.getParent());
    Files.writeString(
        report,
        """
        {
          "scenario":"old-run-import-and-resume",
          "terminal_action":"RETURN_TERMINAL",
          "terminal_provider_calls_before_resume":0,
          "active_action":"RESUME_COMMITTED_CHECKPOINT",
          "active_checkpoint_id":"checkpoint-1",
          "active_provider_calls_before_resume":0,
          "legacy_only_claims_quarantined":1,
          "source_writes":0,
          "terminal_manifest_sha256":"%s",
          "active_manifest_sha256":"%s",
          "result":"PASS"
        }
        """
            .formatted(terminalManifest, activeManifest),
        StandardCharsets.UTF_8);
  }
}
