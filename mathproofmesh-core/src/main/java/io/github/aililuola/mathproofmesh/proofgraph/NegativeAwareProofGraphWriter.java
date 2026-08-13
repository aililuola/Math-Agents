package io.github.aililuola.mathproofmesh.proofgraph;

import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.memory.NegativeCandidateIntent;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeAdmissionGate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeCandidate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSurface;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeTargetType;
import java.util.List;
import java.util.ArrayList;
import java.util.function.IntSupplier;

public final class NegativeAwareProofGraphWriter {
  public static final String IMMUTABLE_ROOT_GOAL = "IMMUTABLE_ROOT_GOAL";

  private final ProofGraphStore store;
  private final NegativeKnowledgeAdmissionGate gate;
  private final IntSupplier currentRound;
  private final List<String> defaultScope;

  NegativeAwareProofGraphWriter(
      ProofGraphStore store,
      NegativeKnowledgeAdmissionGate gate,
      IntSupplier currentRound,
      List<String> defaultScope) {
    this.store = java.util.Objects.requireNonNull(store, "store");
    this.gate = java.util.Objects.requireNonNull(gate, "gate");
    this.currentRound = java.util.Objects.requireNonNull(currentRound, "currentRound");
    this.defaultScope = defaultScope == null ? List.of() : List.copyOf(defaultScope);
  }

  public ProofObligation addObligation(ProofObligation obligation) {
    gate.requireAllAllowed(
        candidates(obligation, NegativeCandidateIntent.PROOF_TARGET), round());
    return store.addObligationUnchecked(obligation);
  }

  public ProofObligation addRootGoalObligation(ProofObligation obligation, String authority) {
    if (!IMMUTABLE_ROOT_GOAL.equals(authority)) {
      throw new IllegalArgumentException("root-goal bypass requires IMMUTABLE_ROOT_GOAL authority");
    }
    return store.addObligationUnchecked(obligation);
  }

  public ProofObligation addFalsificationObligation(ProofObligation obligation) {
    gate.requireAllAllowed(
        candidates(obligation, NegativeCandidateIntent.FALSIFICATION_ONLY), round());
    return store.addObligationUnchecked(obligation);
  }

  public MessageEnvelope addClaimNode(MessageEnvelope message) {
    gate.requireAllowed(candidate(message, NegativeCandidateIntent.POSITIVE_DEPENDENCY), round());
    return store.addClaimNodeUnchecked(message);
  }

  public MessageEnvelope addFalsificationClaimNode(MessageEnvelope message) {
    gate.requireAllowed(candidate(message, NegativeCandidateIntent.FALSIFICATION_ONLY), round());
    return store.addClaimNodeUnchecked(message);
  }

  public List<String> revalidateOpenObligations() {
    List<String> blocked = new ArrayList<>();
    for (ProofObligation obligation : store.obligations()) {
      if (!"open".equals(obligation.status()) && !"tentative".equals(obligation.status())) {
        continue;
      }
      if (obligation.kind()
          == io.github.aililuola.mathproofmesh.contract.ObligationKind.MAIN_GOAL) {
        continue;
      }
      boolean rejected = false;
      for (NegativeKnowledgeTargetType targetType : NegativeKnowledgeTargetType.values()) {
        NegativeKnowledgeCandidate candidate =
            new NegativeKnowledgeCandidate(
                obligation.problemHash(),
                targetType,
                obligation.statement(),
                obligation.normalizedStatement(),
                obligation.assumptions(),
                obligation.quantifiers(),
                List.of(),
                defaultScope,
                NegativeKnowledgeSurface.RESTORE_REVALIDATION,
                NegativeCandidateIntent.PROOF_TARGET);
        if (!gate.evaluate(candidate, round()).allowed()) {
          rejected = true;
        }
      }
      if (rejected) {
        store.blockObligationUnchecked(
            obligation.obligationId(), "negative_knowledge_restore_revalidation");
        blocked.add(obligation.obligationId());
      }
    }
    return List.copyOf(blocked);
  }

  private List<NegativeKnowledgeCandidate> candidates(
      ProofObligation obligation, NegativeCandidateIntent intent) {
    return java.util.Arrays.stream(NegativeKnowledgeTargetType.values())
        .map(
            targetType ->
                new NegativeKnowledgeCandidate(
                    obligation.problemHash(),
                    targetType,
                    obligation.statement(),
                    obligation.normalizedStatement(),
                    obligation.assumptions(),
                    obligation.quantifiers(),
                    List.of(),
                    defaultScope,
                    NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
                    intent))
        .toList();
  }

  private NegativeKnowledgeCandidate candidate(
      MessageEnvelope message, NegativeCandidateIntent intent) {
    List<String> scope =
        message.scopeLimitations().isEmpty() ? defaultScope : message.scopeLimitations();
    return new NegativeKnowledgeCandidate(
        message.problemHash(),
        NegativeKnowledgeTargetType.CLAIM,
        message.statement(),
        message.normalizedStatement(),
        message.assumptions(),
        message.quantifiers(),
        message.variableBindings(),
        scope,
        NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
        intent);
  }

  private int round() {
    return Math.max(0, currentRound.getAsInt());
  }
}
