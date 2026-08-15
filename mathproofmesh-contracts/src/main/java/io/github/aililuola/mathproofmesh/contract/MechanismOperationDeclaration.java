package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Model-proposed operation declaration whose node bindings are verified by the server. */
public record MechanismOperationDeclaration(
    @JsonProperty(value = "operation_id", required = true) @ContractNonNull String operationId,
    @JsonProperty(value = "kind", required = true) @ContractNonNull MechanismOperationKind kind,
    @JsonProperty(value = "input_blueprint_node_ids", required = true)
        @ContractNonNull
        List<String> inputBlueprintNodeIds,
    @JsonProperty(value = "output_blueprint_node_ids", required = true)
        @ContractNonNull
        List<String> outputBlueprintNodeIds)
    implements StrictContract {
  public MechanismOperationDeclaration {
    operationId = ContractStrings.required("operation_id", ContractStrings.trim(operationId));
    kind = ContractValues.required("kind", kind);
    inputBlueprintNodeIds = ImmutableCollections.listOrEmpty(inputBlueprintNodeIds);
    outputBlueprintNodeIds = ImmutableCollections.listOrEmpty(outputBlueprintNodeIds);
    ContractValues.minimumSize("input_blueprint_node_ids", inputBlueprintNodeIds, 1);
    ContractValues.minimumSize("output_blueprint_node_ids", outputBlueprintNodeIds, 1);
  }

  @Override
  public List<String> inputBlueprintNodeIds() {
    return List.copyOf(inputBlueprintNodeIds);
  }

  @Override
  public List<String> outputBlueprintNodeIds() {
    return List.copyOf(outputBlueprintNodeIds);
  }
}
