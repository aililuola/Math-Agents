package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.config.AgentConfig;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Performs real authenticated model-list probes without exposing credential material. */
final class DesktopProviderProbe {
  private final SettingsStore settings;
  private final DesktopLiveRuntimeFactory runtimes;

  DesktopProviderProbe(SettingsStore settings, DesktopLiveRuntimeFactory runtimes) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.runtimes = Objects.requireNonNull(runtimes, "runtimes");
  }

  List<Map<String, Object>> probe() {
    DesktopLiveRuntimeFactory.PreparedRuntime runtime =
        runtimes.prepare(null, settings.load());
    List<Map<String, Object>> results = new ArrayList<>();
    try (ProviderClientRegistry providers = runtimes.openProviders(runtime)) {
      for (AgentConfig agent : runtime.config().agents()) {
        if (!agent.enabled()) {
          continue;
        }
        boolean credentialOk = false;
        boolean modelVisible = false;
        try {
          List<String> models = providers.get(agent.id()).listModels();
          credentialOk = true;
          modelVisible = models.contains(agent.model());
        } catch (RuntimeException ignored) {
          // The UI receives only booleans; provider diagnostics never expose secrets.
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("agent", agent.id());
        result.put("provider", agent.provider());
        result.put("model", agent.model());
        result.put("reasoning_effort", agent.reasoningEffort());
        result.put("credential_ok", credentialOk);
        result.put("model_visible", modelVisible);
        results.add(Collections.unmodifiableMap(result));
      }
    }
    return List.copyOf(results);
  }
}
