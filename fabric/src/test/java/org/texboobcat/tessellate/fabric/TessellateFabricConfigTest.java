package org.texboobcat.tessellate.fabric;

import org.junit.jupiter.api.Test;
import org.texboobcat.tessellate.Config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TessellateFabricConfigTest {

    @Test
    void fillsDefaultsAndCorrectsInvalidOrOutOfRangeValues() {
        com.electronwill.nightconfig.core.Config input =
            com.electronwill.nightconfig.core.Config.inMemory();
        input.set("regions.enabled", "invalid");
        input.set("regions.sectionShift", 99L);
        input.set("regions.mergeRadius", -2L);
        input.set("regions.targetTickMillis", 100.0);
        input.set("regions.workerThreads", -1L);

        Config.Values values = TessellateFabricConfig.read(input);

        assertTrue(values.regionsEnabled());
        assertEquals(8, values.sectionShift());
        assertEquals(1, values.mergeRadius());
        assertEquals(50.0, values.targetTickMillis());
        assertEquals(0, values.workerThreads());
        assertFalse(values.strictGuard());
        assertEquals(false, input.get("guard.strict"));
        assertEquals(25.0, input.get("regions.budgetMillis"));
    }
}
