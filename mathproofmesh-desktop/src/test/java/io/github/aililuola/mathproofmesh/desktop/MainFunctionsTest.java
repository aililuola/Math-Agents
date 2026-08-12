package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class MainFunctionsTest {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void parsesProviderCheckWithoutLaunchingJavaFx() {
    MainFunctions.DesktopArguments parsed = MainFunctions.parse(new String[] {"--provider-check"});

    assertThat(parsed.providerCheck()).isTrue();
    assertThat(parsed.healthCheck()).isFalse();
    assertThat(parsed.windowSmokeTest()).isFalse();
    assertThat(parsed.version()).isFalse();
  }

  @Test
  void acceptsOnlyFiveHealthyPinnedProviderAssignments() throws Exception {
    String item =
        "{\"provider\":\"deepseek\",\"model\":\"deepseek-v4-pro\","
            + "\"reasoning_effort\":\"max\",\"credential_ok\":true,\"model_visible\":true}";
    DesktopLauncher.validateProviderCheck(
        MAPPER.readTree("{\"results\":[" + String.join(",", item, item, item, item, item) + "]}"));

    assertThatThrownBy(
            () ->
                DesktopLauncher.validateProviderCheck(
                    MAPPER.readTree(
                        "{\"results\":["
                            + String.join(",", item, item, item, item)
                            + "]}")))
        .isInstanceOf(IllegalStateException.class);
  }
}
