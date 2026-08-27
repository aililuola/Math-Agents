package io.github.aililuola.mathproofmesh.desktop;

import java.nio.file.Path;
import java.util.Arrays;

/** Desktop entry-point helpers kept independent of JavaFX initialization. */
public final class MainFunctions {
  private MainFunctions() {}

  public static DesktopArguments parse(String[] arguments) {
    boolean health = false;
    boolean providerCheck = false;
    boolean windowSmoke = false;
    boolean version = false;
    for (String argument : Arrays.asList(arguments)) {
      switch (argument) {
        case "--health-check" -> health = true;
        case "--provider-check" -> providerCheck = true;
        case "--window-smoke-test" -> windowSmoke = true;
        case "--version" -> version = true;
        default -> throw new IllegalArgumentException("unknown desktop argument");
      }
    }
    return new DesktopArguments(health, providerCheck, windowSmoke, version);
  }

  public static void configureLogging(Path logFile) {
    System.setProperty("logging.file.name", logFile.toAbsolutePath().normalize().toString());
    System.setProperty("logging.logback.rollingpolicy.max-file-size", "5MB");
    System.setProperty("logging.logback.rollingpolicy.max-history", "4");
  }

  public static String safeError(Throwable failure) {
    return failure == null
        ? "desktop startup failed"
        : String.valueOf(
            DesktopApiModel.redact(
                failure.getClass().getSimpleName() + ": " + failure.getMessage()));
  }

  public record DesktopArguments(
      boolean healthCheck, boolean providerCheck, boolean windowSmokeTest, boolean version) {}
}
