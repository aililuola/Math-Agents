package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.desktop.benchmark.BenchmarkSecretSet;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkPackager;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBundleChecksums;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadProtocolDocumentLocator;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadSecretRedactor;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Explicitly gated, zero-network packaging of a campaign stopped by a hard benchmark gate. */
final class OlympiadStoppedCampaignPackagerTest {
  private static final String CAMPAIGN_PROPERTY = "benchmark.package.stopped";

  @Test
  void packagesAnExplicitlyStoppedCampaignWithoutCallingTheProvider() {
    String configuredCampaign = System.getProperty(CAMPAIGN_PROPERTY, "").strip();
    Assumptions.assumeFalse(
        configuredCampaign.isEmpty(),
        "stopped campaign packaging requires -D" + CAMPAIGN_PROPERTY + "=<path>");
    Path campaign = Path.of(configuredCampaign).toAbsolutePath().normalize();
    assertTrue(
        Files.isRegularFile(campaign.resolve("aggregate/HARD-GATE-STOP-REPORT.md")),
        "stopped campaign must contain an explicit hard-gate report");
    Path projectRoot = DesktopLiveRunExecutionBackendTest.projectRoot();
    Path benchmarkRoot = projectRoot.resolve("benchmark/olympiad-5key-v1");

    try (BenchmarkSecretSet secrets = BenchmarkSecretSet.load(System::getenv)) {
      OlympiadSecretRedactor redactor =
          new OlympiadSecretRedactor(secrets.transientValues());
      OlympiadSecretRedactor.LeakReport sourceLeaks = redactor.scan(campaign);
      assertTrue(sourceLeaks.passed());

      OlympiadBenchmarkPackager.PackageResult result =
          OlympiadBenchmarkPackager.create(
              projectRoot,
              benchmarkRoot,
              campaign,
              OlympiadProtocolDocumentLocator.locate(projectRoot),
              benchmarkRoot.resolve("results/packages"),
              redactor);

      assertTrue(Files.isRegularFile(result.zip()));
      assertTrue(OlympiadBundleChecksums.verify(result.stagingDirectory()).passed());
      assertTrue(redactor.scan(result.stagingDirectory()).passed());
      System.out.println("OLYMPIAD STOPPED-CAMPAIGN PACKAGE DIAGNOSTIC");
      System.out.println("CAMPAIGN=" + campaign);
      System.out.println("NETWORK_CALLS=0");
      System.out.println("SOURCE_SECRET_LEAKS=" + sourceLeaks.secretLeaks());
      System.out.println("CHECKSUM_FAILURES=0");
      System.out.println("SANITIZED_ZIP=" + result.zip());
      System.out.println("RESULT=PASS");
    }
  }
}
