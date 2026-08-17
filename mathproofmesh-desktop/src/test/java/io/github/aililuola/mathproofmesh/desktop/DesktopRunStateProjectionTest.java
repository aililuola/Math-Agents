package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.aililuola.mathproofmesh.runstate.DesktopMetadataProjectionService;
import io.github.aililuola.mathproofmesh.runstate.RunResultProjectionService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRunStateProjectionTest {
  @TempDir Path temporaryDirectory;

  @Test
  void resultAndMetadataCarryTheSameAuthorityHash() throws Exception {
    var state = DesktopRunStateTestSupport.failure("projection-state", null, 9);
    new RunResultProjectionService().project(temporaryDirectory, state, java.util.Map.of());
    new DesktopMetadataProjectionService().project(temporaryDirectory, state, java.util.Map.of());
    var mapper = JsonMapper.builder().build();
    String resultHash =
        mapper.readTree(temporaryDirectory.resolve("structured/run_result.json").toFile())
            .path("authority_state_hash").asText();
    String metadataHash =
        mapper.readTree(temporaryDirectory.resolve("desktop_run.json").toFile())
            .path("authority_state_hash").asText();
    assertThat(resultHash).isEqualTo(state.authority().authorityHash()).isEqualTo(metadataHash);
  }
}
