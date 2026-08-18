package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AgentLeaseRoleIsolationTest {
  @Test
  void independentReviewMustExcludeItsAuthor() {
    assertThatThrownBy(
            () -> new AgentLeaseRequest(
                "run", "epoch", "work", AgentLeaseClass.ADVERSARIAL_REVIEW,
                "reviewer", Set.of(), List.of(), "author", "", 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authorAgentId");
  }
}
