package io.github.aililuola.mathproofmesh.proofgraph;

import io.github.aililuola.mathproofmesh.contract.RouteDescriptor;
import io.github.aililuola.mathproofmesh.contract.RouteStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class DuplicateRouteDetector {
  private final double threshold;

  public DuplicateRouteDetector(double threshold) {
    if (threshold < 0.0 || threshold > 1.0) {
      throw new IllegalArgumentException("duplicate route threshold must be in [0, 1]");
    }
    this.threshold = threshold;
  }

  public double similarity(
      RouteDescriptor left,
      RouteDescriptor right,
      Collection<String> leftObligations,
      Collection<String> rightObligations,
      Collection<String> leftFactIds,
      Collection<String> rightFactIds) {
    double mechanism =
        MathTextSimilarity.jaccard(
            left.mechanismSignature(), right.mechanismSignature());
    double obligations =
        MathTextSimilarity.jaccard(leftObligations, rightObligations);
    double facts = MathTextSimilarity.jaccard(leftFactIds, rightFactIds);
    return 0.60 * mechanism + 0.25 * obligations + 0.15 * facts;
  }

  public List<DuplicateRouteMatch> detect(
      Collection<RouteDescriptor> routes,
      Map<String, ? extends Collection<String>> obligationsByRoute,
      Map<String, ? extends Collection<String>> factIdsByRoute,
      Map<String, Double> progressByRoute) {
    List<RouteDescriptor> active =
        routes.stream()
            .filter(
                item ->
                    item.status() == RouteStatus.ACTIVE
                        || item.status() == RouteStatus.REPAIR_ONCE)
            .toList();
    List<DuplicateRouteMatch> result = new ArrayList<>();
    for (int leftIndex = 0; leftIndex < active.size(); leftIndex++) {
      RouteDescriptor left = active.get(leftIndex);
      for (int rightIndex = leftIndex + 1; rightIndex < active.size(); rightIndex++) {
        RouteDescriptor right = active.get(rightIndex);
        double score =
            similarity(
                left,
                right,
                values(obligationsByRoute, left.routeId()),
                values(obligationsByRoute, right.routeId()),
                values(factIdsByRoute, left.routeId()),
                values(factIdsByRoute, right.routeId()));
        if (score < threshold) {
          continue;
        }
        double leftProgress = progressByRoute.getOrDefault(left.routeId(), 0.0);
        double rightProgress = progressByRoute.getOrDefault(right.routeId(), 0.0);
        String survivor =
            leftProgress >= rightProgress ? left.routeId() : right.routeId();
        String source =
            survivor.equals(left.routeId()) ? right.routeId() : left.routeId();
        result.add(
            new DuplicateRouteMatch(
                source,
                survivor,
                score,
                survivor,
                "mechanism, obligation, and fact overlap exceed threshold"));
      }
    }
    return List.copyOf(result);
  }

  private static Collection<String> values(
      Map<String, ? extends Collection<String>> source, String key) {
    Collection<String> values = source.get(key);
    return values == null ? List.of() : values;
  }
}
