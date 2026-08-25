package org.texboobcat.tessellate.fabric;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

public final class TessellateFabricConfig {

    private static final String FILE_NAME = "tessellate-common.toml";

    private TessellateFabricConfig() {
    }

    public static void load() {
        try (CommentedFileConfig file = CommentedFileConfig.builder(
                FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME))
                .sync().preserveInsertionOrder().build()) {
            file.load();
            org.texboobcat.tessellate.Config.apply(read(file));
            org.texboobcat.tessellate.Config.configureCompatibility(
                strings(file, "compatibility.forceSerialMods", List.of()),
                text(file, "compatibility.reportEndpoint", ""),
                text(file, "compatibility.reportApiKey", ""));
            org.texboobcat.tessellate.Config.configureCompatibilityRules(
                text(file, "compatibility.rulesEndpoint",
                    org.texboobcat.tessellate.Config.DEFAULT_COMPATIBILITY_RULES_ENDPOINT),
                text(file, "compatibility.rulesApiKey",
                    org.texboobcat.tessellate.Config.DEFAULT_COMPATIBILITY_RULES_API_KEY));
            file.save();
        }
    }

    static org.texboobcat.tessellate.Config.Values read(
            com.electronwill.nightconfig.core.Config config) {
        return new org.texboobcat.tessellate.Config.Values(
            bool(config, "regions.enabled", true),
            integer(config, "regions.sectionShift", 2, 0, 8),
            integer(config, "regions.mergeRadius", 2, 1, 16),
            integer(config, "regions.verifyIntervalTicks", 200, 0, 24000),
            bool(config, "regions.scopedEntityTicking", true),
            bool(config, "regions.scopedBlockEntityTicking", true),
            bool(config, "regions.scopedScheduledTicks", true),
            bool(config, "regions.scopedBlockEvents", true),
            bool(config, "regions.adaptiveThrottling", true),
            decimal(config, "regions.budgetMillis", 25.0, 1.0, 45.0),
            decimal(config, "regions.targetTickMillis", 45.0, 5.0, 50.0),
            decimal(config, "regions.minThrottleMillis", 2.0, 0.0, 50.0),
            integer(config, "regions.maxTickDivisor", 16, 1, 64),
            bool(config, "regions.shardEntityStorage", true),
            bool(config, "regions.assertShardOwnership", false),
            bool(config, "regions.parallelTicking", true),
            bool(config, "regions.directWorkerChunkReads", true),
            bool(config, "regions.parallelNaturalSpawning", true),
            bool(config, "regions.asyncRegionLoops", true),
            integer(config, "regions.workerThreads", 0, 0, 64),
            bool(config, "regions.threadLocalRandom", true),
            bool(config, "compatibility.serializeEntityTicks", false),
            strings(config, "compatibility.mainThreadEntities",
                org.texboobcat.tessellate.Config.DEFAULT_MAIN_THREAD_ENTITIES),
            bool(config, "guard.diagnoseEntitySectionRaces", false),
            bool(config, "guard.strict", false),
            bool(config, "guard.logRegionChanges", false));
    }

    private static boolean bool(com.electronwill.nightconfig.core.Config config, String path,
                                boolean defaultValue) {
        Object raw = config.getOptional(path).orElse(defaultValue);
        boolean value = raw instanceof Boolean bool ? bool : defaultValue;
        config.set(path, value);
        return value;
    }

    private static int integer(com.electronwill.nightconfig.core.Config config, String path,
                               int defaultValue, int min, int max) {
        Object raw = config.getOptional(path).orElse(defaultValue);
        double number = raw instanceof Number numeric ? numeric.doubleValue() : Double.NaN;
        int value = !Double.isFinite(number) || number != Math.rint(number)
            ? defaultValue
            : (int) Math.max(min, Math.min(max, number));
        config.set(path, value);
        return value;
    }

    private static double decimal(com.electronwill.nightconfig.core.Config config, String path,
                                  double defaultValue, double min, double max) {
        Object raw = config.getOptional(path).orElse(defaultValue);
        double number = raw instanceof Number numeric ? numeric.doubleValue() : Double.NaN;
        double value = Double.isFinite(number) ? Math.max(min, Math.min(max, number)) : defaultValue;
        config.set(path, value);
        return value;
    }

    private static List<String> strings(com.electronwill.nightconfig.core.Config config,
                                        String path, List<String> defaultValue) {
        Object raw = config.getOptional(path).orElse(defaultValue);
        List<String> value = raw instanceof List<?> list
            ? list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(id -> ResourceLocation.tryParse(id) != null)
                .toList()
            : defaultValue;
        config.set(path, value);
        return value;
    }

    private static String text(com.electronwill.nightconfig.core.Config config, String path,
                               String defaultValue) {
        Object raw = config.getOptional(path).orElse(defaultValue);
        String value = raw instanceof String string ? string.strip() : defaultValue;
        config.set(path, value);
        return value;
    }
}
