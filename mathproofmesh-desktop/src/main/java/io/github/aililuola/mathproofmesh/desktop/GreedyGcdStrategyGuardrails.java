package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Deterministic rejection rules for known-invalid greedy-GCD proof dependencies. */
final class GreedyGcdStrategyGuardrails {
  private GreedyGcdStrategyGuardrails() {}

  static boolean violates(StrategyCard strategy) {
    Set<String> tags =
        strategy.tags().stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    String text = strategyText(strategy).toLowerCase(Locale.ROOT);

    boolean finitePrimeSupport =
        tags.contains("prime_finiteness")
            || (tags.contains("prime_factors") && tags.contains("finiteness"))
            || text.contains("finite prime support")
            || text.contains("finitely many prime divisors")
            || text.contains("finite set of prime factors")
            || text.contains("prove p is finite")
            || text.contains("prove that p is finite")
            || text.contains("素数有限性")
            || text.contains("质数有限性")
            || text.contains("限制素数集合")
            || text.contains("限制质数集合")
            || text.contains("证明p有限")
            || text.contains("证明 p 有限");
    boolean universalPrefixPrime =
        text.contains("one prime divides every term")
            || text.contains("one prime divides every prefix")
            || text.contains("a common prime divides all terms")
            || text.contains("同一素数整除所有项")
            || text.contains("同一质数整除所有项")
            || text.contains("存在一个素数整除每一项")
            || text.contains("存在一个质数整除每一项");
    boolean crossModulusContainment =
        text.contains("containment of residue classes for different moduli")
            || text.contains("residue classes for distinct moduli are nested")
            || text.contains("不同模数") && text.contains("包含关系");
    boolean finiteSampleProof =
        (text.contains("finite sample") || text.contains("bounded search"))
                && text.contains("proves eventual")
            || (text.contains("有限样本") || text.contains("有界搜索"))
                && (text.contains("证明最终") || text.contains("证明周期"));
    return finitePrimeSupport
        || universalPrefixPrime
        || crossModulusContainment
        || finiteSampleProof;
  }

  private static String strategyText(StrategyCard strategy) {
    return Stream.of(
            strategy.title(),
            strategy.coreIdea(),
            strategy.bottleneck(),
            strategy.falsificationTest(),
            strategy.independenceBasis(),
            String.join(" ", strategy.prerequisites()),
            String.join(" ", strategy.expectedLemmas()),
            String.join(" ", strategy.tags()))
        .collect(Collectors.joining(" "));
  }
}
