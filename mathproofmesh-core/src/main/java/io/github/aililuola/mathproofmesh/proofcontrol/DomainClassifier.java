package io.github.aililuola.mathproofmesh.proofcontrol;

import static io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.AssumptionDomain;
import static io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ObligationDomain;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Separates mathematical obligations from search, process, and protocol work. */
public final class DomainClassifier {
  private static final List<String> PROTOCOL =
      List.of(
          "do not change", "goal hash", "output json", "output yaml",
          "response schema", "required format");
  private static final List<String> PROCESS =
      List.of(
          "checkpoint policy", "checkpoint format", "resume policy",
          "workflow state", "round budget", "processing opportunity");
  private static final List<String> TOOL =
      List.of(
          "tool budget", "runtime limit", "memory limit",
          "execute the computation", "run the tool", "typed handler");
  private static final List<String> SAFETY =
      List.of("api key", "network access", "sandbox policy", "secret value");
  private static final List<String> VERIFICATION =
      List.of(
          "independent reviewer", "referee review", "verify the proof",
          "verification pass", "formalization review", "audit the proof");
  private static final List<String> SEARCH =
      List.of(
          "find a representation", "find a suitable", "search for",
          "explore an alternative", "try another route");
  private static final Map<String, ObligationDomain> EXPLICIT =
      Map.ofEntries(
          Map.entry("strategy", ObligationDomain.MATHEMATICAL),
          Map.entry("strategy_blueprint", ObligationDomain.MATHEMATICAL),
          Map.entry("claim", ObligationDomain.MATHEMATICAL),
          Map.entry("bridge", ObligationDomain.MATHEMATICAL),
          Map.entry("counterexample", ObligationDomain.MATHEMATICAL),
          Map.entry("search", ObligationDomain.SEARCH),
          Map.entry("countermodel_task", ObligationDomain.SEARCH),
          Map.entry("falsification_task", ObligationDomain.SEARCH),
          Map.entry("process", ObligationDomain.PROCESS),
          Map.entry("checkpoint", ObligationDomain.PROCESS),
          Map.entry("workflow", ObligationDomain.PROCESS),
          Map.entry("tool", ObligationDomain.TOOL),
          Map.entry("computation", ObligationDomain.TOOL),
          Map.entry("verification", ObligationDomain.VERIFICATION),
          Map.entry("review", ObligationDomain.VERIFICATION),
          Map.entry("protocol", ObligationDomain.PROTOCOL),
          Map.entry("schema", ObligationDomain.PROTOCOL),
          Map.entry("safety", ObligationDomain.SAFETY));

  public record ObligationClassification(
      String obligationId, ObligationDomain domain, String source, double confidence) {}

  public record AssumptionClassification(
      String key, AssumptionDomain domain, String source, double confidence) {}

  public ObligationClassification classifyObligation(
      ProofControlModels.Obligation obligation, String sourceKind) {
    if (obligation.kind() == ProofControlModels.ObligationKind.MAIN_GOAL) {
      return new ObligationClassification(
          obligation.id(), ObligationDomain.MATHEMATICAL, "main_goal_kind", 1.0d);
    }
    Classification classification = classify(obligation.statement(), sourceKind);
    return new ObligationClassification(
        obligation.id(), classification.domain(), classification.source(),
        classification.confidence());
  }

  public AssumptionClassification classifyAssumption(
      String statement, String sourceKind) {
    Classification classification = classify(statement, sourceKind);
    return new AssumptionClassification(
        ProofIdentity.obligationIdentityText(statement),
        AssumptionDomain.valueOf(classification.domain().name()),
        classification.source(),
        classification.confidence());
  }

  private static Classification classify(String statement, String sourceKind) {
    String explicit =
        sourceKind == null ? "" : sourceKind.strip().toLowerCase(Locale.ROOT);
    ObligationDomain explicitDomain = EXPLICIT.get(explicit);
    if (explicitDomain != null) {
      return new Classification(explicitDomain, explicit, 1.0d);
    }
    String text = ProofIdentity.normalizeText(statement).toLowerCase(Locale.ROOT);
    if (contains(text, SAFETY)) {
      return new Classification(ObligationDomain.SAFETY, "safety_marker", 1.0d);
    }
    if (contains(text, PROTOCOL)) {
      return new Classification(ObligationDomain.PROTOCOL, "protocol_marker", 1.0d);
    }
    if (contains(text, PROCESS)) {
      return new Classification(ObligationDomain.PROCESS, "process_marker", 1.0d);
    }
    if (contains(text, TOOL)) {
      return new Classification(ObligationDomain.TOOL, "tool_marker", 1.0d);
    }
    if (contains(text, VERIFICATION)) {
      return new Classification(
          ObligationDomain.VERIFICATION, "verification_marker", 1.0d);
    }
    if (contains(text, SEARCH)) {
      return new Classification(ObligationDomain.SEARCH, "search_marker", 0.95d);
    }
    return new Classification(
        ObligationDomain.MATHEMATICAL, "mathematical_default", 0.8d);
  }

  private static boolean contains(String text, List<String> markers) {
    return markers.stream().anyMatch(text::contains);
  }

  private record Classification(
      ObligationDomain domain, String source, double confidence) {}
}
