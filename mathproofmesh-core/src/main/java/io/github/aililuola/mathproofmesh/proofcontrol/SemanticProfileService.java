package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.SemanticInvariantAudit;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative bilingual semantic invariant extraction and comparison. */
@SuppressFBWarnings(
    value = "IMPROPER_UNICODE",
    justification =
        "NFKC and Locale.ROOT canonicalization are required for conservative bilingual audits")
public final class SemanticProfileService {
  private static final Pattern CJK = Pattern.compile("[\\u3400-\\u9fff]");
  private static final Pattern LATIN = Pattern.compile("\\b[A-Za-z]{2,}\\b");

  private static final Map<String, List<Pattern>> CONCEPTS =
      patterns(
          "adjacency", "相邻|邻接", "\\b(?:adjacent|neighbou?ring|neighbou?rs?)\\b",
          "object", "对象|元素", "\\b(?:objects?|elements?)\\b",
          "distance", "距离|间距", "\\b(?:distance|distances|gap|gaps)\\b",
          "boundedness", "有界|无界|界限", "\\b(?:bounded|unbounded|finite bound)\\b",
          "representation", "表示|表象", "\\b(?:representation|representations|represent)\\b",
          "mapping", "映射|函数", "\\b(?:map|maps|mapping|function|functions)\\b",
          "domain", "定义域", "\\bdomains?\\b",
          "order", "次序|顺序|序关系", "\\b(?:order|ordering|ordered)\\b",
          "preservation", "保持|不变|守恒", "\\b(?:preserve|preserved|invariant)\\b",
          "continuity", "连续", "\\bcontinu(?:ous|ity)\\b",
          "convergence", "收敛", "\\bconver(?:ge|ges|gence|gent)\\b",
          "periodicity", "周期", "\\bperiodic(?:ity)?\\b",
          "monotonicity", "单调|递增|递减", "\\b(?:monotone|monotonic|increasing|decreasing)\\b",
          "sequence", "数列|序列", "\\bsequences?\\b",
          "relation", "关系", "\\brelations?\\b");
  private static final Map<String, List<Pattern>> TASKS =
      patterns(
          "disprove", "证伪|否证|反驳|举反例", "\\b(?:disprove|refute|falsify)\\b",
          "prove", "证明|证实|论证", "\\b(?:prove|show|demonstrate|establish)\\b",
          "compute", "计算|求值", "\\b(?:compute|calculate|evaluate)\\b",
          "find", "求出|寻找|找出", "\\b(?:find|locate)\\b",
          "determine", "确定|判定", "\\bdetermine\\b",
          "classify", "分类", "\\bclassify\\b",
          "construct", "构造", "\\bconstruct\\b");
  private static final Map<String, List<Pattern>> DOMAINS =
      patterns(
          "positive_integer", "正整数", "\\bpositive integers?\\b",
          "nonnegative_integer", "非负整数", "\\bnonnegative integers?\\b",
          "natural_number", "自然数|\\\\mathbb\\s*\\{\\s*n\\s*}", "\\bnatural (?:number|numbers|integer|integers)\\b|\\\\mathbb\\s*\\{\\s*n\\s*}",
          "integer", "整数|\\\\mathbb\\s*\\{\\s*z\\s*}", "\\bintegers?\\b|\\\\mathbb\\s*\\{\\s*z\\s*}",
          "rational_number", "有理数", "\\brational (?:number|numbers)\\b",
          "real_number", "实数|\\\\mathbb\\s*\\{\\s*r\\s*}", "\\breal (?:number|numbers)\\b|\\\\mathbb\\s*\\{\\s*r\\s*}",
          "complex_number", "复数", "\\bcomplex (?:number|numbers)\\b");
  private static final Map<String, List<Pattern>> RELATIONS =
      patterns(
          "equivalence", "当且仅当|等价于", "\\bif and only if\\b|\\biff\\b|\\bequivalent to\\b",
          "implication", "(?:若|如果|假设).{0,300}(?:则|那么|就有|必有)", "\\bif\\b.{0,300}\\bthen\\b",
          "only_if", "仅当", "\\bonly if\\b");

  public record Profile(
      String language,
      Set<String> concepts,
      Set<String> taskIntents,
      Set<String> polarities,
      List<String> quantifiers,
      Set<String> domains,
      Set<String> logicalRelations,
      List<String> mathFragments) {
    public Profile {
      concepts = Set.copyOf(concepts);
      taskIntents = Set.copyOf(taskIntents);
      polarities = Set.copyOf(polarities);
      quantifiers = List.copyOf(quantifiers);
      domains = Set.copyOf(domains);
      logicalRelations = Set.copyOf(logicalRelations);
      mathFragments = List.copyOf(mathFragments);
    }
  }

  public Profile extract(String value) {
    String text =
        Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
    return new Profile(
        CJK.matcher(text).find() ? "zh" : LATIN.matcher(text).find() ? "en" : "unknown",
        labels(text, CONCEPTS),
        labels(text, TASKS),
        polarity(text),
        quantifiers(text),
        labels(text, DOMAINS),
        labels(text, RELATIONS),
        ProblemSemanticViewService.mathBlocks(text));
  }

  public List<SemanticInvariantAudit> audit(String source, String translation) {
    Profile left = extract(source);
    Profile right = extract(translation);
    List<SemanticInvariantAudit> findings = new ArrayList<>();
    findings.add(compare("task_intent", sorted(left.taskIntents()), sorted(right.taskIntents())));
    findings.add(compare("polarity", sorted(left.polarities()), sorted(right.polarities())));
    findings.add(compare("quantifier", left.quantifiers(), right.quantifiers()));
    findings.add(compare("domain", sorted(left.domains()), sorted(right.domains())));
    findings.add(
        compare(
            "logical_relation",
            sorted(left.logicalRelations()),
            sorted(right.logicalRelations())));
    boolean directional =
        left.logicalRelations().contains("implication")
            || left.logicalRelations().contains("only_if")
            || right.logicalRelations().contains("implication")
            || right.logicalRelations().contains("only_if");
    findings.add(
        directional
            ? compare("logical_relation_order", left.mathFragments(), right.mathFragments())
            : new SemanticInvariantAudit(
                "no directional logical relation was detected",
                "logical_relation_order",
                left.mathFragments(),
                "not_applicable",
                right.mathFragments()));

    Set<String> shared = new LinkedHashSet<>(left.concepts());
    shared.retainAll(right.concepts());
    if (left.concepts().size() < 2 && right.concepts().size() < 2) {
      findings.add(
          new SemanticInvariantAudit(
              "too few controlled-vocabulary concepts for comparison",
              "semantic_concepts",
              sorted(left.concepts()),
              "not_applicable",
              sorted(right.concepts())));
    } else {
      double leftCoverage = shared.size() / (double) Math.max(1, left.concepts().size());
      double rightCoverage = shared.size() / (double) Math.max(1, right.concepts().size());
      boolean passed =
          shared.size() >= 2 && Math.min(leftCoverage, rightCoverage) >= 0.67d;
      findings.add(
          new SemanticInvariantAudit(
              passed
                  ? "controlled-vocabulary concepts agree"
                  : "controlled-vocabulary concept coverage is insufficient",
              "semantic_concepts",
              sorted(left.concepts()),
              passed ? "pass" : "fail",
              sorted(right.concepts())));
    }
    return List.copyOf(findings);
  }

  public boolean conservativelyMatchesAcrossLanguages(String left, String right) {
    Profile first = extract(left);
    Profile second = extract(right);
    Set<String> languages = new LinkedHashSet<>();
    languages.add(first.language());
    languages.add(second.language());
    if (!languages.equals(Set.of("zh", "en"))) {
      return false;
    }
    if (audit(left, right).stream()
        .anyMatch(value -> "fail".equals(value.status())
            && !"semantic_concepts".equals(value.invariant()))) {
      return false;
    }
    Set<String> shared = new LinkedHashSet<>(first.concepts());
    shared.retainAll(second.concepts());
    return shared.size() >= 2
        && shared.size() / (double) Math.max(1, first.concepts().size()) >= 0.67d
        && shared.size() / (double) Math.max(1, second.concepts().size()) >= 0.67d;
  }

  private static SemanticInvariantAudit compare(
      String invariant, List<String> source, List<String> target) {
    if (source.isEmpty() && target.isEmpty()) {
      return new SemanticInvariantAudit(
          invariant + " was not explicitly detected",
          invariant,
          source,
          "not_applicable",
          target);
    }
    boolean passed = source.equals(target);
    return new SemanticInvariantAudit(
        passed ? invariant + " agrees" : invariant + " differs between source and translation",
        invariant,
        source,
        passed ? "pass" : "fail",
        target);
  }

  private static Set<String> labels(String text, Map<String, List<Pattern>> patterns) {
    Set<String> labels = new LinkedHashSet<>();
    for (Map.Entry<String, List<Pattern>> entry : patterns.entrySet()) {
      if (entry.getValue().stream().anyMatch(pattern -> pattern.matcher(text).find())) {
        labels.add(entry.getKey());
      }
    }
    return Set.copyOf(labels);
  }

  private static Set<String> polarity(String text) {
    String masked =
        text.replaceAll("不超过|不大于|不少于|不小于|非负|非零", " ");
    return Pattern.compile(
            "不存在|无界|不成立|不能|不可|\\b(?:not|no|never|cannot|without|unbounded|false)\\b|\\bdoes not\\b")
        .matcher(masked)
        .find()
        ? Set.of("negative")
        : Set.of();
  }

  private static List<String> quantifiers(String text) {
    List<String> result = new ArrayList<>();
    addIf(result, "exists_unique", text, "存在唯一", "唯一存在", "there exists a unique", "exactly one");
    addIf(result, "at_least", text, "至少", "at least");
    addIf(result, "at_most", text, "至多", "at most");
    addIf(result, "universal", text, "每个", "任意", "所有", "every", "each", "for all");
    addIf(result, "existential", text, "存在", "某个", "there exists", "there is", "some");
    return List.copyOf(result);
  }

  private static void addIf(
      List<String> target, String label, String text, String... markers) {
    for (String marker : markers) {
      if (text.contains(marker)) {
        target.add(label);
        return;
      }
    }
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().sorted().toList();
  }

  private static Map<String, List<Pattern>> patterns(String... values) {
    if (values.length % 3 != 0) {
      throw new IllegalArgumentException("pattern triples required");
    }
    Map<String, List<Pattern>> result = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 3) {
      result.put(
          values[index],
          List.of(
              Pattern.compile(values[index + 1], Pattern.CASE_INSENSITIVE),
              Pattern.compile(values[index + 2], Pattern.CASE_INSENSITIVE)));
    }
    return Map.copyOf(result);
  }
}
