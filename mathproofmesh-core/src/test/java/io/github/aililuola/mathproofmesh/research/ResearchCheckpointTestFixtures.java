package io.github.aililuola.mathproofmesh.research;

import io.github.aililuola.mathproofmesh.contract.ResearchCheckpointFrame;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDraft;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import java.util.List;

final class ResearchCheckpointTestFixtures {
  private ResearchCheckpointTestFixtures() {}

  static ResearchFindingDraft finding(ResearchFindingKind kind, String statement) {
    return new ResearchFindingDraft(
        kind,
        statement,
        "A bounded public justification.",
        List.of("fixed assumptions"),
        List.of("current route"),
        kind == ResearchFindingKind.COUNTEREXAMPLE_CANDIDATE ? "obligation-1" : null,
        null,
        null,
        null,
        null);
  }

  static ResearchCheckpointFrame frame(int sequence, ResearchFindingDraft... findings) {
    return new ResearchCheckpointFrame(
        sequence, "A concise public checkpoint.", List.of(findings));
  }

  static String marker(ResearchCheckpointFrame frame) {
    return ResearchCheckpointFrameParser.BEGIN_MARKER
        + "\n"
        + io.github.aililuola.mathproofmesh.contract.ContractObjectMapper.write(frame)
        + "\n"
        + ResearchCheckpointFrameParser.END_MARKER;
  }
}
