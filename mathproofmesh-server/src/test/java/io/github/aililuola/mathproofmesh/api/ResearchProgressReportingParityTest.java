package io.github.aililuola.mathproofmesh.api;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ResearchProgressReportingParityTest {
  @TempDir Path temporaryDirectory;

  static Stream<String> authorityCases() {
    return Stream.of(
        "test_unverified_report_surfaces_verified_local_claims",
        "test_progress_summary_distinguishes_verified_claims_from_passed_routes");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ApiParityScenarios.verify("ResearchProgressReportingParityTest", authorityFunction, temporaryDirectory);
  }
}
