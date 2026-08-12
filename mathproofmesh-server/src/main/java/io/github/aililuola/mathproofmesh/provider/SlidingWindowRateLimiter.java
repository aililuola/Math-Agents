package io.github.aililuola.mathproofmesh.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

public final class SlidingWindowRateLimiter {
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final Integer requestsPerMinute;
  private final Clock clock;
  private final Sleeper sleeper;
  private final Deque<Instant> admissions = new ArrayDeque<>();
  private final ReentrantLock lock = new ReentrantLock(true);

  public SlidingWindowRateLimiter(Integer requestsPerMinute) {
    this(requestsPerMinute, Clock.systemUTC(), Thread::sleep);
  }

  public SlidingWindowRateLimiter(
      Integer requestsPerMinute, Clock clock, Sleeper sleeper) {
    if (requestsPerMinute != null && requestsPerMinute < 1) {
      throw new IllegalArgumentException("requestsPerMinute must be positive");
    }
    this.requestsPerMinute = requestsPerMinute;
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
  }

  public void acquire() throws InterruptedException {
    if (requestsPerMinute == null) {
      return;
    }
    while (true) {
      Duration wait;
      lock.lockInterruptibly();
      try {
        Instant now = clock.instant();
        prune(now);
        if (admissions.size() < requestsPerMinute) {
          admissions.addLast(now);
          return;
        }
        Instant available = admissions.getFirst().plus(WINDOW);
        wait = Duration.between(now, available);
      } finally {
        lock.unlock();
      }
      sleeper.sleep(wait.isNegative() ? Duration.ZERO : wait);
    }
  }

  public boolean tryAcquire(Instant now) {
    Objects.requireNonNull(now, "now");
    if (requestsPerMinute == null) {
      return true;
    }
    lock.lock();
    try {
      prune(now);
      if (admissions.size() >= requestsPerMinute) {
        return false;
      }
      admissions.addLast(now);
      return true;
    } finally {
      lock.unlock();
    }
  }

  public int inWindow() {
    lock.lock();
    try {
      prune(clock.instant());
      return admissions.size();
    } finally {
      lock.unlock();
    }
  }

  private void prune(Instant now) {
    Instant cutoff = now.minus(WINDOW);
    while (!admissions.isEmpty() && !admissions.getFirst().isAfter(cutoff)) {
      admissions.removeFirst();
    }
  }

  @FunctionalInterface
  public interface Sleeper {
    void sleep(Duration duration) throws InterruptedException;
  }
}
