package io.github.aililuola.mathproofmesh.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LegacyRunImporterTest {
  @Test
  void importIsReadOnlyAndPreservesManifestProvenance() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.7.0", "in_progress")) {
      Map<String, String> before = fixture.hashes();

      LegacyImportResult result = new LegacyRunImporter().importRun(fixture.root());

      assertEquals(before, fixture.hashes());
      assertEquals(fixture.root().toRealPath().toString(), result.sourceRoot());
      assertEquals(6, result.files().size());
      assertEquals("0.7", result.legacyVersion());
      assertEquals("0.8.2", result.targetVersion());
      assertTrue(result.importId().endsWith(result.sourceManifestHash()));
    }
  }

  @Test
  void repeatedImportUsesTheManifestHashAsAnIdempotencyKey() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "completed")) {
      LegacyRunImporter importer = new LegacyRunImporter();
      LegacyImportResult first = importer.importRun(fixture.root());
      LegacyImportResult second = importer.importRun(fixture.root());

      assertSame(first, second);
      assertEquals(1, importer.registeredImports());
      assertEquals(first.targetRunId(), second.targetRunId());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"0.7", "0.8.0", "0.8.1", "0.8.2"})
  void supportedVersionChainAlwaysProducesCanonicalV082(String version) throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create(version, "completed")) {
      LegacyImportResult result = new LegacyRunImporter().importRun(fixture.root());

      assertEquals("0.8.2", result.targetVersion());
      assertTrue(result.migratedDocuments().get("run.json").contains("\"version\":\"0.8.2\""));
      assertFalse(result.migrationSteps().isEmpty());
    }
  }

  @Test
  void unsupportedVersionFailsClosed() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.9.0", "completed")) {
      assertThrows(LegacyImportException.class, () -> new LegacyRunImporter().importRun(fixture.root()));
    }
  }

  @Test
  void corruptProblemBytesFailTheIntegrityGate() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "completed")) {
      fixture.writeText("problem.txt", "tampered\n");

      LegacyImportException error =
          assertThrows(
              LegacyImportException.class,
              () -> new LegacyRunImporter().importRun(fixture.root()));
      assertTrue(error.getMessage().contains("problem hash"));
    }
  }

  @Test
  void corruptArtifactBytesFailTheIntegrityGate() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "completed")) {
      fixture.writeText("artifacts/proof.txt", "tampered\n");

      LegacyImportException error =
          assertThrows(
              LegacyImportException.class,
              () -> new LegacyRunImporter().importRun(fixture.root()));
      assertTrue(error.getMessage().contains("artifact hash"));
    }
  }

  @Test
  void missingCheckpointParentFailsClosed() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "completed")) {
      fixture.updateJson(
          "checkpoints/checkpoint-1.json",
          checkpoint -> checkpoint.put("parent_checkpoint_id", "missing"));

      LegacyImportException error =
          assertThrows(
              LegacyImportException.class,
              () -> new LegacyRunImporter().importRun(fixture.root()));
      assertTrue(error.getMessage().contains("parent"));
    }
  }

  @Test
  void checkpointCycleFailsClosed() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "completed")) {
      fixture.updateJson(
          "checkpoints/checkpoint-0.json",
          checkpoint -> checkpoint.put("parent_checkpoint_id", "checkpoint-1"));

      LegacyImportException error =
          assertThrows(
              LegacyImportException.class,
              () -> new LegacyRunImporter().importRun(fixture.root()));
      assertTrue(error.getMessage().contains("cycle"));
    }
  }

  @Test
  void latestPointerMustResolveToACommittedCheckpoint() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "completed")) {
      fixture.updateJson(
          "checkpoints/checkpoint-1.json", checkpoint -> checkpoint.put("status", "working"));

      LegacyImportException error =
          assertThrows(
              LegacyImportException.class,
              () -> new LegacyRunImporter().importRun(fixture.root()));
      assertTrue(error.getMessage().contains("not committed"));
    }
  }

  @Test
  void externalPathReferenceIsRejectedBeforeUse() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "completed")) {
      fixture.updateJson("run.json", run -> run.put("problem_path", "../problem.txt"));

      LegacyImportException error =
          assertThrows(
              LegacyImportException.class,
              () -> new LegacyRunImporter().importRun(fixture.root()));
      assertTrue(error.getMessage().contains("escapes"));
    }
  }

  @Test
  void linkedArtifactIsRejectedWhenThePlatformCanCreateIt() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "completed")) {
      Path link = fixture.root().resolve("linked.txt");
      try {
        Files.createSymbolicLink(link, fixture.root().resolve("problem.txt"));
      } catch (IOException | UnsupportedOperationException exception) {
        Assumptions.assumeTrue(false, "symbolic links are unavailable for this account");
      }

      LegacyImportException error =
          assertThrows(
              LegacyImportException.class,
              () -> new LegacyRunImporter().importRun(fixture.root()));
      assertTrue(error.getMessage().contains("linked"));
    }
  }

  @Test
  void unauditedLegacyFactIsQuarantinedRatherThanPromoted() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.7", "completed")) {
      LegacyImportResult result = new LegacyRunImporter().importRun(fixture.root());

      assertEquals(1, result.quarantinedClaims().size());
      assertEquals("legacy-claim", result.quarantinedClaims().getFirst().claimId());
      assertTrue(result.migratedDocuments().get("run.json").contains("\"status\":\"quarantined\""));
    }
  }

  @Test
  void auditedLegacyFactRetainsItsVerifiedStatus() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "completed")) {
      fixture.updateJson(
          "run.json",
          run -> {
            run.path("claims").get(0).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) run.path("claims").get(0))
                .put("verification_report_id", "review-1");
          });

      LegacyImportResult result = new LegacyRunImporter().importRun(fixture.root());

      assertTrue(result.quarantinedClaims().isEmpty());
      assertTrue(result.migratedDocuments().get("run.json").contains("\"status\":\"FACT\""));
    }
  }

  @Test
  void legacyReceiptBypassCannotReactivateAuthority() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.0", "completed")) {
      LegacyImportResult result = new LegacyRunImporter().importRun(fixture.root());

      String migrated = result.migratedDocuments().get("run.json");
      assertTrue(migrated.contains("\"bypass_reactivated\":false"));
      assertTrue(migrated.contains("\"migration_reason\""));
    }
  }

  @Test
  void dependencyReferencesReceiveAnExplicitNamespace() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.7", "completed")) {
      String migrated = new LegacyRunImporter().importRun(fixture.root()).migratedDocuments().get("run.json");

      assertTrue(migrated.contains("\"kind\":\"external_claim\""));
      assertTrue(migrated.contains("\"namespace\":\"legacy\""));
      assertTrue(migrated.contains("\"legacy_kind\":\"legacy_external\""));
    }
  }

  @Test
  void terminalRunResumePlansExactlyZeroProviderCalls() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "completed")) {
      LegacyResumeDecision decision = new LegacyRunImporter().importRun(fixture.root()).resumeDecision();

      assertEquals(LegacyResumeDecision.Action.RETURN_TERMINAL, decision.action());
      assertEquals(0, decision.providerCallsBeforeResume());
    }
  }

  @Test
  void nonTerminalRunResumesOnlyFromCommittedCheckpoint() throws IOException {
    try (LegacyRunTestFixture fixture = LegacyRunTestFixture.create("0.8.2", "in_progress")) {
      LegacyResumeDecision decision = new LegacyRunImporter().importRun(fixture.root()).resumeDecision();

      assertEquals(
          LegacyResumeDecision.Action.RESUME_COMMITTED_CHECKPOINT, decision.action());
      assertEquals("checkpoint-1", decision.checkpointId());
      assertEquals(0, decision.providerCallsBeforeResume());
    }
  }
}
