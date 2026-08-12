package io.github.aililuola.mathproofmesh.desktop;

import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

final class DesktopParityTest {
  @TempDir Path temporaryDirectory;

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void authorityParity(String function, DesktopParityScenarios.Scenario scenario) throws Exception {
    scenario.run(temporaryDirectory.resolve(function));
  }

  static Stream<Arguments> authorityCases() {
    return Stream.of(
        Arguments.of(
            "test_settings_and_credentials_are_persisted_without_plaintext",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::settingsAndCredentialsPersistedWithoutPlaintext),
        Arguments.of(
            "test_desktop_config_uses_user_writable_paths_and_injected_keys",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::configUsesWritablePathsAndInjectedKeys),
        Arguments.of(
            "test_desktop_app_requires_session_cookie_and_serves_workbench",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::appRequiresCookieAndServesWorkbench),
        Arguments.of(
            "test_run_activity_returns_one_latest_snapshot_per_logical_task",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::activityReturnsLatestLogicalSnapshot),
        Arguments.of(
            "test_run_activity_reconstructs_topology_metadata",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::activityReconstructsTopologyMetadata),
        Arguments.of(
            "test_desktop_reasoning_snapshot_and_completed_sse_replay",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::reasoningSnapshotAndCompletedReplay),
        Arguments.of(
            "test_desktop_computation_snapshot_is_complete_and_redacted",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::computationSnapshotCompleteAndRedacted),
        Arguments.of(
            "test_desktop_delete_moves_run_directory_to_recycle_bin",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::deleteMovesRunToRecycleBin),
        Arguments.of(
            "test_desktop_run_manager_publishes_terminal_state",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::managerPublishesTerminalState),
        Arguments.of(
            "test_desktop_resume_endpoint_defaults_to_normal",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::resumeDefaultsToNormal),
        Arguments.of(
            "test_desktop_run_manager_resume_threads_intervention_to_orchestrator",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::managerThreadsResumeMode),
        Arguments.of(
            "test_desktop_run_manager_pauses_until_goal_is_confirmed",
            (DesktopParityScenarios.Scenario)
                DesktopParityScenarios::managerClarificationConfirmation));
  }
}
