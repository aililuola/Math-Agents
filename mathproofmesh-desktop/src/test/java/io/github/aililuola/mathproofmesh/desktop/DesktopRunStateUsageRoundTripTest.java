package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.aililuola.mathproofmesh.runstate.FileRunStateStore;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRunStateUsageRoundTripTest {
  @TempDir Path temporaryDirectory;

  @Test
  void originalFailureUsageSurvivesJsonAndStoreRoundTrip() throws Exception {
    var state = DesktopRunStateTestSupport.failure("usage-round-trip", null, 215);
    var mapper = JsonMapper.builder().findAndAddModules().build();
    var jsonRoundTrip = mapper.readValue(mapper.writeValueAsBytes(state), state.getClass());
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    store.compareAndSet("usage-round-trip", -1, jsonRoundTrip, "test", 0);
    var restored = store.load("usage-round-trip").orElseThrow();
    assertThat(restored.authority().usage().providerCalls()).isEqualTo(215);
    assertThat(restored.authority().usage().totalTokens()).isEqualTo(6_450);
  }
}
