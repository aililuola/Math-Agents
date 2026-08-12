package io.github.aililuola.mathproofmesh.contract;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ContractInvariants {
  private ContractInvariants() {}

  static void validateRecursively(Object value) {
    validateRecursively(value, new IdentityHashMap<>());
  }

  private static void validateRecursively(Object value, IdentityHashMap<Object, Boolean> seen) {
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean
        || value instanceof Enum<?>) {
      return;
    }
    if (seen.put(value, Boolean.TRUE) != null) {
      return;
    }
    if (value instanceof Iterable<?> iterable) {
      iterable.forEach(item -> validateRecursively(item, seen));
      return;
    }
    if (value instanceof Map<?, ?> map) {
      map.values().forEach(item -> validateRecursively(item, seen));
      return;
    }
    if (!(value instanceof StrictContract contract)) {
      return;
    }
    for (RecordComponent component : value.getClass().getRecordComponents()) {
      try {
        validateRecursively(component.getAccessor().invoke(value), seen);
      } catch (IllegalAccessException | InvocationTargetException exception) {
        throw new ContractValidationException(
            "could not inspect " + value.getClass().getSimpleName(), exception);
      }
    }
    validateOne(contract);
  }

  private static void validateOne(StrictContract contract) {
    if (contract instanceof MessageEnvelope value) {
      validateQuantifiedScope(value.quantifiers(), value.variableBindings(), "");
    } else if (contract instanceof MessageReceipt value) {
      validateQuantifiedScope(
          value.parsedQuantifiers(), value.parsedVariableBindings(), "parsed ");
    } else if (contract instanceof ProofObligation value) {
      if ("closed".equals(value.status()) && value.evidenceMessageIds().isEmpty()) {
        fail("closed obligation requires reusable evidence");
      }
    } else if (contract instanceof BridgeTask value) {
      if (new HashSet<>(value.obligationIds()).size() < 2
          || new HashSet<>(value.routeIds()).size() < 2) {
        fail("a bridge task must connect at least two obligations/routes");
      }
    } else if (contract instanceof DomainOperatorSpec value) {
      requireNonEmpty(
          value.preconditions(),
          value.generatedObligations(),
          "domain operator requires preconditions and obligations");
      requireNonEmpty(
          value.fastFailureTests(),
          value.knownFailureModes(),
          "domain operator requires falsification and failure data");
    } else if (contract instanceof SurpriseMutationDirective value) {
      requireNonEmpty(
          value.targetObligationIds(),
          value.preconditions(),
          "mutation requires targets and preconditions");
      requireNonEmpty(
          value.generatedObligations(),
          value.reversibilityRequirements(),
          "mutation requires obligations and reversibility checks");
      requireNonEmpty(
          value.fastFailureTests(),
          value.knownFailureModes(),
          "mutation requires falsification and failure data");
    } else if (contract instanceof FrontierBridge value) {
      if ("candidate_ingredient".equals(value.semanticRelationship())
          && value.requiredSupportingConditions().isEmpty()) {
        fail("candidate frontier facts require explicit applicability conditions");
      }
    } else if (contract instanceof ComposedInspiration value) {
      if (new HashSet<>(value.sourceProposalIds()).size() < 2) {
        fail("composition requires two distinct source proposals");
      }
      requireNonEmpty(
          value.targetObligationIds(),
          value.fastFailureTests(),
          "composition requires a target and fast failure test");
      requireNonEmpty(
          value.compatibilityConditions(),
          value.combinedMechanism(),
          "composition requires compatibility and mechanisms");
      if (value.newObligations().isEmpty()) {
        fail("composition requires an executable new obligation");
      }
    } else if (contract instanceof RepresentationCandidate value) {
      if (value.objectMapping().isEmpty()) {
        fail("representation candidate requires an object mapping");
      }
      if (value.failureRisks().isEmpty()) {
        fail("representation candidate requires failure risks");
      }
    } else if (contract instanceof AnalogyMapping value) {
      if (value.objectCorrespondence().isEmpty() || value.operationCorrespondence().isEmpty()) {
        fail("analogy requires object and operation correspondence");
      }
      if (value.nonTransferableConditions().isEmpty()) {
        fail("analogy must state non-transferable conditions");
      }
      if (value.transferRisks().isEmpty()) {
        fail("analogy must state transfer risks");
      }
    } else if (contract instanceof ConstructionProposal value) {
      if (value.constructedObjects().isEmpty()) {
        fail("construction requires at least one constructed object");
      }
      if (value.intendedObligations().isEmpty()) {
        fail("construction must target an open obligation");
      }
      if (value.falsificationTests().isEmpty()) {
        fail("construction requires a falsification test");
      }
    } else if (contract instanceof InvariantHypothesis value) {
      requireNonEmpty(
          value.targetObligationIds(),
          value.allowedOperations(),
          "invariant hypothesis requires targets and operations");
    } else if (contract instanceof ReverseGoalPlan value) {
      requireNonEmpty(
          value.sufficientIntermediateClaims(),
          value.minimalGaps(),
          "reverse goal analysis must expose a sufficient claim and gap");
    } else if (contract instanceof InspirationProposal value) {
      if (value.evidenceType() != EvidenceType.UNVERIFIED_IDEA) {
        fail("inspiration proposals begin as unverified ideas");
      }
      if (value.generatedObligations().isEmpty()) {
        fail("inspiration proposal must target or create an obligation");
      }
    } else if (contract instanceof InspirationReview value) {
      if ("deferred".equals(value.reviewStatus()) && value.deferredReason().isBlank()) {
        fail("deferred inspiration review requires a reason");
      }
    } else if (contract instanceof InspirationCallReservation value) {
      int planned =
          value.proposerCalls()
              + value.refereeCalls()
              + value.skepticCalls()
              + value.routeAttemptCalls();
      if (planned != value.reservedCalls()) {
        fail("inspiration reservation breakdown must match reserved_calls");
      }
    } else if (contract instanceof ExperimentSpec value) {
      if (value.purpose() == ComputationPurpose.DISCOVER_PATTERN && !value.broadSearch()) {
        fail("discover_pattern requests must set broad_search=true");
      }
      if (value.broadSearch() && value.purpose() == ComputationPurpose.FALSIFY_CLAIM) {
        fail("falsify_claim is targeted; broad searches must use discover_pattern");
      }
    } else if (contract instanceof ComputationContractRepair value) {
      validateComputationRepair(value);
    } else if (contract instanceof ExperimentResult value) {
      validateExperimentResult(value);
    } else if (contract instanceof GoalNormalizationAssessment value) {
      validateGoalAssessment(value);
    } else if (contract instanceof LocalGoalPrecheck value) {
      if ("clear".equals(value.status())
          && (!value.ruleIds().isEmpty() || !value.reasons().isEmpty())) {
        fail("a clear local precheck cannot contain findings");
      }
      if ("model_review_required".equals(value.status()) && value.reasons().isEmpty()) {
        fail("model review requires at least one local finding");
      }
    } else if (contract instanceof StrategySet value) {
      if (value.strategies().isEmpty()) {
        fail("at least one strategy is required");
      }
    } else if (contract instanceof CandidateConjectureBatch value) {
      if (value.candidateConjectures().isEmpty()) {
        fail("at least one candidate conjecture is required");
      }
    } else if (contract instanceof ProofAttempt value) {
      if (value.status() == AttemptStatus.COMPLETE && value.finalAnswer() == null) {
        fail("complete attempt requires final_answer");
      }
    } else if (contract instanceof ProofDelta value) {
      validateProofDelta(value);
    } else if (contract instanceof InitialExplorationTurn value) {
      validateInitialTurn(value);
    } else if (contract instanceof ContinuationTurn value) {
      validateContinuationTurn(value);
    } else if (contract instanceof WorkingProofCheckpoint value) {
      ProofDelta delta = value.delta();
      if (!delta.parentCheckpointId().equals(value.parentVerifiedCheckpointId())) {
        fail("working checkpoint changed its verified parent");
      }
      if (!delta.problemHash().equals(value.problemHash())
          || !delta.pathId().equals(value.pathId())
          || !delta.strategyId().equals(value.strategyId())
          || !delta.segmentIndex().equals(value.segmentIndex())) {
        fail("working checkpoint and delta identities do not match");
      }
    } else if (contract instanceof BlindVerificationReport value) {
      validateVerification(
          value.problemIntegrityOk(), value.verdict(), value.issues(), "blind report");
    } else if (contract instanceof VerificationReport value) {
      validateVerification(
          value.problemIntegrityOk(), value.verdict(), value.issues(), "verification report");
    }
  }

  private static void validateQuantifiedScope(
      List<QuantifierSpec> quantifiers, List<VariableBinding> bindings, String label) {
    Set<Integer> orders = new HashSet<>();
    for (QuantifierSpec quantifier : quantifiers) {
      if (!orders.add(quantifier.order())) {
        fail(label + "quantifier orders must be unique");
      }
    }
    for (int expected = 0; expected < orders.size(); expected++) {
      if (!orders.contains(expected)) {
        fail(label + "quantifier orders must be contiguous and start at zero");
      }
    }
    Map<String, VariableBinding> byId = new java.util.HashMap<>();
    for (VariableBinding binding : bindings) {
      if (byId.put(binding.variableId(), binding) != null) {
        fail(label + "variable binding IDs must be unique");
      }
    }
    for (QuantifierSpec quantifier : quantifiers) {
      VariableBinding binding = byId.get(quantifier.variableId());
      if (binding == null) {
        fail(label + "quantified variable " + quantifier.variableId() + " has no binding");
      }
      if (!binding.domain().equals(quantifier.domain())) {
        fail(label + "quantifier and binding domains must agree");
      }
    }
  }

  private static void validateComputationRepair(ComputationContractRepair value) {
    if (value.action() == ComputationContractRepairAction.RETRY_WITH_REPAIRED_SPEC) {
      if (value.repairedSpec() == null) {
        fail("retry_with_repaired_spec requires repaired_spec");
      }
      if (value.semanticEquivalence() == null || value.semanticEquivalence().isEmpty()) {
        fail("retry_with_repaired_spec requires a semantic equivalence statement");
      }
      if (value.repairedSpec().method() == ComputationMethod.SANDBOXED_PYTHON
          && (value.repairedSpec().typedToolGap() == null
              || value.repairedSpec().typedToolGap().length() < 12)) {
        fail("sandboxed_python repair requires a precise typed_tool_gap");
      }
    } else if (value.repairedSpec() != null) {
      fail("abandon_as_unrepresentable cannot carry a repaired_spec");
    }
  }

  private static void validateExperimentResult(ExperimentResult value) {
    if (value.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND) {
      if (value.counterexample() == null) {
        fail("counterexample_found requires a counterexample payload");
      }
      if (value.evidenceStrength() != EvidenceStrength.COUNTEREXAMPLE) {
        fail("a counterexample must use counterexample evidence strength");
      }
      if (!value.independentlyVerified()) {
        fail("counterexample_found requires independent deterministic verification");
      }
    }
    if (value.outcome() == ExperimentOutcome.CERTIFIED) {
      if (value.certificate() == null) {
        fail("certified requires a certificate payload");
      }
      if (value.evidenceStrength() != EvidenceStrength.EXHAUSTIVE_CERTIFICATE
          && value.evidenceStrength() != EvidenceStrength.FORMAL_CERTIFICATE) {
        fail("certified requires exhaustive_certificate or formal_certificate evidence");
      }
    }
    if (value.outcome() == ExperimentOutcome.NOT_REFUTED
        && value.evidenceStrength() != EvidenceStrength.HEURISTIC
        && value.evidenceStrength() != EvidenceStrength.BOUNDED_EVIDENCE) {
      fail("not_refuted can only be heuristic or bounded evidence");
    }
    if ((value.outcome() == ExperimentOutcome.ERROR
            || value.outcome() == ExperimentOutcome.INCONCLUSIVE)
        && value.evidenceStrength() != EvidenceStrength.HEURISTIC) {
      fail("failed or inconclusive computation is only heuristic");
    }
  }

  private static void validateGoalAssessment(GoalNormalizationAssessment value) {
    if (value.hasAmbiguity() && value.ambiguityReasons().isEmpty()) {
      fail("ambiguous assessments require at least one reason");
    }
    if ((value.hasAmbiguity() || !value.isWellFormed())
        && (value.clarificationQuestion() == null || value.clarificationQuestion().isBlank())) {
      fail("a clarification question is required for an unclear goal");
    }
    Set<String> statements = new HashSet<>();
    statements.add(value.recommendedStatement());
    for (GoalInterpretationCandidate candidate : value.alternativeInterpretations()) {
      if (!statements.add(candidate.statement())) {
        fail("goal interpretation candidates must be distinct");
      }
    }
  }

  private static void validateProofDelta(ProofDelta value) {
    if (value.proofComplete() && value.candidateFinalAnswer() == null) {
      fail("proof_complete delta requires candidate_final_answer");
    }
    if (value.proofComplete() && !value.remainingSubgoals().isEmpty()) {
      fail("proof_complete delta cannot retain remaining_subgoals");
    }
    if (value.newSteps().isEmpty() && value.detectedConflicts().isEmpty()) {
      fail("delta must add at least one proof step or report a conflict");
    }
  }

  private static void validateInitialTurn(InitialExplorationTurn value) {
    if (value.experimentImpact() == FailureLevel.NONE) {
      fail("experiment_impact must classify execution, plan, or strategy");
    }
    if (value.action() == InitialExplorationAction.SUBMIT_ATTEMPT) {
      if (value.attempt() == null || value.experimentSpec() != null) {
        fail("submit_attempt requires only an attempt");
      }
    } else if (value.action() == InitialExplorationAction.REQUEST_COMPUTATION) {
      if (value.experimentSpec() == null || value.attempt() != null) {
        fail("request_computation requires only an experiment_spec");
      }
      if (value.experimentImpact() != null) {
        fail("request_computation cannot classify an experiment before it runs");
      }
    } else if (value.attempt() != null || value.experimentSpec() != null) {
      fail("abandon cannot carry an attempt or experiment request");
    }
  }

  private static void validateContinuationTurn(ContinuationTurn value) {
    if (value.experimentImpact() == FailureLevel.NONE) {
      fail("experiment_impact must classify execution, plan, or strategy");
    }
    if (value.action() == ContinuationAction.SUBMIT_DELTA
        || value.action() == ContinuationAction.COMPLETE) {
      if (value.delta() == null || value.experimentSpec() != null) {
        fail("submit_delta/complete requires only a proof delta");
      }
      if (value.action() == ContinuationAction.COMPLETE && !value.delta().proofComplete()) {
        fail("complete requires delta.proof_complete=true");
      }
    } else if (value.action() == ContinuationAction.REQUEST_COMPUTATION) {
      if (value.experimentSpec() == null || value.delta() != null) {
        fail("request_computation requires only an experiment_spec");
      }
      if (value.experimentImpact() != null) {
        fail("request_computation cannot classify an experiment before it runs");
      }
    } else if (value.delta() != null || value.experimentSpec() != null) {
      fail("abandon cannot carry a proof delta or experiment request");
    }
  }

  private static void validateVerification(
      Boolean integrityOk,
      VerificationVerdict verdict,
      List<VerificationIssue> issues,
      String label) {
    if (verdict == VerificationVerdict.FAIL && issues.isEmpty()) {
      fail("failed " + label + " must contain at least one issue");
    }
    if (!integrityOk && verdict != VerificationVerdict.FAIL) {
      fail("problem_integrity_ok=false requires verdict=fail");
    }
    if (!integrityOk && issues.isEmpty()) {
      fail("a goal-alignment failure requires a concrete issue");
    }
  }

  private static void requireNonEmpty(List<?> left, List<?> right, String message) {
    if (left.isEmpty() || right.isEmpty()) {
      fail(message);
    }
  }

  private static void fail(String message) {
    throw new ContractValidationException(message);
  }
}
