package io.github.aililuola.mathproofmesh.server;

import io.github.aililuola.mathproofmesh.core.CoreModuleMarker;

/**
 * Marker that makes the allowed server-to-core dependency explicit.
 */
public final class ServerModuleMarker {
    public static final String NAME = "server";
    public static final String DEPENDS_ON = CoreModuleMarker.NAME;

    private ServerModuleMarker() {
    }
}
