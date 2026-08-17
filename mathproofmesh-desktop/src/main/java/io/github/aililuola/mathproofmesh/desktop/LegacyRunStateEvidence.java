package io.github.aililuola.mathproofmesh.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.runstate.RunExecutionStatus;
import io.github.aililuola.mathproofmesh.runstate.RunMathematicalProgressSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunProjectionSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunUsageSnapshot;

record LegacyRunStateEvidence(
    String runId,
    String problemHash,
    RunExecutionStatus executionStatus,
    String currentStage,
    boolean checkpointPresent,
    boolean checkpointTerminal,
    String checkpointHash,
    RunUsageSnapshot usage,
    RunMathematicalProgressSnapshot mathematicalProgress,
    RunProjectionSnapshot projection) {}
