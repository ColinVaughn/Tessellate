#!/usr/bin/env python3
"""Parse `/optimal regions` output for the verification scripts.

The command returns one block per dimension followed by its regions:

    minecraft:overworld: 3 region(s), 137 ticking chunk(s), update 0.000 ms, verify 0.5 ms (peak 1.0, drift 0)
      sectionShift=2 (4x4 chunks), mergeRadius=2, merges=0, splits=0
      entities: scoped=true, 0.058 ms total, 0 orphan(s), 0 misplaced (repaired)
      blockentities: scoped=true, 0.016 ms grouping, 0 orphan(s)
      region#4: 4 section(s), 25 entity(s) 0.056 ms, 1 blockentity(s) 0.002 ms, near [576, 576]

Use `parse` or `overworld` so callers do not accidentally read another dimension's values.
"""

import re

DIMENSION_RE = re.compile(
    r"^(?P<dim>minecraft:\w+): (?P<regions>\d+) region\(s\), "
    r"(?P<chunks>\d+) ticking chunk\(s\), update (?P<update>[\d.]+) ms, "
    r"verify (?P<verify>[\d.]+) ms \(peak (?P<peak>[\d.]+), drift (?P<drift>\d+)\)")

CONFIG_RE = re.compile(
    r"sectionShift=(?P<shift>\d+) \(\d+x\d+ chunks\), mergeRadius=(?P<radius>\d+), "
    r"merges=(?P<merges>\d+), splits=(?P<splits>\d+)")

ENTITIES_RE = re.compile(
    r"entities: scoped=(?P<scoped>true|false), (?P<ms>[\d.]+) ms total, "
    r"(?P<orphans>\d+) orphan\(s\), (?P<misplaced>\d+) misplaced")

BLOCK_ENTITIES_RE = re.compile(
    r"blockentities: scoped=(?P<scoped>true|false), (?P<ms>[\d.]+) ms grouping, "
    r"(?P<orphans>\d+) orphan\(s\)")

REGION_RE = re.compile(
    r"region#(?P<id>\d+): (?P<sections>\d+) section\(s\), "
    r"(?P<entities>\d+) entity\(s\) (?P<entity_ms>[\d.]+) ms, "
    r"(?P<blockentities>\d+) blockentity\(s\) (?P<be_ms>[\d.]+) ms, "
    r"1/(?P<divisor>\d+) rate \((?P<tps>[\d.]+) TPS\), "
    r"near \[(?P<x>-?\d+), (?P<z>-?\d+)\]")

THROTTLE_RE = re.compile(
    r"throttle: (?P<state>on|off), budget floor (?P<budget>[\d.]+) ms, "
    r"(?P<throttled>\d+) region\(s\) throttled")


class ParseError(Exception):
    pass


class Dimension:
    """Values reported for one dimension."""

    def __init__(self, name, line):
        self.name = name
        self.line = line
        self.region_count = 0
        self.chunks = 0
        self.update_ms = 0.0
        self.verify_ms = 0.0
        self.peak_ms = 0.0
        self.drift = 0
        self.section_shift = 0
        self.merge_radius = 0
        self.merges = 0
        self.splits = 0
        self.entity_scoped = None
        self.entity_ms = 0.0
        self.entity_orphans = 0
        self.entity_misplaced = 0
        self.be_scoped = None
        self.be_ms = 0.0
        self.be_orphans = 0
        self.throttling = None
        self.budget_ms = 0.0
        self.throttled = 0
        self.regions = {}

    def region_near(self, x, z, tolerance=256):
        """Return the region anchored near (x, z), if any."""
        for rid, info in self.regions.items():
            if abs(info["x"] - x) <= tolerance and abs(info["z"] - z) <= tolerance:
                return rid, info
        return None, None

    def total_entities(self):
        return sum(info["entities"] for info in self.regions.values())

    def total_block_entities(self):
        return sum(info["blockentities"] for info in self.regions.values())


def parse(text):
    """Parse dimension blocks by name."""
    dimensions = {}
    current = None

    for line in text.splitlines():
        header = DIMENSION_RE.search(line)
        if header:
            current = Dimension(header.group("dim"), line.strip())
            current.region_count = int(header.group("regions"))
            current.chunks = int(header.group("chunks"))
            current.update_ms = float(header.group("update"))
            current.verify_ms = float(header.group("verify"))
            current.peak_ms = float(header.group("peak"))
            current.drift = int(header.group("drift"))
            dimensions[current.name] = current
            continue

        if current is None:
            continue

        cfg = CONFIG_RE.search(line)
        if cfg:
            current.section_shift = int(cfg.group("shift"))
            current.merge_radius = int(cfg.group("radius"))
            current.merges = int(cfg.group("merges"))
            current.splits = int(cfg.group("splits"))
            continue

        ent = ENTITIES_RE.search(line)
        if ent:
            current.entity_scoped = ent.group("scoped") == "true"
            current.entity_ms = float(ent.group("ms"))
            current.entity_orphans = int(ent.group("orphans"))
            current.entity_misplaced = int(ent.group("misplaced"))
            continue

        thr = THROTTLE_RE.search(line)
        if thr:
            current.throttling = thr.group("state") == "on"
            current.budget_ms = float(thr.group("budget"))
            current.throttled = int(thr.group("throttled"))
            continue

        be = BLOCK_ENTITIES_RE.search(line)
        if be:
            current.be_scoped = be.group("scoped") == "true"
            current.be_ms = float(be.group("ms"))
            current.be_orphans = int(be.group("orphans"))
            continue

        reg = REGION_RE.search(line)
        if reg:
            current.regions[int(reg.group("id"))] = {
                "sections": int(reg.group("sections")),
                "entities": int(reg.group("entities")),
                "entity_ms": float(reg.group("entity_ms")),
                "blockentities": int(reg.group("blockentities")),
                "be_ms": float(reg.group("be_ms")),
                "divisor": int(reg.group("divisor")),
                "tps": float(reg.group("tps")),
                "x": int(reg.group("x")),
                "z": int(reg.group("z")),
            }

    if not dimensions:
        raise ParseError(
            "no dimension block found in `/optimal regions` output. The command's format has "
            "probably changed and tools/regions.py needs updating. Output was:\n" + text)
    return dimensions


def overworld(rcon):
    """Query and parse the overworld block."""
    text = rcon.command("optimal regions")
    dims = parse(text)
    if "minecraft:overworld" not in dims:
        raise ParseError("no overworld in output:\n" + text)
    return dims["minecraft:overworld"]
