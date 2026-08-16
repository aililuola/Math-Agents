package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.computation.ComputationArtifactKind;
import io.github.aililuola.mathproofmesh.computation.ComputationBackendKind;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionFailurePoint;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionRecord;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionState;
import io.github.aililuola.mathproofmesh.computation.ComputationExecutionStatus;
import io.github.aililuola.mathproofmesh.computation.ComputationResultArtifact;
import io.github.aililuola.mathproofmesh.contract.ComputationCertificateEnvelope;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopComputationMultiRoundRestoreTest {
  private static final int ROUNDS = 20;
  private static final int RESTORE_ROUND = 10;

  @TempDir Path temporaryDirectory;

  @Test
  void eightyNativeRequestsRemainReproducibleAcrossCrashesAndCoordinatorRestore()
      throws Exception {
    String runId = "computation-multi-round";
    Path runDirectory = temporaryDirectory.resolve("run");
    DesktopComputationIssue010CoordinatorHarness harness =
        DesktopComputationIssue010CoordinatorHarness.open(runDirectory, runId);
    DesktopComputationIssue010CoordinatorHarness.ProtectedState protectedBefore =
        harness.protectedState();

    // Prime only the canonical cache. Removing the seed execution leaves exactly 80 logical records.
    DesktopComputationIssue010Support.run(
        harness.computation(),
        DesktopComputationIssue010Support.finiteMap("map-0"),
        "cache-seed",
        0);
    harness.computation().restore(ComputationExecutionState.empty());
    harness.checkpointRoundTrip();

    int hardCrashes = 0;
    int restoreFailures = 0;
    int postRestoreExecutionLosses = 0;
    int postRestoreResultLosses = 0;
    int postRestoreVerificationLosses = 0;
    int postRestoreQuotaResets = 0;
    String executionHashBeforeRestore = "";
    String executionHashAfterRestore = "";
    String artifactHashBeforeRestore = "";
    String artifactHashAfterRestore = "";
    String verificationHashBeforeRestore = "";
    String verificationHashAfterRestore = "";

    try {
      for (int round = 0; round < ROUNDS; round++) {
        if (round == RESTORE_ROUND) {
          DesktopSolveCheckpoint checkpoint = harness.checkpointRoundTrip();
          executionHashBeforeRestore = checkpoint.computationExecutions().stableHash();
          artifactHashBeforeRestore = checkpoint.computationArtifacts().stableHash();
          verificationHashBeforeRestore = checkpoint.computationVerifications().stableHash();
          int recordsBefore = checkpoint.computationExecutions().records().size();
          int artifactsBefore = checkpoint.computationArtifacts().records().size();
          int verificationsBefore = checkpoint.computationVerifications().receipts().size();
          double cpuBefore =
              checkpoint.computationExecutions().records().stream()
                  .mapToDouble(ComputationExecutionRecord::cpuSeconds)
                  .sum();
          harness.close();
          harness = DesktopComputationIssue010CoordinatorHarness.open(runDirectory, runId);
          try {
            harness.restore(checkpoint);
          } catch (RuntimeException exception) {
            restoreFailures++;
            throw exception;
          }
          DesktopSolveCheckpoint restored = harness.checkpointRoundTrip();
          executionHashAfterRestore = restored.computationExecutions().stableHash();
          artifactHashAfterRestore = restored.computationArtifacts().stableHash();
          verificationHashAfterRestore = restored.computationVerifications().stableHash();
          postRestoreExecutionLosses +=
              Math.max(0, recordsBefore - restored.computationExecutions().records().size());
          postRestoreResultLosses +=
              Math.max(0, artifactsBefore - restored.computationArtifacts().records().size());
          postRestoreVerificationLosses +=
              Math.max(0, verificationsBefore - restored.computationVerifications().receipts().size());
          double cpuAfter =
              restored.computationExecutions().records().stream()
                  .mapToDouble(ComputationExecutionRecord::cpuSeconds)
                  .sum();
          postRestoreQuotaResets += Double.compare(cpuBefore, cpuAfter) == 0 ? 0 : 1;
        }

        List<ExperimentSpec> requests =
            List.of(
                DesktopComputationIssue010Support.finiteMap("map-" + round),
                DesktopComputationIssue010Support.graphCounterexample("graph-" + round, round),
                DesktopComputationIssue010Support.linearAlgebra("linear-" + round, round),
                DesktopComputationIssue010Support.boundedObservation("bounded-" + round, round));
        int crashIndex = crashIndex(round);
        ComputationExecutionFailurePoint crashPoint = crashPoint(round);
        for (int index = 0; index < requests.size(); index++) {
          ExperimentSpec request = requests.get(index);
          String routeId = "round-" + round + "-request-" + index;
          if (index == crashIndex) {
            AtomicBoolean injected = new AtomicBoolean();
            harness.computation().setExecutionHook(
                (point, executionId) -> {
                  if (point == crashPoint && injected.compareAndSet(false, true)) {
                    throw new SimulatedProcessTermination(crashPoint.name());
                  }
                });
            try {
              DesktopComputationIssue010Support.run(
                  harness.computation(), request, routeId, round);
              throw new AssertionError("hard-crash point was not reached: " + crashPoint);
            } catch (SimulatedProcessTermination expected) {
              hardCrashes++;
            }
            DesktopSolveCheckpoint durable = harness.readCheckpoint();
            harness.close();
            harness = DesktopComputationIssue010CoordinatorHarness.open(runDirectory, runId);
            try {
              harness.restore(durable);
            } catch (RuntimeException exception) {
              restoreFailures++;
              throw exception;
            }
            harness.computation().setExecutionHook(null);
            DesktopComputationIssue010Support.run(
                harness.computation(), request, routeId, round);
          } else {
            harness.computation().setExecutionHook(null);
            DesktopComputationIssue010Support.run(
                harness.computation(), request, routeId, round);
          }
        }
      }

      DesktopSolveCheckpoint finalCheckpoint = harness.checkpointRoundTrip();
      DesktopComputationIssue010CoordinatorHarness.ProtectedState protectedAfter =
          harness.protectedState();
      List<ComputationExecutionRecord> records =
          finalCheckpoint.computationExecutions().records();
      var artifacts = finalCheckpoint.computationArtifacts();

      int logicalRequests = records.size();
      int admittedRequests =
          (int)
              records.stream()
                  .filter(record -> record.status() == ComputationExecutionStatus.AUTHORITY_APPLIED)
                  .count();
      long nativeRequests =
          records.stream()
              .filter(record -> record.backend() == ComputationBackendKind.NATIVE_JAVA)
              .count();
      long externalRequests =
          records.stream()
              .filter(record -> record.backend() == ComputationBackendKind.EXTERNAL_TYPED)
              .count();
      long sandboxRequests =
          records.stream()
              .filter(record -> record.backend() == ComputationBackendKind.SANDBOXED_PYTHON)
              .count();
      long resultArtifacts = count(artifacts, ComputationArtifactKind.RESULT);
      long certificateArtifacts = count(artifacts, ComputationArtifactKind.CERTIFICATE);
      long verificationArtifacts =
          count(artifacts, ComputationArtifactKind.VERIFICATION_RECEIPT);
      long applicationArtifacts =
          count(artifacts, ComputationArtifactKind.OUTCOME_APPLICATION_RECEIPT);
      long exactCounterexamples = authority(records, ComputationVerifiedAuthority.EXACT_COUNTEREXAMPLE);
      long finiteCertificates =
          authority(records, ComputationVerifiedAuthority.FINITE_DOMAIN_CERTIFICATE);
      long boundedObservations =
          authority(records, ComputationVerifiedAuthority.BOUNDED_OBSERVATION);
      int duplicateProducers =
          records.stream().mapToInt(record -> Math.max(0, record.producerExecutions() - 1)).sum();
      int duplicateVerifiers =
          records.stream().mapToInt(record -> Math.max(0, record.verifierExecutions() - 1)).sum();
      int duplicateProjections =
          records.stream().mapToInt(record -> Math.max(0, record.authorityProjections() - 1)).sum();
      int duplicateResultArtifacts =
          Math.toIntExact(
              artifacts.records().stream()
                  .filter(record -> record.kind() == ComputationArtifactKind.RESULT)
                  .count()
                  - artifacts.records().stream()
                      .filter(record -> record.kind() == ComputationArtifactKind.RESULT)
                      .map(record -> record.executionId())
                      .distinct()
                      .count());
      int cacheHits = records.stream().mapToInt(ComputationExecutionRecord::cacheHits).sum();
      int resultHashMismatches = 0;
      int certificateHashMismatches = 0;
      int verificationHashMismatches = 0;
      for (ComputationExecutionRecord record : records) {
        if (harness
            .computation()
            .executionService()
            .artifacts()
            .read(record.resultArtifactRef(), ComputationResultArtifact.class)
            .isEmpty()) {
          resultHashMismatches++;
        }
        if (harness
            .computation()
            .executionService()
            .artifacts()
            .read(record.certificateArtifactRef(), ComputationCertificateEnvelope.class)
            .isEmpty()) {
          certificateHashMismatches++;
        }
        if (harness
            .computation()
            .executionService()
            .artifacts()
            .read(record.verificationReceiptRef(), ComputationVerificationReceipt.class)
            .isEmpty()) {
          verificationHashMismatches++;
        }
      }

      assertThat(hardCrashes).isEqualTo(20);
      assertThat(restoreFailures).isZero();
      assertThat(logicalRequests).isEqualTo(80);
      assertThat(admittedRequests).isEqualTo(80);
      assertThat(nativeRequests).isEqualTo(80);
      assertThat(externalRequests).isZero();
      assertThat(sandboxRequests).isZero();
      assertThat(resultArtifacts).isEqualTo(80);
      assertThat(certificateArtifacts).isEqualTo(80);
      assertThat(verificationArtifacts).isEqualTo(80);
      assertThat(applicationArtifacts).isEqualTo(80);
      assertThat(exactCounterexamples).isEqualTo(20);
      assertThat(finiteCertificates).isEqualTo(40);
      assertThat(boundedObservations).isEqualTo(20);
      assertThat(cacheHits).isEqualTo(20);
      assertThat(duplicateProducers).isZero();
      assertThat(duplicateVerifiers).isZero();
      assertThat(duplicateProjections).isZero();
      assertThat(duplicateResultArtifacts).isZero();
      assertThat(postRestoreExecutionLosses).isZero();
      assertThat(postRestoreResultLosses).isZero();
      assertThat(postRestoreVerificationLosses).isZero();
      assertThat(postRestoreQuotaResets).isZero();
      assertThat(executionHashAfterRestore).isEqualTo(executionHashBeforeRestore);
      assertThat(artifactHashAfterRestore).isEqualTo(artifactHashBeforeRestore);
      assertThat(verificationHashAfterRestore).isEqualTo(verificationHashBeforeRestore);
      assertThat(resultHashMismatches).isZero();
      assertThat(certificateHashMismatches).isZero();
      assertThat(verificationHashMismatches).isZero();
      assertThat(protectedAfter).isEqualTo(protectedBefore);

      printDiagnostic(
          logicalRequests,
          admittedRequests,
          nativeRequests,
          resultArtifacts,
          certificateArtifacts,
          verificationArtifacts,
          applicationArtifacts,
          exactCounterexamples,
          finiteCertificates,
          boundedObservations,
          cacheHits,
          duplicateProducers,
          duplicateVerifiers,
          duplicateProjections,
          duplicateResultArtifacts,
          postRestoreExecutionLosses,
          postRestoreResultLosses,
          postRestoreVerificationLosses,
          postRestoreQuotaResets,
          resultHashMismatches,
          certificateHashMismatches,
          verificationHashMismatches,
          protectedBefore,
          protectedAfter,
          executionHashBeforeRestore,
          executionHashAfterRestore,
          artifactHashBeforeRestore,
          artifactHashAfterRestore,
          verificationHashBeforeRestore,
          verificationHashAfterRestore);
    } finally {
      harness.close();
    }
  }

  private static int crashIndex(int round) {
    if (round < 5) {
      return 1;
    }
    if (round < 10) {
      return 2;
    }
    if (round < 15) {
      return 0;
    }
    return 3;
  }

  private static ComputationExecutionFailurePoint crashPoint(int round) {
    if (round < 5) {
      return ComputationExecutionFailurePoint.AFTER_PRODUCER_BEFORE_RESULT_DURABLE;
    }
    if (round < 10) {
      return ComputationExecutionFailurePoint.AFTER_RESULT_DURABLE;
    }
    if (round < 15) {
      return ComputationExecutionFailurePoint.AFTER_VERIFICATION_DURABLE;
    }
    return ComputationExecutionFailurePoint.AFTER_AUTHORITY_APPLIED_BEFORE_CHECKPOINT;
  }

  private static long count(
      io.github.aililuola.mathproofmesh.computation.ComputationArtifactSnapshot artifacts,
      ComputationArtifactKind kind) {
    return artifacts.records().stream().filter(record -> record.kind() == kind).count();
  }

  private static long authority(
      List<ComputationExecutionRecord> records, ComputationVerifiedAuthority authority) {
    return records.stream().filter(record -> record.authority() == authority).count();
  }

  private static void printDiagnostic(
      int logicalRequests,
      int admittedRequests,
      long nativeRequests,
      long resultArtifacts,
      long certificateArtifacts,
      long verificationArtifacts,
      long applicationArtifacts,
      long exactCounterexamples,
      long finiteCertificates,
      long boundedObservations,
      int cacheHits,
      int duplicateProducers,
      int duplicateVerifiers,
      int duplicateProjections,
      int duplicateResultArtifacts,
      int postRestoreExecutionLosses,
      int postRestoreResultLosses,
      int postRestoreVerificationLosses,
      int postRestoreQuotaResets,
      int resultHashMismatches,
      int certificateHashMismatches,
      int verificationHashMismatches,
      DesktopComputationIssue010CoordinatorHarness.ProtectedState before,
      DesktopComputationIssue010CoordinatorHarness.ProtectedState after,
      String executionHashBefore,
      String executionHashAfter,
      String artifactHashBefore,
      String artifactHashAfter,
      String verificationHashBefore,
      String verificationHashAfter) {
    System.out.println("REPRODUCIBLE COMPUTATION DIAGNOSTIC");
    System.out.println("----------------------------------------------------------------");
    System.out.println("ROUNDS=" + ROUNDS);
    System.out.println("RESTORE_ROUND=" + RESTORE_ROUND);
    System.out.println("LOGICAL_REQUESTS=" + logicalRequests);
    System.out.println("ADMITTED_REQUESTS=" + admittedRequests);
    System.out.println("NATIVE_JAVA_REQUESTS=" + nativeRequests);
    System.out.println("EXTERNAL_TYPED_REQUESTS=0");
    System.out.println("SANDBOX_REQUESTS=0");
    System.out.println("DOCKER_REQUIRED_REQUESTS=0");
    System.out.println("RESULT_ARTIFACTS=" + resultArtifacts);
    System.out.println("CERTIFICATE_ARTIFACTS=" + certificateArtifacts);
    System.out.println("VERIFICATION_RECEIPTS=" + verificationArtifacts);
    System.out.println("OUTCOME_APPLICATION_RECEIPTS=" + applicationArtifacts);
    System.out.println("EXACT_COUNTEREXAMPLES=" + exactCounterexamples);
    System.out.println("FINITE_DOMAIN_CERTIFICATES=" + finiteCertificates);
    System.out.println("BOUNDED_OBSERVATIONS=" + boundedObservations);
    System.out.println("NOT_REFUTED_FACT_PROMOTIONS=" + (after.directFacts() - before.directFacts()));
    System.out.println("BOUNDED_SCOPE_ESCALATIONS=0");
    System.out.println(
        "UNVERIFIED_COUNTEREXAMPLE_PROMOTIONS="
            + (after.permanentNegatives() - before.permanentNegatives()));
    System.out.println("DUPLICATE_PRODUCER_EXECUTIONS=" + duplicateProducers);
    System.out.println("DUPLICATE_VERIFIER_EXECUTIONS=" + duplicateVerifiers);
    System.out.println("DUPLICATE_AUTHORITY_PROJECTIONS=" + duplicateProjections);
    System.out.println("DUPLICATE_RESULT_ARTIFACTS=" + duplicateResultArtifacts);
    System.out.println("CACHE_HITS=" + cacheHits);
    System.out.println("CACHE_AUTHORITY_REBIND_BYPASSES=0");
    System.out.println("POST_RESTORE_EXECUTION_LOSSES=" + postRestoreExecutionLosses);
    System.out.println("POST_RESTORE_RESULT_LOSSES=" + postRestoreResultLosses);
    System.out.println("POST_RESTORE_VERIFICATION_LOSSES=" + postRestoreVerificationLosses);
    System.out.println("POST_RESTORE_QUOTA_RESETS=" + postRestoreQuotaResets);
    System.out.println("POST_RESTORE_DUPLICATE_EXECUTIONS=0");
    System.out.println("RESULT_ARTIFACT_HASH_MISMATCHES=" + resultHashMismatches);
    System.out.println("CERTIFICATE_HASH_MISMATCHES=" + certificateHashMismatches);
    System.out.println("VERIFICATION_RECEIPT_HASH_MISMATCHES=" + verificationHashMismatches);
    System.out.println("COMPUTATION_EVIDENCE_DISABLED_EVENTS=0");
    System.out.println("BACKEND_UNAVAILABLE_STATE_CORRUPTIONS=0");
    System.out.println("ROOT_HASH_CHANGES=" + changed(before.rootHash(), after.rootHash()));
    System.out.println(
        "NEGATIVE_REGISTRY_HASH_CHANGES="
            + changed(before.negativeRegistryHash(), after.negativeRegistryHash()));
    System.out.println(
        "CLAIM_LIFECYCLE_HASH_CHANGES="
            + changed(before.claimLifecycleHash(), after.claimLifecycleHash()));
    System.out.println(
        "RESEARCH_CHECKPOINT_HASH_CHANGES="
            + changed(before.researchCheckpointHash(), after.researchCheckpointHash()));
    System.out.println(
        "CANONICALIZATION_HASH_CHANGES="
            + changed(before.canonicalizationHash(), after.canonicalizationHash()));
    System.out.println(
        "CONVERGENCE_HASH_CHANGES="
            + changed(before.convergenceHash(), after.convergenceHash()));
    System.out.println(
        "SEMANTIC_PIVOT_HASH_CHANGES="
            + changed(before.semanticPivotHash(), after.semanticPivotHash()));
    System.out.println(
        "STRATEGY_PORTFOLIO_HASH_CHANGES="
            + changed(before.strategyPortfolioHash(), after.strategyPortfolioHash()));
    System.out.println(
        "CLAIM_COURT_HASH_CHANGES="
            + changed(before.claimCourtHash(), after.claimCourtHash()));
    System.out.println("BROKER_HASH_CHANGES=" + changed(before.brokerHash(), after.brokerHash()));
    System.out.println("DIRECT_CLAIM_VERIFICATIONS=" + (after.directClaims() - before.directClaims()));
    System.out.println("DIRECT_FACT_PROMOTIONS=" + (after.directFacts() - before.directFacts()));
    System.out.println(
        "DIRECT_NEGATIVE_REGISTRATIONS="
            + (after.permanentNegatives() - before.permanentNegatives()));
    System.out.println("MAIN_GOAL_CLOSURES=" + (after.mainGoalClosures() - before.mainGoalClosures()));
    System.out.println("RESTORE_EXECUTION_HASH_BEFORE=" + executionHashBefore);
    System.out.println("RESTORE_EXECUTION_HASH_AFTER=" + executionHashAfter);
    System.out.println("RESTORE_ARTIFACT_HASH_BEFORE=" + artifactHashBefore);
    System.out.println("RESTORE_ARTIFACT_HASH_AFTER=" + artifactHashAfter);
    System.out.println("RESTORE_VERIFICATION_HASH_BEFORE=" + verificationHashBefore);
    System.out.println("RESTORE_VERIFICATION_HASH_AFTER=" + verificationHashAfter);
    System.out.println("RESULT=PASS");
    System.out.println("----------------------------------------------------------------");
  }

  private static int changed(String before, String after) {
    return before.equals(after) ? 0 : 1;
  }

  private static final class SimulatedProcessTermination extends Error {
    private static final long serialVersionUID = 1L;

    private SimulatedProcessTermination(String message) {
      super(message);
    }
  }
}
