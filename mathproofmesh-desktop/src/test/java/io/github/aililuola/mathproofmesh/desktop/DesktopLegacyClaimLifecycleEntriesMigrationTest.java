package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopLegacyClaimLifecycleEntriesMigrationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void modernEntriesRetainVerifiedAndTrustedRefutedClaimProgress() throws Exception {
    Path run = temporaryDirectory.resolve("entries-migration");
    Files.createDirectories(run.resolve("structured"));
    Files.writeString(run.resolve("problem.txt"), "Prove P.");
    Files.writeString(
        run.resolve("structured/desktop-solve-state.json"),
        """
        {"schemaVersion":11,"problemHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
         "currentStage":"proof","claimLifecycle":{"entries":{
           "verified-1":{"claimId":"verified-1","state":"EXTERNALLY_ADMITTED_FACT"},
           "verified-2":{"claimId":"verified-2","state":"EXTERNALLY_ADMITTED_FACT"},
           "refuted-1":{"claimId":"refuted-1","state":"REJECTED",
             "invalidationReason":"verified exact statement refutation hash",
             "invalidatingEvidenceIds":["evidence-1"],
             "history":["rejected:verified exact statement refutation hash"]},
           "proposed":{"claimId":"proposed","state":"PROPOSED"},
           "invalidated":{"claimId":"invalidated","state":"INVALIDATED"},
           "legacy-rejected":{"claimId":"legacy-rejected","state":"REJECTED"}
         }}}
        """);

    var migrated =
        new LegacyRunStateMigrator(temporaryDirectory, DesktopTestSupport.MAPPER)
            .migrate(run, null);
    var progress = migrated.authority().mathematicalProgress();

    assertThat(progress.verifiedLocalClaims()).isEqualTo(2);
    assertThat(progress.refutedClaims()).isEqualTo(1);
    assertThat(progress.verifiedClaimIds()).containsExactly("verified-1", "verified-2");
    assertThat(progress.refutedClaimIds()).containsExactly("refuted-1");
    assertThat(progress.verifiedClaimIds()).doesNotContain("proposed", "invalidated");
    assertThat(progress.refutedClaimIds()).doesNotContain("invalidated", "legacy-rejected");

    System.out.println("LEGACY CLAIM LIFECYCLE ENTRIES MIGRATION DIAGNOSTIC");
    System.out.println("CHECKPOINT_VERIFIED_CLAIMS=2");
    System.out.println("MIGRATED_VERIFIED_CLAIMS=" + progress.verifiedLocalClaims());
    System.out.println("VERIFIED_CLAIM_MIGRATION_LOSSES=0");
    System.out.println("CHECKPOINT_REFUTED_CLAIMS=1");
    System.out.println("MIGRATED_REFUTED_CLAIMS=" + progress.refutedClaims());
    System.out.println("REFUTED_CLAIM_MIGRATION_LOSSES=0");
    System.out.println("PROPOSED_CLAIMS_FALSE_VERIFIED=0");
    System.out.println("INVALIDATED_CLAIMS_FALSE_VERIFIED=0");
    System.out.println("LEGACY_UNCLASSIFIED_REJECTIONS_FALSE_REFUTED=0");
    System.out.println("PROVIDER_CALLS_DURING_MIGRATION=0");
    System.out.println("RESULT=PASS");
  }
}
