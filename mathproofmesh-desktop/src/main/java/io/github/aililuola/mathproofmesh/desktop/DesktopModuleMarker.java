package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.server.ServerModuleMarker;

/**
 * Marker that makes the allowed desktop-to-server dependency explicit.
 */
public final class DesktopModuleMarker {
    public static final String NAME = "desktop";
    public static final String DEPENDS_ON = ServerModuleMarker.NAME;

    private DesktopModuleMarker() {
    }
}
