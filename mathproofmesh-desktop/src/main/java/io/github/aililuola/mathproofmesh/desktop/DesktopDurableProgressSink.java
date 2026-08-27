package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import java.nio.file.Path;
import java.util.Objects;

/** Carries validation-only durable-boundary observation alongside normal progress events. */
final class DesktopDurableProgressSink
    implements RunExecutionBackend.ProgressSink, DesktopDurableBoundaryObserver {
  private final RunExecutionBackend.ProgressSink progress;
  private final DesktopDurableBoundaryObserver observer;

  DesktopDurableProgressSink(
      RunExecutionBackend.ProgressSink progress, DesktopDurableBoundaryObserver observer) {
    this.progress = Objects.requireNonNull(progress, "progress");
    this.observer = Objects.requireNonNull(observer, "observer");
  }

  @Override
  public void emit(
      String type,
      String stage,
      String agentId,
      String status,
      String summary,
      String reference) {
    progress.emit(type, stage, agentId, status, summary, reference);
  }

  @Override
  public void afterDurableBoundary(DesktopDurableBoundary boundary, Path checkpoint) {
    observer.afterDurableBoundary(boundary, checkpoint);
  }

  @Override
  public int maximumResearchInFlight(int configuredMaximum) {
    return observer.maximumResearchInFlight(configuredMaximum);
  }
}
