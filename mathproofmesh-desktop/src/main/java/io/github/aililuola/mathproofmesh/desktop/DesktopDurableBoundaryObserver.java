package io.github.aililuola.mathproofmesh.desktop;

import java.nio.file.Path;

/** Read-only validation hook invoked only after the named state is durably checkpointed. */
@FunctionalInterface
interface DesktopDurableBoundaryObserver {
  void afterDurableBoundary(DesktopDurableBoundary boundary, Path checkpoint);

  default int maximumResearchInFlight(int configuredMaximum) {
    return configuredMaximum;
  }

  static DesktopDurableBoundaryObserver none() {
    return (boundary, checkpoint) -> {};
  }
}
