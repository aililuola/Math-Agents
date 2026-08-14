package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.SemanticPivotProposal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SemanticPivotProposalPromptTest {
  @Test
  void proposalPromptCarriesOnlyBoundedImmutableAuthorityContext() {
    PromptBundle<SemanticPivotProposal> prompt =
        new PromptFactory("English")
            .typedStage(
                "semantic_pivot_proposal",
                SemanticPivotProposal.class,
                Map.of(
                    "immutable_root_goal",
                    Map.of("source_statement", "Prove P.", "hash", "root-hash", "editable", false),
                    "trusted_obstruction_refs",
                    SemanticPivotServerTestFixtures.obstructions().values(),
                    "allowed_transformation_types",
                    List.of("OBJECT_REPLACEMENT")));

    assertThat(prompt.user())
        .contains("[STAGE:semantic_pivot_proposal]")
        .contains("immutable_root_goal")
        .contains("trusted_obstruction_refs")
        .contains("claimed_pivot_id")
        .contains("non-authoritative draft")
        .doesNotContain("chain_of_thought");
  }
}
