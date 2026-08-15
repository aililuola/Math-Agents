package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrategyMechanismMetadataContractTest {
  @Test
  void operationDeclarationsUseTheBoundedJsonVocabularyAndImmutableBindings() {
    List<String> inputs = new ArrayList<>(List.of("@roots"));
    MechanismOperationDeclaration declaration =
        new MechanismOperationDeclaration(
            "reduce-to-target",
            MechanismOperationKind.REDUCTION,
            inputs,
            List.of("@direct_targets"));
    inputs.add("untrusted-late-node");

    MechanismOperationDeclaration restored =
        ContractObjectMapper.read(
            ContractObjectMapper.toTree(declaration), MechanismOperationDeclaration.class);
    assertEquals(declaration, restored);
    assertEquals(List.of("@roots"), restored.inputBlueprintNodeIds());
    assertThrows(
        UnsupportedOperationException.class,
        () -> restored.inputBlueprintNodeIds().add("mutable"));
    for (MechanismOperationKind kind : MechanismOperationKind.values()) {
      assertEquals(kind, MechanismOperationKind.fromValue(kind.value()));
    }
    assertThrows(
        ContractValidationException.class,
        () -> MechanismOperationKind.fromValue("invented-operation"));
    assertThrows(
        ContractValidationException.class,
        () ->
            new MechanismOperationDeclaration(
                "missing-input",
                MechanismOperationKind.DIRECT,
                List.of(),
                List.of("@main_goal")));
    assertThrows(
        ContractValidationException.class,
        () ->
            new MechanismOperationDeclaration(
                "missing-output",
                MechanismOperationKind.DIRECT,
                List.of("@roots"),
                null));
  }

  @Test
  void claimContextBindingsDefaultConservativelyAndRejectUnknownPolarity() {
    CriticalClaimContextBinding defaults =
        new CriticalClaimContextBinding(
            "claim-a", null, null, null, null, null, null, null);
    assertNull(defaults.claimBlueprintNodeId());
    assertEquals(List.of(), defaults.localAssumptionNodeIds());
    assertEquals(List.of(), defaults.localAssumptions());
    assertEquals(List.of(), defaults.quantifiers());
    assertEquals(List.of(), defaults.variableBindings());
    assertEquals(List.of(), defaults.scopeLimitations());
    assertEquals("positive", defaults.polarity());

    CriticalClaimContextBinding negative =
        new CriticalClaimContextBinding(
            "claim-b", "@claim", List.of(), List.of(), List.of(), List.of(), List.of(), "negative");
    assertEquals(
        negative,
        ContractObjectMapper.read(
            ContractObjectMapper.toTree(negative), CriticalClaimContextBinding.class));
    assertThrows(
        ContractValidationException.class,
        () ->
            new CriticalClaimContextBinding(
                "claim-c",
                "@claim",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "uncertain"));
  }
}
