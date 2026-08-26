package org.texboobcat.tessellate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.architectury.platform.Mod;
import dev.architectury.platform.Platform;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.texboobcat.tessellate.region.CompatibilityTicks;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Records runtime compatibility failures without uploading raw server logs or stack traces. */
public final class CompatibilityReporter {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build();
    private static final List<String> FRAMEWORK_PACKAGES = List.of(
        "java.", "jdk.", "sun.", "net.minecraft.", "net.fabricmc.", "net.neoforged.",
        "dev.architectury.", "com.mojang.", "org.spongepowered.",
        "org.texboobcat.tessellate.");
    private static final Set<String> FRAMEWORK_MOD_IDS = Set.of(
        "minecraft", "neoforge", "forge", "fabricloader", "quilt_loader");
    @Nullable
    private static volatile MinecraftServer server;

    private CompatibilityReporter() {
    }

    static void setServer(@Nullable MinecraftServer server) {
        CompatibilityReporter.server = server;
    }

    public static List<String> loadedForcedSerialMods() {
        return Config.forceSerialMods.stream().filter(Platform::isModLoaded).toList();
    }

    public static RemoteRules loadRemoteRules() {
        if (Config.compatibilityRulesEndpoint.isBlank()) {
            return RemoteRules.EMPTY;
        }
        try {
            List<InstalledMod> mods = Platform.getMods().stream()
                .map(mod -> new InstalledMod(mod.getModId(), mod.getVersion()))
                .toList();
            String loader = Platform.isFabric() ? "fabric" : "neoforge";
            RemoteRules rules = loadMatchingRules(
                URI.create(Config.compatibilityRulesEndpoint), loader, mods);
            if (Config.compatibilityRulesEndpoint.equals(
                    Config.DEFAULT_COMPATIBILITY_RULES_ENDPOINT)) {
                rules = rules.merge(loadMatchingRules(
                    URI.create(Config.DEFAULT_COMPATIBILITY_FEATURE_RULES_ENDPOINT), loader, mods));
            }
            return rules;
        } catch (IllegalArgumentException failure) {
            LOGGER.warn("tessellate: could not load compatibility rules: {}", failure.toString());
        }
        return RemoteRules.EMPTY;
    }

    public static List<String> loadRemoteEntityExclusions() {
        return loadRemoteRules().mainThreadEntities();
    }

    static RemoteRules loadMatchingRules(URI endpoint, String loader, List<InstalledMod> mods) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
            LOGGER.warn("tessellate: compatibility rules endpoint must use HTTPS");
            return RemoteRules.EMPTY;
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET();
            if (!Config.compatibilityRulesApiKey.isBlank()) {
                request.header("apikey", Config.compatibilityRulesApiKey);
            }
            HttpResponse<String> response = HTTP.send(request.build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("tessellate: compatibility rules endpoint returned HTTP {}",
                    response.statusCode());
                return RemoteRules.EMPTY;
            }
            return matchingRules(response.body(), loader, mods);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            LOGGER.warn("tessellate: interrupted loading compatibility rules");
        } catch (IOException | JsonParseException failure) {
            LOGGER.warn("tessellate: could not load compatibility rules: {}", failure.toString());
        }
        return RemoteRules.EMPTY;
    }

    public static void report(String component, String eventKind, String reason,
                              @Nullable Throwable failure) {
        report(component, eventKind, reason, failure, null);
    }

    public static void report(String component, String eventKind, String reason,
                              @Nullable Throwable failure,
                              @Nullable String fallbackReasonCode) {
        LOGGER.error("tessellate: {}", reason);
        try {
            List<Mod> mods = Platform.getMods().stream()
                .sorted(Comparator.comparing(Mod::getModId))
                .toList();
            Suspect suspect = findSuspect(failure, mods);
            LOGGER.error("tessellate: compatibility candidate: mod={}, frame={}",
                suspect.modId() == null ? "unknown" : suspect.modId(),
                suspect.frame() == null ? "unknown" : suspect.frame());

            Report report = new Report(UUID.randomUUID().toString(),
                Platform.getMod(Tessellate.MODID).getVersion(), Platform.getMinecraftVersion(),
                Platform.isFabric() ? "fabric" : "neoforge", component, eventKind,
                fallbackReasonCode, failureClass(failure),
                CompatibilityTicks.failedEntityTypeId(failure),
                CompatibilityTicks.failedBlockEntityTypeId(failure), suspect,
                mods.stream().map(mod -> new InstalledMod(mod.getModId(), mod.getVersion())).toList());
            notifyPlayers(report, reason);
            if (!Config.compatibilityReportEndpoint.isBlank()) {
                send(report);
            }
        } catch (RuntimeException | LinkageError reportFailure) {
            LOGGER.warn("tessellate: could not create compatibility report", reportFailure);
        }
    }

    private static void notifyPlayers(Report report, String reason) {
        MinecraftServer currentServer = server;
        if (!"serial-fallback".equals(report.eventKind()) || currentServer == null) {
            return;
        }
        String mod = report.suspect().modId() == null ? "unknown"
            : report.suspect().modId() + " " + report.suspect().modVersion();
        Component message = Component.literal("Tessellate fell back to serial execution for "
                + report.component() + ". Suspected mod: " + mod + ". ")
            .withStyle(ChatFormatting.RED)
            .append(Component.literal("[Copy compatibility report]")
                .withStyle(style -> style.withColor(ChatFormatting.YELLOW).withUnderlined(true)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD,
                        issueReport(report, reason)))));
        currentServer.execute(() -> currentServer.getPlayerList()
            .broadcastSystemMessage(message, false));
    }

    static String issueReport(Report report, String reason) {
        return """
            ### Tessellate compatibility report

            Tessellate fell back to serial execution while running `%s`.

            - Suspected mod: `%s` `%s`
            - Minecraft: `%s`
            - Loader: `%s`
            - Tessellate: `%s`
            - Reason code: `%s`
            - Failure: `%s`
            - Suspected frame: `%s`
            - Affected entity type: `%s`
            - Affected block entity type: `%s`
            - Event ID: `%s`

            Reason: %s

            ### Compatibility guidance

            Prefer moving only the unsafe operation: use `TessellateApi.executeOnRegion(level, pos, work)` for world access owned by another region, or `TessellateApi.executeOnMainThread(work)` for main-thread-only state. If the whole type is unsafe, register only the affected type during mod initialization with `TessellateApi.registerMainThreadEntity(type)` or `TessellateApi.registerMainThreadBlockEntity(type)`.

            - GitHub compatibility guide: https://github.com/ColinVaughn/Tessellate#compatibility
            - Tessellate Discord: https://discord.gg/dPY6zmHtr5

            _The suspected mod was detected automatically from the failing stack frame._
            """.formatted(report.component(), unknown(report.suspect().modId()),
                unknown(report.suspect().modVersion()), report.minecraftVersion(), report.loader(),
                report.tessellateVersion(), unknown(report.fallbackReasonCode()),
                unknown(report.failureClass()), unknown(report.suspect().frame()),
                unknown(report.entityTypeId()), unknown(report.blockEntityTypeId()),
                report.eventId(), reason);
    }

    private static String unknown(@Nullable String value) {
        return value == null ? "unknown" : value;
    }

    @Nullable
    private static String failureClass(@Nullable Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String className = current.getClass().getName();
            if (!className.startsWith("org.texboobcat.tessellate.")) {
                return className;
            }
            current = current.getCause();
        }
        return failure == null ? null : failure.getClass().getName();
    }

    private static Suspect findSuspect(@Nullable Throwable failure, List<Mod> mods) {
        List<StackTraceElement> frames = new ArrayList<>();
        if (failure == null) {
            frames.addAll(List.of(Thread.currentThread().getStackTrace()));
        } else {
            Throwable current = failure;
            for (int depth = 0; current != null && depth < 16; depth++) {
                frames.addAll(List.of(current.getStackTrace()));
                current = current.getCause();
            }
        }

        String firstExternal = null;
        for (StackTraceElement frame : frames) {
            if (FRAMEWORK_PACKAGES.stream().anyMatch(frame.getClassName()::startsWith)) {
                continue;
            }
            if (firstExternal == null) {
                firstExternal = frame.toString();
            }
            Mod owner = owningMod(frame.getClassName(), mods);
            if (owner != null && !owner.getModId().equals(Tessellate.MODID)) {
                return new Suspect(owner.getModId(), owner.getVersion(), frame.toString());
            }
        }
        return new Suspect(null, null, firstExternal);
    }

    @Nullable
    static Mod owningMod(String className, List<Mod> mods) {
        String classFile = className.replace('.', '/') + ".class";
        Mod frameworkOwner = null;
        for (Mod mod : mods) {
            try {
                if (mod.getFilePaths().stream().anyMatch(root -> Files.exists(root.resolve(classFile)))) {
                    if (!FRAMEWORK_MOD_IDS.contains(mod.getModId())) {
                        return mod;
                    }
                    frameworkOwner = mod;
                }
            } catch (RuntimeException ignored) {
                // A broken or synthetic mod root must not hide the original server failure.
            }
        }
        return frameworkOwner;
    }

    private static void send(Report report) {
        try {
            URI endpoint = URI.create(Config.compatibilityReportEndpoint);
            if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
                LOGGER.warn("tessellate: compatibility.reportEndpoint must use HTTPS");
                return;
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(report)));
            if (!Config.compatibilityReportApiKey.isBlank()) {
                request.header("apikey", Config.compatibilityReportApiKey);
            }
            HTTP.sendAsync(request.build(), HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, failure) -> {
                    if (failure != null) {
                        LOGGER.warn("tessellate: compatibility report upload failed: {}",
                            failure.toString());
                    } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        LOGGER.warn("tessellate: compatibility report endpoint returned HTTP {}",
                            response.statusCode());
                    }
                });
        } catch (IllegalArgumentException failure) {
            LOGGER.warn("tessellate: invalid compatibility.reportEndpoint");
        }
    }

    static List<String> matchingEntityRules(String json, String loader,
                                            List<InstalledMod> loadedMods) {
        return matchingRules(json, loader, loadedMods).mainThreadEntities();
    }

    static RemoteRules matchingRules(String json, String loader,
                                     List<InstalledMod> loadedMods) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonArray()) {
            throw new JsonParseException("compatibility rules response is not an array");
        }
        Map<String, String> versions = new HashMap<>();
        for (InstalledMod mod : loadedMods) {
            versions.put(mod.id(), mod.version());
        }
        RuleMatches matches = new RuleMatches();
        for (JsonElement element : parsed.getAsJsonArray()) {
            if (element.isJsonObject()) {
                matches.add(element.getAsJsonObject(), loader, versions);
            }
        }
        return matches.toRules();
    }

    @Nullable
    private static String matchingMod(JsonObject rule, String loader,
                                      Map<String, String> versions) {
        String modId = string(rule, "mod_id");
        String modVersion = string(rule, "mod_version");
        String ruleLoader = string(rule, "loader");
        String loadedVersion = versions.get(modId);
        if (loadedVersion == null || !("*".equals(modVersion)
                || loadedVersion.equals(modVersion))
                || !("any".equals(ruleLoader) || loader.equals(ruleLoader))) {
            return null;
        }
        return modId;
    }

    private static void addResourceLocation(TreeSet<String> ids, @Nullable String id) {
        if (id != null && ResourceLocation.tryParse(id) != null) {
            ids.add(id);
        }
    }

    private static final class RuleMatches {

        private final TreeSet<String> entityIds = new TreeSet<>();
        private final TreeSet<String> blockEntityIds = new TreeSet<>();
        private final TreeSet<String> serializeEntityMods = new TreeSet<>();
        private final TreeSet<String> serialSpawningMods = new TreeSet<>();
        private final TreeSet<String> serialRegionMods = new TreeSet<>();

        private void add(JsonObject rule, String loader, Map<String, String> versions) {
            String modId = matchingMod(rule, loader, versions);
            if (modId == null) {
                return;
            }
            String action = string(rule, "action");
            String targetId = string(rule, "target_id");
            if (action == null) {
                action = "main_thread_entity";
                targetId = string(rule, "entity_type_id");
            }
            switch (action) {
                case "main_thread_entity" -> addResourceLocation(this.entityIds, targetId);
                case "main_thread_block_entity" ->
                    addResourceLocation(this.blockEntityIds, targetId);
                case "serialize_entity_ticks" -> this.serializeEntityMods.add(modId);
                case "disable_parallel_spawning" -> this.serialSpawningMods.add(modId);
                case "force_serial_regions" -> this.serialRegionMods.add(modId);
                default -> {
                    // Unknown actions are ignored so newer rule feeds remain backwards-safe.
                }
            }
        }

        private RemoteRules toRules() {
            return new RemoteRules(List.copyOf(this.entityIds), List.copyOf(this.blockEntityIds),
                List.copyOf(this.serializeEntityMods), List.copyOf(this.serialSpawningMods),
                List.copyOf(this.serialRegionMods));
        }
    }

    @Nullable
    private static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    static String toJson(Report report) {
        StringBuilder json = new StringBuilder(512).append('{');
        field(json, "event_id", report.eventId()).append(',');
        field(json, "tessellate_version", report.tessellateVersion()).append(',');
        field(json, "minecraft_version", report.minecraftVersion()).append(',');
        field(json, "loader", report.loader()).append(',');
        field(json, "component", report.component()).append(',');
        field(json, "event_kind", report.eventKind()).append(',');
        field(json, "fallback_reason_code", report.fallbackReasonCode()).append(',');
        field(json, "failure_class", report.failureClass()).append(',');
        field(json, "entity_type_id", report.entityTypeId()).append(',');
        field(json, "block_entity_type_id", report.blockEntityTypeId()).append(',');
        field(json, "suspected_mod_id", report.suspect().modId()).append(',');
        field(json, "suspected_mod_version", report.suspect().modVersion()).append(',');
        field(json, "suspected_frame", report.suspect().frame()).append(',');
        json.append("\"loaded_mods\":[");
        for (int i = 0; i < report.loadedMods().size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            InstalledMod mod = report.loadedMods().get(i);
            json.append('{');
            field(json, "id", mod.id()).append(',');
            field(json, "version", mod.version()).append('}');
        }
        return json.append("]}").toString();
    }

    private static StringBuilder field(StringBuilder json, String name, @Nullable String value) {
        json.append('"').append(name).append("\":");
        if (value == null) {
            return json.append("null");
        }
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            appendEscaped(json, value.charAt(i));
        }
        return json.append('"');
    }

    private static void appendEscaped(StringBuilder json, char c) {
        String escaped = escaped(c);
        if (escaped != null) {
            json.append(escaped);
        } else if (c < 0x20) {
            json.append("\\u").append("%04x".formatted((int) c));
        } else {
            json.append(c);
        }
    }

    @Nullable
    private static String escaped(char c) {
        return switch (c) {
            case '"' -> "\\\"";
            case '\\' -> "\\\\";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            default -> null;
        };
    }

    record InstalledMod(String id, String version) {
    }

    public record RemoteRules(List<String> mainThreadEntities,
                              List<String> mainThreadBlockEntities,
                              List<String> serializeEntityTickMods,
                              List<String> serialNaturalSpawningMods,
                              List<String> serialRegionMods) {

        private static final RemoteRules EMPTY = new RemoteRules(
            List.of(), List.of(), List.of(), List.of(), List.of());

        private RemoteRules merge(RemoteRules other) {
            return new RemoteRules(merge(mainThreadEntities, other.mainThreadEntities),
                merge(mainThreadBlockEntities, other.mainThreadBlockEntities),
                merge(serializeEntityTickMods, other.serializeEntityTickMods),
                merge(serialNaturalSpawningMods, other.serialNaturalSpawningMods),
                merge(serialRegionMods, other.serialRegionMods));
        }

        private static List<String> merge(List<String> first, List<String> second) {
            TreeSet<String> merged = new TreeSet<>(first);
            merged.addAll(second);
            return List.copyOf(merged);
        }
    }

    record Suspect(@Nullable String modId, @Nullable String modVersion,
                   @Nullable String frame) {
    }

    record Report(String eventId, String tessellateVersion, String minecraftVersion,
                  String loader, String component, String eventKind,
                  @Nullable String fallbackReasonCode, @Nullable String failureClass,
                  @Nullable String entityTypeId,
                  @Nullable String blockEntityTypeId, Suspect suspect,
                  List<InstalledMod> loadedMods) {
    }
}
