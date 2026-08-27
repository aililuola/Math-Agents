package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunApiRestartRecoveryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void cacheMissRestoresStatusFromCanonicalFileAuthority() {
    RunApiService first = RunStateApiProjectionTest.service(temporaryDirectory);
    var before = first.solve(new SolveRequest("Prove P.", "restart-recovery", null, "smoke"));
    RunApiService after = RunStateApiProjectionTest.service(temporaryDirectory);
    assertThat(after.status("restart-recovery").authorityStateHash())
        .isEqualTo(before.authorityStateHash());
  }
}
