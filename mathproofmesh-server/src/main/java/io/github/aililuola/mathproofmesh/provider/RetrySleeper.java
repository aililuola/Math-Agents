package io.github.aililuola.mathproofmesh.provider;

import java.time.Duration;

@FunctionalInterface
public interface RetrySleeper {
  void sleep(Duration duration) throws InterruptedException;
}
