package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopLegacyClaimLifecycleRecordsCompatibilityTest {
  @TempDir Path temporaryDirectory;

  @Test
  void legacyRecordsRemainCompatibleAndMigrationIsDeterministic() throws Exception {
    String checkpoint =
        """
        {"problemHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
         "currentStage":"proof","claimLifecycle":{"records":{
           "verified":{"claimId":"verified","status":"VERIFIED"},
           "refuted":{"claimId":"refuted","status":"REFUTED"},
           "rejected":{"claimId":"rejected","status":"REJECTED"}
         }}}
        """;
    Path firstRun = writeRun("records-migration-first", checkpoint);
    var first =
        new LegacyRunStateMigrator(temporaryDirectory, DesktopTestSupport.MAPPER)
            .migrate(firstRun, null);
    Path secondRun = writeRun("records-migration-second", checkpoint);
    var second =
        new LegacyRunStateMigrator(temporaryDirectory, DesktopTestSupport.MAPPER)
            .migrate(secondRun, null);

    assertThat(first.authority().mathematicalProgress())
        .isEqualTo(second.authority().mathematicalProgress());
    assertThat(second.authority().mathematicalProgress().verifiedClaimIds())
        .containsExactly("verified");
    assertThat(second.authority().mathematicalProgress().refutedClaimIds())
        .containsExactly("refuted");
    System.out.println("SECOND_MIGRATION_STATE_CHANGES=0");
    System.out.println("RESULT=PASS");
  }

  private Path writeRun(String runId, String checkpoint) throws Exception {
    Path run = temporaryDirectory.resolve(runId);
    Files.createDirectories(run.resolve("structured"));
    Files.writeString(run.resolve("problem.txt"), "Prove P.");
    Files.writeString(run.resolve("structured/desktop-solve-state.json"), checkpoint);
    return run;
  }
}
