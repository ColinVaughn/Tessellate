package org.texboobcat.tessellate.api;

import java.util.Collection;

/** Internal bridge for Tessellate configuration; not part of the compatibility API. */
public final class TessellateApiInternal {

    private TessellateApiInternal() {
    }

    public static void configureMainThreadEntities(Collection<String> entityIds) {
        TessellateApi.configureMainThreadEntities(entityIds);
    }

    public static void configureRemoteMainThreadEntities(Collection<String> entityIds) {
        TessellateApi.configureRemoteMainThreadEntities(entityIds);
    }

    public static void configureRemoteMainThreadBlockEntities(Collection<String> blockEntityIds) {
        TessellateApi.configureRemoteMainThreadBlockEntities(blockEntityIds);
    }
}
