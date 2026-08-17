package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RunStateProtectedAuthorityTest {
  private static final String BASELINE = "0e245ed65a28f174b0b447840522e2505827f439";

  @Test
  void issue011DoesNotModifyIssues001Through010AuthorityFiles() throws Exception {
    Path root = projectRoot();
    List<String> protectedFiles =
        List.of(
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/ExactGoalContractChecker.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/RootGoalContract.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/memory/NegativeKnowledgeRegistry.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/memory/NegativeKnowledgeAdmissionGate.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/AttemptArtifactLedger.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/ClaimLifecycleController.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/claimcourt/ClaimCourtLedger.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/claimcourt/ClaimProofRevisionLedger.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/research/ResearchCheckpointLedger.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofgraph/ObligationCanonicalizationRegistry.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofgraph/ProofGraphConvergenceMonitor.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/SemanticPivotCompiler.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/proofcontrol/SemanticPivotLedger.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/strategydiversity/StrategyMechanismAnalyzer.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/strategydiversity/StrategyPortfolioOptimizer.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/communication/artifact/BrokerArtifactCompiler.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/communication/artifact/BrokerArtifactEffectVerifier.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/communication/artifact/MathematicalArtifactBroker.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/computation/ComputationCapabilityRegistry.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/computation/IndependentComputationCertificateVerifier.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/computation/ComputationExecutionLedger.java",
            "mathproofmesh-core/src/main/java/io/github/aililuola/mathproofmesh/computation/ComputationTargetBinding.java");
    ProcessBuilder builder = new ProcessBuilder();
    java.util.ArrayList<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.add("diff");
    command.add("--exit-code");
    command.add(BASELINE);
    command.add("--");
    command.addAll(protectedFiles);
    Process process = builder.command(command).directory(root.toFile()).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).as(output + error).isZero();
  }

  private static Path projectRoot() {
    Path cursor = Path.of("").toAbsolutePath();
    while (cursor != null && !java.nio.file.Files.isRegularFile(cursor.resolve("pom.xml"))) {
      cursor = cursor.getParent();
    }
    return java.util.Objects.requireNonNull(cursor, "project root");
  }
}
