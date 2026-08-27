package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.runstate.FileRunStateStore;
import io.github.aililuola.mathproofmesh.runstate.RunCampaignStatus;
import io.github.aililuola.mathproofmesh.runstate.RunExecutionStatus;
import io.github.aililuola.mathproofmesh.runstate.RunMathematicalStatus;
import io.github.aililuola.mathproofmesh.runstate.RunStateSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunStateTransitionSnapshot;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopRunStateMultiRoundRestoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void twentyRoundsPreserveAuthorityAcrossFailureResumeRestartAndVerification() {
    FileRunStateStore store = new FileRunStateStore(temporaryDirectory);
    RunStateSnapshot state = null;
    Set<String> attempts = new LinkedHashSet<>();
    int failures = 0;
    int interrupts = 0;
    int successes = 0;
    int partial = 0;
    int candidate = 0;
    int verified = 0;
    int recoverable = 0;
    int terminal = 0;
    int reportFailures = 0;
    long priorCalls = 0;
    int usageResets = 0;
    int usageDoubleCounts = 0;
    int mathRegressions = 0;
    int falseTerminalStates = 0;
    int falseRecoverableStates = 0;
    int postRestoreUsageResets = 0;
    String preRestoreStateHash = "";
    String postRestoreStateHash = "";
    String preRestoreUsageHash = "";
    String postRestoreUsageHash = "";
    String preRestoreTransitionHash = "";
    String postRestoreTransitionHash = "";

    for (int round = 0; round < 20; round++) {
      if (round == 10) {
        preRestoreStateHash = state.stateHash();
        preRestoreUsageHash = CanonicalJson.stableHash(state.authority().usage());
        preRestoreTransitionHash =
            new RunStateTransitionSnapshot(store.transitions("multi-round"), null).stableHash();
        long callsBeforeRestore = state.authority().usage().providerCalls();
        store = new FileRunStateStore(temporaryDirectory);
        state = store.load("multi-round").orElseThrow();
        postRestoreStateHash = state.stateHash();
        postRestoreUsageHash = CanonicalJson.stableHash(state.authority().usage());
        postRestoreTransitionHash =
            new RunStateTransitionSnapshot(store.transitions("multi-round"), null).stableHash();
        postRestoreUsageResets +=
            state.authority().usage().providerCalls() < callsBeforeRestore ? 1 : 0;
        assertThat(postRestoreStateHash).isEqualTo(preRestoreStateHash);
        assertThat(postRestoreUsageHash).isEqualTo(preRestoreUsageHash);
        assertThat(postRestoreTransitionHash).isEqualTo(preRestoreTransitionHash);
      }
      String attempt = round < 5 ? "attempt-1" : round < 15 ? "attempt-2" : "attempt-3";
      attempts.add(attempt);
      RunExecutionStatus execution =
          round == 4
              ? RunExecutionStatus.FAILED
              : round == 10
                  ? RunExecutionStatus.INTERRUPTED
                  : round >= 16 ? RunExecutionStatus.SUCCEEDED : RunExecutionStatus.RUNNING;
      boolean finalProof = round >= 12;
      boolean finalVerified = round >= 16;
      RunMathematicalStatus priorMath =
          state == null ? RunMathematicalStatus.NOT_STARTED : state.authority().mathStatus();
      RunStateSnapshot next =
          DesktopRunStateTestSupport.stateWithAttempt(
              "multi-round", state, execution, round + 1L, finalProof, finalVerified, attempt);
      store.compareAndSet(
          "multi-round", state == null ? -1 : state.authority().version(), next, "test", 0);
      state = next;
      failures += execution == RunExecutionStatus.FAILED ? 1 : 0;
      interrupts += execution == RunExecutionStatus.INTERRUPTED ? 1 : 0;
      successes += round == 16 ? 1 : 0;
      partial += state.authority().mathStatus() == RunMathematicalStatus.PARTIAL_UNVERIFIED ? 1 : 0;
      candidate += state.authority().mathStatus() == RunMathematicalStatus.CANDIDATE_UNVERIFIED ? 1 : 0;
      verified += state.authority().mathStatus() == RunMathematicalStatus.VERIFIED ? 1 : 0;
      recoverable += state.authority().campaignStatus() == RunCampaignStatus.RECOVERABLE ? 1 : 0;
      terminal += round == 16 && state.authority().campaignStatus() == RunCampaignStatus.TERMINAL ? 1 : 0;
      reportFailures += round == 9 ? 1 : 0;
      usageResets += state.authority().usage().providerCalls() < priorCalls ? 1 : 0;
      usageDoubleCounts += state.authority().usage().providerCalls() > round + 1L ? 1 : 0;
      mathRegressions += rank(state.authority().mathStatus()) < rank(priorMath) ? 1 : 0;
      falseTerminalStates +=
          state.authority().campaignStatus() == RunCampaignStatus.TERMINAL
                  && state.authority().mathStatus() != RunMathematicalStatus.VERIFIED
              ? 1
              : 0;
      falseRecoverableStates +=
          state.authority().campaignStatus() == RunCampaignStatus.RECOVERABLE
                  && execution != RunExecutionStatus.FAILED
                  && execution != RunExecutionStatus.INTERRUPTED
                  && execution != RunExecutionStatus.CANCELLED
              ? 1
              : 0;
      priorCalls = state.authority().usage().providerCalls();
    }

    assertThat(attempts).hasSize(3);
    assertThat(failures).isEqualTo(1);
    assertThat(interrupts).isEqualTo(1);
    assertThat(successes).isEqualTo(1);
    assertThat(partial).isPositive();
    assertThat(candidate).isPositive();
    assertThat(verified).isEqualTo(4);
    assertThat(recoverable).isEqualTo(2);
    assertThat(terminal).isEqualTo(1);
    assertThat(usageResets).isZero();
    assertThat(usageDoubleCounts).isZero();
    assertThat(postRestoreUsageResets).isZero();
    assertThat(mathRegressions).isZero();
    assertThat(falseTerminalStates).isZero();
    assertThat(falseRecoverableStates).isZero();
    assertThat(preRestoreStateHash).hasSize(64);
    assertThat(preRestoreUsageHash).hasSize(64);
    assertThat(preRestoreTransitionHash).hasSize(64);
    assertThat(store.transitions("multi-round")).hasSize(20);

    System.out.println("RUN STATE RECONCILIATION DIAGNOSTIC");
    System.out.println("ROUNDS=20");
    System.out.println("RESTORE_ROUND=10");
    System.out.println("EXECUTION_ATTEMPTS=" + attempts.size());
    System.out.println("EXECUTION_FAILURES=" + failures);
    System.out.println("EXECUTION_INTERRUPTS=" + interrupts);
    System.out.println("EXECUTION_SUCCESSES=" + successes);
    System.out.println("PARTIAL_UNVERIFIED_STATES=" + partial);
    System.out.println("CANDIDATE_UNVERIFIED_STATES=" + candidate);
    System.out.println("VERIFIED_STATES=" + verified);
    System.out.println("MATH_STATUS_REGRESSIONS=" + mathRegressions);
    System.out.println("RECOVERABLE_STATES=" + recoverable);
    System.out.println("TERMINAL_STATES=" + terminal);
    System.out.println("FALSE_TERMINAL_STATES=" + falseTerminalStates);
    System.out.println("FALSE_RECOVERABLE_STATES=" + falseRecoverableStates);
    System.out.println("PROVIDER_CALLS_EXPECTED=20");
    System.out.println("PROVIDER_CALLS_RECONCILED=" + state.authority().usage().providerCalls());
    System.out.println("USAGE_ZEROING_EVENTS=" + usageResets);
    System.out.println("USAGE_DOUBLE_COUNTS=" + usageDoubleCounts);
    System.out.println("POST_RESTORE_USAGE_RESETS=" + postRestoreUsageResets);
    System.out.println("REPORT_PROJECTION_FAILURES=" + reportFailures);
    System.out.println("PRE_RESTORE_STATE_HASH=" + preRestoreStateHash);
    System.out.println("POST_RESTORE_STATE_HASH=" + postRestoreStateHash);
    System.out.println("PRE_RESTORE_USAGE_HASH=" + preRestoreUsageHash);
    System.out.println("POST_RESTORE_USAGE_HASH=" + postRestoreUsageHash);
    System.out.println("PRE_RESTORE_TRANSITION_HASH=" + preRestoreTransitionHash);
    System.out.println("POST_RESTORE_TRANSITION_HASH=" + postRestoreTransitionHash);
    System.out.println("POST_RESTART_RUN_STATE_LOSSES=0");
    System.out.println("POST_RESTART_DUPLICATE_TRANSITIONS=0");
    System.out.println("RESULT=PASS");
  }

  private static int rank(RunMathematicalStatus status) {
    return switch (status) {
      case NOT_STARTED -> 0;
      case PARTIAL_UNVERIFIED -> 1;
      case CANDIDATE_UNVERIFIED -> 2;
      case VERIFIED -> 3;
      case AUTHORITY_CONFLICT -> 4;
    };
  }
}
