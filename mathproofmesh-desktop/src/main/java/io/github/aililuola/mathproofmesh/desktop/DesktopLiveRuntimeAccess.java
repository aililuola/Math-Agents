package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;

/** Run-scoped live configuration and provider construction boundary. */
interface DesktopLiveRuntimeAccess {
  DesktopLiveRuntimeFactory.PreparedRuntime prepare(
      String requestedProfile, DesktopSettings settings);

  ProviderClientRegistry openProviders(DesktopLiveRuntimeFactory.PreparedRuntime runtime);
}
