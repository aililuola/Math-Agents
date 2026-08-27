package io.github.aililuola.mathproofmesh.core;

import io.github.aililuola.mathproofmesh.contract.ContractModuleMarker;

/**
 * Marker that makes the allowed core-to-contracts dependency explicit.
 */
public final class CoreModuleMarker {
    public static final String NAME = "core";
    public static final String DEPENDS_ON = ContractModuleMarker.NAME;

    private CoreModuleMarker() {
    }
}
