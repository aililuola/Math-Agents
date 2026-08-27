package io.github.aililuola.mathproofmesh;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.Test;

class TemporalTestingSmokeTest {
    @Test
    void startsAndClosesTheInProcessTemporalTestEnvironment() {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        try {
            environment.start();
            assertNotNull(environment.getWorkflowClient());
            assertNotNull(environment.getWorkflowServiceStubs());
        } finally {
            environment.close();
        }
    }
}
