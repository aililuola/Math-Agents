package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import java.nio.file.Path;
import java.util.Objects;

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

  static DesktopDurableBoundaryObserver from(RunExecutionBackend.ProgressSink progress) {
    Objects.requireNonNull(progress, "progress");
    return progress instanceof DesktopDurableBoundaryObserver observer ? observer : none();
  }
}
