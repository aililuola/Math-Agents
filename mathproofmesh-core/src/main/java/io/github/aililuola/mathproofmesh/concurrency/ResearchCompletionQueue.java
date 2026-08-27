package io.github.aililuola.mathproofmesh.concurrency;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ResearchCompletionQueue {
  private final ArrayDeque<ResearchWorkResultEnvelope> completed = new ArrayDeque<>();

  public synchronized void offer(ResearchWorkResultEnvelope result) {
    completed.addLast(result);
    notifyAll();
  }

  public synchronized Optional<ResearchWorkResultEnvelope> poll() {
    return Optional.ofNullable(completed.pollFirst());
  }

  public synchronized List<ResearchWorkResultEnvelope> drain() {
    List<ResearchWorkResultEnvelope> results = new ArrayList<>(completed);
    completed.clear();
    return List.copyOf(results);
  }

  public synchronized int size() {
    return completed.size();
  }
}
