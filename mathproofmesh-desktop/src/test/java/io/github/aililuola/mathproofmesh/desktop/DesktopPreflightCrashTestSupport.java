package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;

final class DesktopPreflightCrashTestSupport {
  private DesktopPreflightCrashTestSupport() {}

  static StrategyCard safeStrategy(String id) {
    return DesktopStrategyPortfolioTestHarness.withOperation(
        DesktopRegisteredContractPreflightExecutionTest.registeredIntegerStrategy(
            id, "For every integer x in {0,1}, x is at most 1.", "le"),
        MechanismOperationKind.DIRECT);
  }
}
