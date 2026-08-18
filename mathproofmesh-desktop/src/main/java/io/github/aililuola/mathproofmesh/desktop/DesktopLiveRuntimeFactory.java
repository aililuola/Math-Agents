package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.provider.JdkHttpTransport;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import java.util.Map;
import java.util.Objects;

/** Loads the immutable live profile and injects only DPAPI-backed credentials. */
final class DesktopLiveRuntimeFactory {
  private static final int REQUIRED_AGENT_COUNT = 5;

  private final DesktopConfigService configs;
  private final DesktopRuntimeLocator locator;

  DesktopLiveRuntimeFactory(DesktopConfigService configs, DesktopRuntimeLocator locator) {
    this.configs = Objects.requireNonNull(configs, "configs");
    this.locator = Objects.requireNonNull(locator, "locator");
  }

  PreparedRuntime prepare(String requestedProfile, DesktopSettings settings) {
    Objects.requireNonNull(settings, "settings");
    String profile =
        requestedProfile == null || requestedProfile.isBlank()
            ? settings.selectedProfile()
            : requestedProfile;
    DesktopConfigService.PreparedDesktopConfig prepared = configs.build(profile, settings);
    SystemConfig loaded = locator.loadProfile(prepared.profileFile());
    boolean sandboxEnabled =
        settings.sandboxEnabled() && loaded.computation().sandboxedPythonEnabled();
    SystemConfig effective =
        new SystemConfig(
            loaded.systemName(),
            loaded.agents(),
            loaded.budget(),
            loaded.scheduler(),
            loaded.topology(),
            loaded.verification(),
            loaded.continuation(),
            loaded.deepExplorationPolicy(),
            loaded.computation().withSandboxedPythonEnabled(sandboxEnabled),
            loaded.concurrency(),
            loaded.runtime());
    validateLiveProfile(effective, prepared.injectedCredentials());
    return new PreparedRuntime(
        prepared.profile(), effective, prepared.injectedCredentials(), sandboxEnabled);
  }

  ProviderClientRegistry openProviders(PreparedRuntime runtime) {
    Objects.requireNonNull(runtime, "runtime");
    return new ProviderClientRegistry(
        runtime.config(),
        Map.of(),
        ignored -> new JdkHttpTransport(),
        true,
        runtime.credentials()::get);
  }

  private static void validateLiveProfile(
      SystemConfig config, Map<String, String> credentials) {
    long enabled = config.agents().stream().filter(AgentConfig::enabled).count();
    if (enabled != REQUIRED_AGENT_COUNT) {
      throw new IllegalStateException("desktop live profile must enable exactly five agents");
    }
    if (config.concurrency().researchSlots() != 4
        || config.concurrency().coordinationSlots() != 1) {
      throw new IllegalStateException(
          "desktop live profile must reserve four research slots and one coordination slot");
    }
    for (AgentConfig agent : config.agents()) {
      if (!agent.enabled()) {
        continue;
      }
      if (!"deepseek".equals(agent.provider())
          || !"deepseek-v4-pro".equals(agent.model())
          || !agent.thinkingEnabled()
          || !"max".equals(agent.reasoningEffort())) {
        throw new IllegalStateException(
            "desktop live profile must use DeepSeek V4 Pro with max reasoning");
      }
      String credentialName = agent.apiKeyEnv();
      String credential = credentialName == null ? null : credentials.get(credentialName);
      if (credential == null || credential.isBlank()) {
        throw new IllegalStateException(
            "all five isolated DeepSeek credentials must be configured");
      }
    }
  }

  record PreparedRuntime(
      String profile,
      SystemConfig config,
      Map<String, String> credentials,
      boolean sandboxEnabled) {
    PreparedRuntime {
      profile = Objects.requireNonNull(profile, "profile");
      config = Objects.requireNonNull(config, "config");
      credentials = Map.copyOf(credentials);
    }

    @Override
    public Map<String, String> credentials() {
      return Map.copyOf(credentials);
    }
  }
}
