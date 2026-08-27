package io.github.aililuola.mathproofmesh.api;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ServerParityTest {
  @TempDir Path temporaryDirectory;

  static Stream<String> authorityCases() {
    return Stream.of(
        "test_server_exposes_resume_routes_and_health");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ApiParityScenarios.verify("ServerParityTest", authorityFunction, temporaryDirectory);
  }
}
