package org.texboobcat.tessellate;

import dev.architectury.platform.Mod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityReporterTest {

    @AfterEach
    void restoreDefaults() {
        Config.reset();
    }

    @Test
    void blankRulesEndpointFailsOpenWithoutNetworkAccess() {
        Config.configureCompatibilityRules("", "");

        assertEmpty(CompatibilityReporter.loadRemoteRules());
    }

    @Test
    void unreachableRulesEndpointFailsOpen() {
        assertEmpty(CompatibilityReporter.loadMatchingRules(
            URI.create("https://127.0.0.1:1"), "neoforge",
            List.of(new CompatibilityReporter.InstalledMod("example", "1.0"))));
    }

    @Test
    void reportJsonEscapesStringsAndKeepsNullAttribution() {
        var report = new CompatibilityReporter.Report(
            "event", "1.2.4", "1.21.1", "fabric", "region-worker", "serial-fallback",
            "scheduled_tick_cross_region", "java.lang.IllegalStateException", null, null,
            new CompatibilityReporter.Suspect(null, null, "bad.Mod.tick(Mod.java:4)\nnext"),
            List.of(new CompatibilityReporter.InstalledMod("example", "1\"2")));

        assertEquals("{\"event_id\":\"event\",\"tessellate_version\":\"1.2.4\","
            + "\"minecraft_version\":\"1.21.1\",\"loader\":\"fabric\","
            + "\"component\":\"region-worker\",\"event_kind\":\"serial-fallback\","
            + "\"fallback_reason_code\":\"scheduled_tick_cross_region\","
            + "\"failure_class\":\"java.lang.IllegalStateException\","
            + "\"entity_type_id\":null,"
            + "\"block_entity_type_id\":null,"
            + "\"suspected_mod_id\":null,\"suspected_mod_version\":null,"
            + "\"suspected_frame\":\"bad.Mod.tick(Mod.java:4)\\nnext\","
            + "\"loaded_mods\":[{\"id\":\"example\",\"version\":\"1\\\"2\"}]}",
            CompatibilityReporter.toJson(report));
    }

    @Test
    void issueReportIsReadyToPasteIntoTheSuspectedModsRepository() {
        var report = new CompatibilityReporter.Report(
            "event", "1.2.10", "1.21.1", "neoforge", "region-ticking",
            "serial-fallback", "worker_exception", "example.Failure", null, "example:machine",
            new CompatibilityReporter.Suspect("example", "2.0", "example.Mod.tick(Mod.java:4)"),
            List.of(new CompatibilityReporter.InstalledMod("example", "2.0")));

        String issue = CompatibilityReporter.issueReport(report, "region worker failed");

        assertTrue(issue.contains("Suspected mod: `example` `2.0`"));
        assertTrue(issue.contains("Reason code: `worker_exception`"));
        assertTrue(issue.contains("Affected block entity type: `example:machine`"));
        assertTrue(issue.contains("TessellateApi.executeOnRegion(level, pos, work)"));
        assertTrue(issue.contains("https://github.com/ColinVaughn/Tessellate#compatibility"));
        assertTrue(issue.contains("https://discord.gg/dPY6zmHtr5"));
        assertTrue(issue.contains("Event ID: `event`"));
        assertTrue(issue.contains("Reason: region worker failed"));
    }

    @Test
    void rulesMatchOnlyLoadedModVersionAndLoader() {
        String rules = """
            [
              {"mod_id":"example","mod_version":"2.0","loader":"neoforge",
               "entity_type_id":"example:unsafe"},
              {"mod_id":"example","mod_version":"*","loader":"any",
               "entity_type_id":"example:shared"},
              {"mod_id":"example","mod_version":"1.0","loader":"neoforge",
               "entity_type_id":"example:old"},
              {"mod_id":"missing","mod_version":"*","loader":"any",
               "entity_type_id":"missing:unsafe"},
              {"mod_id":"example","mod_version":"*","loader":"fabric",
               "entity_type_id":"example:fabric_only"},
              {"mod_id":"example","mod_version":"2.0","loader":"neoforge",
               "action":"main_thread_block_entity","target_id":"example:machine"},
              {"mod_id":"example","mod_version":"2.0","loader":"any",
               "action":"serialize_entity_ticks","target_id":""},
              {"mod_id":"example","mod_version":"*","loader":"neoforge",
               "action":"disable_parallel_spawning","target_id":""},
              {"mod_id":"example","mod_version":"1.0","loader":"neoforge",
               "action":"force_serial_regions","target_id":""},
              {"mod_id":"example","mod_version":"2.0","loader":"neoforge",
               "action":"force_serial_regions","target_id":""}
            ]
            """;

        var matching = CompatibilityReporter.matchingRules(rules, "neoforge",
            List.of(new CompatibilityReporter.InstalledMod("example", "2.0")));
        assertEquals(List.of("example:shared", "example:unsafe"),
            matching.mainThreadEntities());
        assertEquals(List.of("example:machine"), matching.mainThreadBlockEntities());
        assertEquals(List.of("example"), matching.serializeEntityTickMods());
        assertEquals(List.of("example"), matching.serialNaturalSpawningMods());
        assertEquals(List.of("example"), matching.serialRegionMods());
    }

    @Test
    void sharedHybridRootWinsOverFrameworkOwner(@TempDir Path root) throws Exception {
        String className = "io.papermc.paper.plugin.manager.PaperEventManager";
        Path classFile = root.resolve(className.replace('.', '/') + ".class");
        Files.createDirectories(classFile.getParent());
        Files.createFile(classFile);

        Mod neoforge = mod("neoforge", root);
        Mod youer = mod("youer", root);
        assertEquals("youer", CompatibilityReporter.owningMod(
            className, List.of(neoforge, youer)).getModId());
        assertEquals("neoforge", CompatibilityReporter.owningMod(
            className, List.of(neoforge)).getModId());
    }

    private static Mod mod(String id, Path root) {
        return (Mod) Proxy.newProxyInstance(Mod.class.getClassLoader(),
            new Class<?>[]{Mod.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getModId" -> id;
                case "getFilePaths" -> List.of(root);
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    private static void assertEmpty(CompatibilityReporter.RemoteRules rules) {
        assertTrue(rules.mainThreadEntities().isEmpty());
        assertTrue(rules.mainThreadBlockEntities().isEmpty());
        assertTrue(rules.serializeEntityTickMods().isEmpty());
        assertTrue(rules.serialNaturalSpawningMods().isEmpty());
        assertTrue(rules.serialRegionMods().isEmpty());
    }
}
