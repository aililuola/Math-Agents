package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record MessageReceipt(
    @JsonProperty(value = "acknowledged_at") @ContractNonNull String acknowledgedAt,
    @JsonProperty(value = "claimed_closed_obligation_ids") @ContractNonNull List<String> claimedClosedObligationIds,
    @JsonProperty(value = "delivered_round", required = true) @ContractNonNull Integer deliveredRound,
    @JsonProperty(value = "message_id", required = true) @ContractNonNull String messageId,
    @JsonProperty(value = "parsed_assumptions") @ContractNonNull List<String> parsedAssumptions,
    @JsonProperty(value = "parsed_conclusion") @ContractNonNull String parsedConclusion,
    @JsonProperty(value = "parsed_quantifiers") @ContractNonNull List<QuantifierSpec> parsedQuantifiers,
    @JsonProperty(value = "parsed_variable_bindings") @ContractNonNull List<VariableBinding> parsedVariableBindings,
    @JsonProperty(value = "reason") @ContractNonNull String reason,
    @JsonProperty(value = "receipt_id") @ContractNonNull String receiptId,
    @JsonProperty(value = "receipt_token") @ContractNonNull String receiptToken,
    @JsonProperty(value = "referenced_in_step_ids") @ContractNonNull List<String> referencedInStepIds,
    @JsonProperty(value = "semantic_hash") @ContractNonNull String semanticHash,
    @JsonProperty(value = "status", required = true) @ContractNonNull ReceiptStatus status,
    @JsonProperty(value = "target_route_id", required = true) @ContractNonNull String targetRouteId,
    @JsonProperty(value = "used") @ContractNonNull Boolean used
) implements StrictContract {

  public MessageReceipt {
    if (acknowledgedAt == null) {
      acknowledgedAt = PythonIsoTimestampCodec.now();
    }
    acknowledgedAt = ContractStrings.trim(acknowledgedAt);
    if (claimedClosedObligationIds == null) {
      claimedClosedObligationIds = List.of();
    }
    claimedClosedObligationIds = ImmutableCollections.listOrEmpty(claimedClosedObligationIds);
    deliveredRound = ContractValues.required("delivered_round", deliveredRound);
    ContractValues.minimum("delivered_round", deliveredRound, 0);
    messageId = ContractStrings.trim(messageId);
    messageId = ContractStrings.required("message_id", messageId);
    if (parsedAssumptions == null) {
      parsedAssumptions = List.of();
    }
    parsedAssumptions = ImmutableCollections.listOrEmpty(parsedAssumptions);
    if (parsedConclusion == null) {
      parsedConclusion = "";
    }
    parsedConclusion = ContractStrings.trim(parsedConclusion);
    if (parsedQuantifiers == null) {
      parsedQuantifiers = List.of();
    }
    parsedQuantifiers = ImmutableCollections.listOrEmpty(parsedQuantifiers);
    if (parsedVariableBindings == null) {
      parsedVariableBindings = List.of();
    }
    parsedVariableBindings = ImmutableCollections.listOrEmpty(parsedVariableBindings);
    if (reason == null) {
      reason = "";
    }
    reason = ContractStrings.trim(reason);
    if (receiptId == null) {
      receiptId = PythonCompatibleIdGenerator.newId("receipt");
    }
    receiptId = ContractStrings.trim(receiptId);
    if (receiptToken == null) {
      receiptToken = "";
    }
    receiptToken = ContractStrings.trim(receiptToken);
    if (referencedInStepIds == null) {
      referencedInStepIds = List.of();
    }
    referencedInStepIds = ImmutableCollections.listOrEmpty(referencedInStepIds);
    if (semanticHash == null) {
      semanticHash = "";
    }
    semanticHash = ContractStrings.trim(semanticHash);
    status = ContractValues.required("status", status);
    targetRouteId = ContractStrings.trim(targetRouteId);
    targetRouteId = ContractStrings.required("target_route_id", targetRouteId);
    if (used == null) {
      used = false;
    }
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> claimedClosedObligationIds() {
    return claimedClosedObligationIds == null ? null : List.copyOf(claimedClosedObligationIds);
  }

  public List<String> parsedAssumptions() {
    return parsedAssumptions == null ? null : List.copyOf(parsedAssumptions);
  }

  public List<QuantifierSpec> parsedQuantifiers() {
    return parsedQuantifiers == null ? null : List.copyOf(parsedQuantifiers);
  }

  public List<VariableBinding> parsedVariableBindings() {
    return parsedVariableBindings == null ? null : List.copyOf(parsedVariableBindings);
  }

  public List<String> referencedInStepIds() {
    return referencedInStepIds == null ? null : List.copyOf(referencedInStepIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
