package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.CriticalClaim;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import io.github.aililuola.mathproofmesh.memory.VerifiedCounterexampleAuthority;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels;
import io.github.aililuola.mathproofmesh.proofcontrol.StrategyBlueprintCompiler;
import io.github.aililuola.mathproofmesh.strategydiversity.CommonModeRiskRegistry;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimKeyCompiler;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimPreflightEvidence;
import io.github.aililuola.mathproofmesh.strategydiversity.CriticalClaimPreflightStatus;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCriticalClaimPreflight;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyDiversityConfig;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyFeasibilityCalibrator;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismAnalyzer;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioCandidate;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioConstraint;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPortfolioOptimizer;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightEvidenceSource;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategySemanticNormalizer;
import io.github.aililuola.mathproofmesh.strategydiversity.TrustedStrategyPreflightEvidenceSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class StrategyDiversityIssue007BlackBoxSupport {
  static final String PROBLEM_HASH = "graph-problem-hash";
  static final String REFUTED_CLAIM = "Every connected finite graph has a Hamiltonian cycle.";
  static final String VERIFIED_FACT = "Every finite graph has a finite vertex set.";

  private StrategyDiversityIssue007BlackBoxSupport() {}

  static StrategyCard strategy(
      String id,
      String title,
      String mechanism,
      String requiredClaim,
      double estimatedSuccess,
      String tag) {
    return new StrategyCard(
        null,
        "Close the finite graph target through " + mechanism + ".",
        List.of(),
        List.of(),
        List.of(),
        mechanism,
        List.of(
            claim(id + "-required", requiredClaim, "required", "needs_check"),
            claim(id + "-verified", VERIFIED_FACT, "supporting", "verified")),
        0.2d,
        estimatedSuccess,
        List.of("Derive the target from " + requiredClaim + " using " + mechanism + "."),
        "Construct a smallest finite counterexample to " + requiredClaim + ".",
        "Independent presentation: " + mechanism,
        null,
        null,
        List.of(),
        List.of("The graph is finite."),
        id,
        List.of(tag),
        title);
  }

  static CriticalClaim claim(String id, String statement, String necessity, String status) {
    return new CriticalClaim(
        id,
        List.of(),
        "Search all graphs with at most four vertices for a counterexample.",
        necessity,
        null,
        statement,
        status);
  }

  static TypedMemory memoryWithVerifiedCounterexample() {
    TypedMemory memory = new TypedMemory();
    String artifact = "experiment://finite-graph/path-p4";
    String raw = "artifact://finite-graph/path-p4-result";
    MessageEnvelope counterexample =
        new MessageEnvelope(
            List.of(artifact),
            List.of(),
            "P4 is connected and has no Hamiltonian cycle.",
            "",
            null,
            List.of(),
            List.of(),
            EvidenceType.COUNTEREXAMPLE,
            MemoryTier.NEGATIVE,
            "counterexample-path-p4",
            MessageType.COUNTEREXAMPLE,
            1.0d,
            REFUTED_CLAIM,
            PROBLEM_HASH,
            List.of(),
            raw,
            0,
            "1",
            List.of(),
            "independent-computation-replay",
            RouteRole.SKEPTIC,
            "route-counterexample",
            REFUTED_CLAIM,
            List.of(),
            2,
            List.of(),
            1.0d,
            ClaimStatus.REJECTED);
    memory.applyVerifiedCounterexample(
        counterexample,
        VerifiedCounterexampleAuthority.independentReplay(
            true,
            true,
            ComputationEvidenceGate.EvidenceAuthority.REFUTED,
            artifact,
            REFUTED_CLAIM,
            raw,
            List.of()));
    return memory;
  }

  static List<StrategyCard> selectWithIssue007Pipeline(
      List<StrategyCard> strategies,
      int requested,
      TypedMemory memory,
      Set<String> verifiedStatements) {
    StrategyBlueprintCompiler compiler = new StrategyBlueprintCompiler();
    StrategyMechanismAnalyzer analyzer = new StrategyMechanismAnalyzer();
    List<StrategyPreflightEvidenceSource> sources = new ArrayList<>();
    sources.add(
        new TrustedStrategyPreflightEvidenceSource(
            PROBLEM_HASH,
            memory.negativeKnowledgeAdmissionGate(),
            List.of(),
            memory.facts(),
            0));
    sources.add(
        (key, spec) ->
            verifiedStatements.stream()
                .filter(
                    statement ->
                        StrategySemanticNormalizer.normalize(statement)
                            .equals(key.normalizedStatement()))
                .findFirst()
                .map(
                    statement ->
                        new CriticalClaimPreflightEvidence(
                            CriticalClaimPreflightStatus.VERIFIED_SUPPORTED,
                            "trusted-test-claim-store",
                            List.of("claim://verified/" + spec.claim().claimId()),
                            "EXACT_VERIFIED_SUPPORT")));
    StrategyCriticalClaimPreflight preflight =
        new StrategyCriticalClaimPreflight(new CriticalClaimKeyCompiler(), sources);
    CommonModeRiskRegistry commonMode = new CommonModeRiskRegistry();
    Map<String, CandidateParts> parts = new LinkedHashMap<>();
    for (StrategyCard strategy : strategies) {
      ProofControlModels.Strategy control = control(strategy);
      StrategyBlueprintCompiler.Compilation blueprint =
          compiler.compile(PROBLEM_HASH, control, goal());
      var report = preflight.evaluate(PROBLEM_HASH, strategy);
      commonMode.observe(report);
      var signature =
          analyzer.signature(PROBLEM_HASH, "finite-graph-root", strategy, control, blueprint);
      parts.put(
          strategy.strategyId(),
          new CandidateParts(
              strategy, blueprint, signature, analyzer.profile(strategy, blueprint), report));
    }
    Map<String, Long> signatureCounts =
        parts.values().stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    candidate -> candidate.signature().structuralSignatureHash(),
                    LinkedHashMap::new,
                    java.util.stream.Collectors.counting()));
    StrategyFeasibilityCalibrator calibrator =
        new StrategyFeasibilityCalibrator(StrategyDiversityConfig.defaults());
    List<StrategyPortfolioCandidate> candidates =
        parts.values().stream()
            .map(
                candidate ->
                    new StrategyPortfolioCandidate(
                        candidate.strategy(),
                        candidate.blueprint(),
                        candidate.signature(),
                        candidate.profile(),
                        candidate.preflight(),
                        calibrator.calibrate(
                            candidate.strategy(),
                            candidate.blueprint(),
                            candidate.preflight(),
                            1.0d,
                            signatureCounts.get(candidate.signature().structuralSignatureHash())
                                        == 1L
                                ? 1.0d
                                : 0.0d,
                            1.0d,
                            commonMode.groupsFor(candidate.strategy().strategyId()).isEmpty()
                                ? 0.0d
                                : 1.0d)))
            .toList();
    var decision =
        new StrategyPortfolioOptimizer()
            .optimize(
                "black-box-episode",
                candidates,
                new StrategyPortfolioConstraint(requested, 0, 20, Set.of(), Set.of()));
    Set<String> selected = Set.copyOf(decision.selectedStrategyIds());
    return strategies.stream().filter(strategy -> selected.contains(strategy.strategyId())).toList();
  }

  private static ProofControlModels.Strategy control(StrategyCard strategy) {
    return new ProofControlModels.Strategy(
        strategy.strategyId(),
        strategy.title(),
        strategy.coreIdea(),
        strategy.prerequisites(),
        strategy.criticalClaims().stream().map(CriticalClaim::statement).toList(),
        strategy.expectedLemmas(),
        List.of(strategy.falsificationTest()),
        List.of("finite structure", "target relation"),
        "black-box-route");
  }

  private static ProofControlModels.Obligation goal() {
    return new ProofControlModels.Obligation(
        "main-goal",
        "Every finite tree with at least two vertices has at least two leaves.",
        ProofControlModels.ObligationKind.MAIN_GOAL,
        ProofControlModels.ObligationStatus.OPEN,
        List.of(),
        List.of("black-box-route"),
        1.0d,
        1.0d);
  }

  private record CandidateParts(
      StrategyCard strategy,
      StrategyBlueprintCompiler.Compilation blueprint,
      io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismSignature signature,
      io.github.aililuola.mathproofmesh.strategydiversity.StrategyMechanismProfile profile,
      io.github.aililuola.mathproofmesh.strategydiversity.StrategyPreflightReport preflight) {}
}
