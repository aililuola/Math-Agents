package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import io.github.aililuola.mathproofmesh.contract.SemanticPivotProposal;
import io.github.aililuola.mathproofmesh.proofcontrol.PivotCompilationException;
import io.github.aililuola.mathproofmesh.proofcontrol.SemanticPivotCompiler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticPivotContractsTest {
  @Test
  void duplicateAndAuthorityEscalatingPayloadsFailClosed() {
    SemanticPivotProposal source = SemanticPivotServerTestFixtures.proposal();
    List<String> duplicateTypes = new ArrayList<>(source.transformationTypes());
    duplicateTypes.add(source.transformationTypes().getFirst());
    SemanticPivotProposal duplicate = copy(source, duplicateTypes, source.objectChanges(), null, null);
    assertThatThrownBy(
            () ->
                new SemanticPivotCompiler()
                    .compile(duplicate, SemanticPivotServerTestFixtures.obstructions()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicates");

    List<SemanticPivotProposal.ObjectChangeDraft> duplicateObjects =
        new ArrayList<>(source.objectChanges());
    duplicateObjects.add(source.objectChanges().getFirst());
    assertThatThrownBy(
            () ->
                new SemanticPivotCompiler()
                    .compile(
                        copy(source, source.transformationTypes(), duplicateObjects, null, null),
                        SemanticPivotServerTestFixtures.obstructions()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate identities");

    assertThatThrownBy(
            () ->
                new SemanticPivotCompiler()
                    .compile(
                        copy(source, source.transformationTypes(), source.objectChanges(), "model-id", "model-hash"),
                        SemanticPivotServerTestFixtures.obstructions()))
        .isInstanceOf(PivotCompilationException.class)
        .hasMessageContaining("server-owned");

    for (String unauthorized :
        List.of("editable_root_goal", "verified_claim", "fact", "permanent_negative")) {
      ObjectNode payload = (ObjectNode) ContractObjectMapper.toTree(source);
      payload.put(unauthorized, true);
      assertThatThrownBy(() -> ContractObjectMapper.read(payload, SemanticPivotProposal.class))
          .as(unauthorized)
          .isInstanceOf(ContractValidationException.class)
          .hasMessageContaining("Unrecognized field");
    }
  }

  private static SemanticPivotProposal copy(
      SemanticPivotProposal source,
      List<String> types,
      List<SemanticPivotProposal.ObjectChangeDraft> objects,
      String claimedId,
      String claimedHash) {
    return new SemanticPivotProposal(
        source.proposalId(),
        source.proposerAgentId(),
        source.problemHash(),
        source.rootGoalHash(),
        source.routeId(),
        source.sourceStrategyId(),
        types,
        source.obstructionIds(),
        objects,
        source.directionChanges(),
        source.assumptionChanges(),
        source.claimUseChanges(),
        source.obligationChanges(),
        source.proposedStrategy(),
        source.rationale(),
        claimedId,
        claimedHash);
  }
}
