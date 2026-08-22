package org.texboobcat.tessellate.api;

import java.util.Collection;

/** Internal bridge for Tessellate configuration; not part of the compatibility API. */
public final class TessellateApiInternal {

    private TessellateApiInternal() {
    }

    public static void configureMainThreadEntities(Collection<String> entityIds) {
        TessellateApi.configureMainThreadEntities(entityIds);
    }
}
