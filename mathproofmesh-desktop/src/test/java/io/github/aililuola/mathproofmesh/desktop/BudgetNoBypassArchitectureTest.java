package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class BudgetNoBypassArchitectureTest {
  @Test
  void actionAdmissionAndPhysicalReservationPrecedeReadyQueueAndProviderDispatch()
      throws Exception {
    Path root = projectRoot();
    String coordinator = read(root,
        "mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/DesktopSolveCoordinator.java");
    String budgetScheduler = read(root,
        "mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/DesktopBudgetScheduler.java");
    String runner = read(root,
        "mathproofmesh-server/src/main/java/io/github/aililuola/mathproofmesh/agent/StructuredAgentRunner.java");

    String initial = method(coordinator, "case CURSOR_EXPLORE -> {", "case CURSOR_INTEGRATE -> {");
    assertBefore(initial, "reserveInitialExplorationBudget()", "exploreUnstartedRoutes(true)");
    String action = method(
        budgetScheduler,
        "boolean apply(EvidenceAwareBudgetDecision decision)",
        "boolean reserveInitial(int pendingRoutes");
    assertBefore(action, "reserve(", "host.execute(action)");
    String batch = method(
        coordinator,
        "private boolean schedulePendingProofTasksBatch()",
        "private BudgetStateSnapshot state()");
    assertBefore(batch, "reserveProofTaskBatch(batch)", "schedulePendingProofTask()");
    String recovery =
        method(
            coordinator,
            "private boolean scheduleExhaustedPortfolioRecovery()",
            "private BudgetStateSnapshot state()");
    assertBefore(
        recovery, "reserveExhaustedPortfolioRecovery(state())", "widenRoutes()");
    String reservation = method(
        budgetScheduler,
        "private BudgetEnvelope reserve(",
        "private static boolean budgetAdmissionRefused(");
    assertBefore(reservation, "runtime.reserveAndActivate(", "host.persistReservation()");
    String provider =
        method(
            runner,
            "private <T> StructuredCallResult<T> callSingle(",
            "private <T> StructuredCallResult<T> replayExisting(");
    assertBefore(provider, "budget.reserveWithId(", "providerCall(agent, lease, request)");
    assertBefore(provider, "reservePhysicalBudget(", "providerCall(agent, lease, request)");
    assertThat(runner).contains("new StageTokenEnvelopeResolver()");
  }

  @Test
  void pureBudgetPackageCannotMutateProviderOrMathematicalAuthority() throws Exception {
    Path budgetPackage =
        projectRoot().resolve(
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/orchestration");
    try (Stream<Path> files = Files.list(budgetPackage)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file);
        assertThat(source)
            .as(file.getFileName().toString())
            .doesNotContain("mathproofmesh.provider")
            .doesNotContain("mathproofmesh.proofgraph")
            .doesNotContain("mathproofmesh.memory")
            .doesNotContain("UUID.randomUUID")
            .doesNotContain("System.currentTimeMillis")
            .doesNotContain("Instant.now");
      }
    }
  }

  private static void assertBefore(String source, String first, String second) {
    assertThat(source.indexOf(first)).isGreaterThanOrEqualTo(0);
    assertThat(source.indexOf(second)).isGreaterThan(source.indexOf(first));
  }

  private static String method(String source, String start, String end) {
    int from = source.indexOf(start);
    int to = source.indexOf(end, from + start.length());
    assertThat(from).isGreaterThanOrEqualTo(0);
    assertThat(to).isGreaterThan(from);
    return source.substring(from, to);
  }

  private static String read(Path root, String relative) throws Exception {
    return Files.readString(root.resolve(relative));
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("config/proof-control-active.yaml"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("project root was not found");
  }
}
