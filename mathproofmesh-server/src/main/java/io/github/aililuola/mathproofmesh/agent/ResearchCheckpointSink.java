package io.github.aililuola.mathproofmesh.agent;

@FunctionalInterface
public interface ResearchCheckpointSink {
  void commit(ResearchCheckpointCapture capture);

  static ResearchCheckpointSink noOp() {
    return ignored -> {};
  }
}
