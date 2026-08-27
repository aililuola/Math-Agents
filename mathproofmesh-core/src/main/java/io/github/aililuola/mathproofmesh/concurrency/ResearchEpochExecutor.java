package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;

public interface ResearchEpochExecutor {
  List<ResearchWorkResultEnvelope> execute(
      FrozenResearchSnapshot snapshot, List<ResearchWorkItem> workItems);
}
