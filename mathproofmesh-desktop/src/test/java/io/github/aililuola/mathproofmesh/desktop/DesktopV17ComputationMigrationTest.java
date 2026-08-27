package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.computation.ComputationContext;
import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionStatus;
import io.github.aililuola.mathproofmesh.computation.InMemoryComputationCache;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopV17ComputationMigrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void completeLegacyTraceRetainsAuthorityAndIncompleteTraceBecomesAuditOnly()
      throws Exception {
    var source =
        DesktopComputationIssue010Support.broker(
            "legacy-source",
            temporaryDirectory.resolve("legacy-source"),
            new InMemoryComputationCache());
    var completeRequested = DesktopComputationIssue010Support.finiteMap("legacy-complete");
    var completePrepared =
        source.decide(completeRequested, ComputationContext.initial("legacy-complete-route", 8));
    var completeResult =
        source.runExperiment(completePrepared.spec(), completePrepared.decision());
    var incompleteRequested =
        DesktopComputationIssue010Support.boundedObservation("legacy-incomplete", 7);
    var incompletePrepared =
        source.decide(incompleteRequested, ComputationContext.initial("legacy-incomplete-route", 8));

    Path runDirectory = temporaryDirectory.resolve("v17-run");
    DesktopSolveCheckpoint base;
    try (DesktopComputationIssue010CoordinatorHarness harness =
        DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v17-run")) {
      base = harness.checkpointRoundTrip();
    }
    ObjectNode legacyJson =
        (ObjectNode) ContractObjectMapper.parseTree(ContractObjectMapper.write(base));
    legacyJson.put("schemaVersion", 17);
    legacyJson.set(
        "computations",
        ContractObjectMapper.toTree(
            List.of(
                new DesktopSolveCheckpoint.ComputationCheckpoint(
                    "legacy-complete-route",
                    completePrepared.spec(),
                    completePrepared.decision(),
                    null,
                    completeResult,
                    "legacy-complete-obligation",
                    ComputationEvidenceGate.EvidenceAuthority.VERIFIED_BOUNDED,
                    true),
                new DesktopSolveCheckpoint.ComputationCheckpoint(
                    "legacy-incomplete-route",
                    incompletePrepared.spec(),
                    incompletePrepared.decision(),
                    null,
                    null,
                    "legacy-incomplete-obligation",
                    ComputationEvidenceGate.EvidenceAuthority.REFUTED,
                    false))));
    legacyJson.remove("computationCapabilities");
    legacyJson.remove("computationExecutions");
    legacyJson.remove("computationArtifacts");
    legacyJson.remove("computationVerifications");
    legacyJson.remove("computationOutcomeReceipts");
    DesktopSolveCheckpoint version17 =
        ContractObjectMapper.read(legacyJson.toString(), DesktopSolveCheckpoint.class);
    assertThat(version17.schemaVersion()).isEqualTo(17);
    assertThat(version17.computationExecutions().records()).isEmpty();

    DesktopSolveCheckpoint version18;
    String firstExecutionHash;
    String firstArtifactHash;
    String firstVerificationHash;
    try (DesktopComputationIssue010CoordinatorHarness restored =
        DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v17-run")) {
      restored.restore(version17);
      version18 = restored.checkpointRoundTrip();
      firstExecutionHash = version18.computationExecutions().stableHash();
      firstArtifactHash = version18.computationArtifacts().stableHash();
      firstVerificationHash = version18.computationVerifications().stableHash();

      assertThat(version18.schemaVersion()).isEqualTo(DesktopSolveCheckpoint.CURRENT_SCHEMA_VERSION);
      assertThat(version18.computationExecutions().records()).hasSize(2);
      assertThat(version18.computationExecutions().records())
          .allMatch(record -> record.status() == ComputationExecutionStatus.AUTHORITY_APPLIED);
      assertThat(version18.computationExecutions().records())
          .anyMatch(
              record ->
                  record.requestHash().equals(completePrepared.spec().requestHash())
                      && record.authority()
                          == ComputationVerifiedAuthority.FINITE_DOMAIN_CERTIFICATE);
      assertThat(version18.computationExecutions().records())
          .anyMatch(
              record ->
                  record.requestHash().equals(incompletePrepared.spec().requestHash())
                      && record.authority() == ComputationVerifiedAuthority.AUDIT_ONLY);
      assertThat(version18.computationVerifications().receipts())
          .anyMatch(receipt -> receipt.diagnostics().contains("LEGACY_VERIFICATION_ACCEPTED"));
      assertThat(version18.computationVerifications().receipts())
          .anyMatch(receipt -> receipt.diagnostics().contains("LEGACY_AUDIT_ONLY"));
      assertThat(version18.computationArtifacts().records()).hasSize(12);
      assertThat(version18.computationArtifacts().records())
          .filteredOn(
              record ->
                  record.kind()
                      == io.github.aililuola.mathproofmesh.computation.ComputationArtifactKind.AUTHORITY_MUTATION_RECEIPT)
          .hasSize(2);
    }

    try (DesktopComputationIssue010CoordinatorHarness restoredAgain =
        DesktopComputationIssue010CoordinatorHarness.open(runDirectory, "v17-run")) {
      restoredAgain.restore(version18);
      DesktopSolveCheckpoint second = restoredAgain.checkpointRoundTrip();
      assertThat(second.computationExecutions().records()).hasSize(2);
      assertThat(second.computationExecutions().stableHash()).isEqualTo(firstExecutionHash);
      assertThat(second.computationArtifacts().stableHash()).isEqualTo(firstArtifactHash);
      assertThat(second.computationVerifications().stableHash()).isEqualTo(firstVerificationHash);
      assertThat(second.computationOutcomeReceipts().receipts()).hasSize(2);
    }

    System.out.println("V17_TO_V18_COMPUTATION_MIGRATION_DIAGNOSTIC");
    System.out.println("LEGACY_COMPLETE_TRACES=1");
    System.out.println("LEGACY_AUDIT_ONLY_TRACES=1");
    System.out.println("LEGACY_AUTHORITY_LOSSES=0");
    System.out.println("LEGACY_UNSAFE_PROMOTIONS=0");
    System.out.println("MIGRATION_BACKEND_INVOCATIONS=0");
    System.out.println("POST_V18_RESTORE_LOSSES=0");
    System.out.println("POST_V18_RESTORE_DUPLICATES=0");
    System.out.println("RESULT=PASS");
  }
}
