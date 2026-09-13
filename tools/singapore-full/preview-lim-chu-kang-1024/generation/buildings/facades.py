"""Deterministic, explicitly approximate OSM-to-Minecraft facade palettes.

Mapped means a source tag was translated into a Minecraft block; it does not
mean survey/photo-exact architecture. Missing material/colour and every window
pattern are estimates. Global integer block coordinates keep chunk boundaries
from changing the pattern. This module performs no network or world I/O.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
import re
from typing import Mapping

# Coarse visual approximations, not physical material equivalence.
_MATERIALS = {
    "brick": "bricks", "bricks": "bricks",
    "concrete": "light_gray_concrete", "reinforced_concrete": "light_gray_concrete",
    "cement": "light_gray_concrete", "plaster": "white_concrete",
    "render": "white_concrete", "stucco": "white_concrete",
    "stone": "stone_bricks", "sandstone": "sandstone",
    "limestone": "calcite", "marble": "quartz_block",
    "granite": "polished_granite", "slate": "polished_deepslate",
    "glass": "light_blue_stained_glass", "steel": "iron_block",
    "metal": "iron_block", "aluminium": "iron_block", "aluminum": "iron_block",
    "wood": "oak_planks", "timber": "oak_planks",
    "clay": "terracotta", "ceramic": "terracotta",
    "roof_tiles": "bricks", "tiles": "bricks", "tile": "bricks",
    "copper": "copper_block", "zinc": "iron_block",
    "asphalt": "gray_concrete", "bitumen": "black_concrete",
}
# One neutral family per broad use; a stable seed picks among its estimates.
_FALLBACKS = {
    "office": ("light_gray_concrete", "gray_concrete", "white_concrete"),
    "commercial": ("light_gray_concrete", "white_concrete", "smooth_stone"),
    "retail": ("white_concrete", "light_gray_concrete", "sandstone"),
    "industrial": ("light_gray_concrete", "stone_bricks", "gray_concrete"),
    "warehouse": ("light_gray_concrete", "gray_concrete", "stone_bricks"),
    "apartments": ("white_concrete", "light_gray_concrete", "smooth_sandstone"),
    "residential": ("white_concrete", "light_gray_concrete", "smooth_sandstone"),
    "house": ("white_concrete", "bricks", "smooth_sandstone"),
    "terrace": ("white_concrete", "bricks", "smooth_sandstone"),
}
_COLOURS = {
    "white": (207, 213, 214), "orange": (224, 97, 0),
    "magenta": (169, 48, 159), "light_blue": (36, 137, 199),
    "yellow": (241, 175, 21), "lime": (94, 168, 24),
    "pink": (214, 101, 143), "gray": (54, 57, 61),
    "light_gray": (125, 125, 115), "cyan": (21, 119, 136),
    "purple": (100, 31, 156), "blue": (44, 46, 143),
    "brown": (96, 59, 31), "green": (73, 91, 36),
    "red": (142, 32, 32), "black": (8, 10, 15),
}
# Primary architectural references; consulted for coarse typology only.
# No imagery or copyrighted facade assets are included or redistributed.
_STYLE_REFERENCES = {
    "housing": "https://www.hdb.gov.sg/-/media/doc/PLG/monograph-2-29-dec-2014.pdf",
    "shophouse": "https://www.ura.gov.sg/conservation/conservation-resources/understanding-the-shophouse/",
    "landed": "https://www.ura.gov.sg/conservation/conservation-areas/",
}
_ALIASES = {
    "grey": "gray", "light_grey": "light_gray",
    "dark_grey": "gray", "dark_gray": "gray",
    "beige": (216, 202, 163), "cream": (242, 230, 184),
    "silver": (192, 192, 192), "terracotta": (177, 89, 64),
}


@dataclass(frozen=True)
class Palette:
    feature_id: str
    seed: int
    wall_block: str
    roof_block: str
    trim_block: str
    window_block: str
    wall_provenance: str
    roof_provenance: str
    wall_material_provenance: str
    wall_colour_provenance: str
    roof_material_provenance: str
    roof_colour_provenance: str
    source_tags: tuple[tuple[str, str], ...]
    estimates: tuple[str, ...]
    detail_provenance: str = "estimated"
    detail_pattern: str = "global-grid-v2"
    style_class: str = "neutral_estimate"
    style_provenance: str = "estimated"
    floor_period: int = 4
    window_period: int = 3
    window_width: int = 1
    style_reference_urls: tuple[str, ...] = ()

    def to_dict(self) -> dict:
        """Return JSON-ready values, retaining source tags and estimates."""
        result = asdict(self)
        result["source_tags"] = dict(self.source_tags)
        result["estimates"] = list(self.estimates)
        result["style_reference_urls"] = list(self.style_reference_urls)
        return result


def _normalise(value: str) -> str:
    return value.strip().lower().replace(" ", "_").replace("-", "_")


def _colour(value: str) -> str | None:
    """Nearest concrete colour for hex RGB; known colour names use that family."""
    normalized = _normalise(value)
    if normalized in _COLOURS:
        return normalized
    alias = _ALIASES.get(normalized)
    if isinstance(alias, str):
        return alias
    if isinstance(alias, tuple):
        rgb = alias
    elif re.fullmatch(r"#[0-9a-f]{3}", normalized):
        rgb = tuple(int(c * 2, 16) for c in normalized[1:])
    elif re.fullmatch(r"#[0-9a-f]{6}", normalized):
        rgb = tuple(int(normalized[i:i + 2], 16) for i in (1, 3, 5))
    else:
        return None
    # Stable tie-break by name, independent of dictionary insertion order.
    return min(_COLOURS, key=lambda name: (
        sum((a - b) ** 2 for a, b in zip(rgb, _COLOURS[name])), name
    ))


def _surface(tags: Mapping[str, str], prefix: str, default: str) -> tuple:
    material_raw = tags.get(prefix + ":material", "")
    colour_raw = (tags.get(prefix + ":colour") or tags.get(prefix + ":color", ""))
    materials = [_normalise(v) for v in material_raw.split(";") if v.strip()]
    material = next((v for v in materials if v in _MATERIALS), None)
    colour = _colour(colour_raw) if colour_raw else None
    material_provenance = "mapped" if material else "estimated"
    colour_provenance = "mapped" if colour else "estimated"
    estimates = []
    if not material:
        estimates.append(prefix + " material: " + (
            "unsupported source tag; use fallback" if material_raw
            else "missing source tag; use fallback"
        ))
    elif len(materials) > 1:
        estimates.append(prefix + " material mixture simplified to " + material)
    if not colour:
        estimates.append(prefix + " colour: " + (
            "unsupported source tag; use block default" if colour_raw
            else "missing source tag; use block default"
        ))
    block = _MATERIALS[material] if material else default
    if colour:
        if material == "glass":
            block = colour + "_stained_glass"
        elif material in {"clay", "ceramic", "brick", "bricks", "roof_tiles", "tiles", "tile"}:
            block = colour + "_terracotta"
        else:
            block = colour + "_concrete"
        if material and material not in {"glass", "concrete", "reinforced_concrete", "cement"}:
            estimates.append(prefix + " colour prioritized over physical material in block approximation")
    provenance = "mapped" if material or colour else "estimated"
    return ("minecraft:" + block, provenance, material_provenance,
            colour_provenance, estimates)


def _style(tags: Mapping[str, str]) -> tuple[str, int, int, int, tuple[str, ...]]:
    """Infer coarse styling only; never infer a specific building's identity."""
    kind = _normalise(tags.get("building", "yes"))
    architecture = _normalise(tags.get("building:architecture", ""))
    try:
        levels = float(tags.get("building:levels", "0"))
        if not 0 < levels < 1000:
            levels = 0
    except ValueError:
        levels = 0
    if kind == "shophouse" or "shophouse" in architecture:
        return ("shophouse_inspired", 4, 4, 1, (_STYLE_REFERENCES["shophouse"],))
    if kind in {"retail", "commercial"} and 1 < levels <= 3:
        return ("lowrise_shopfront_inspired", 4, 4, 1, (_STYLE_REFERENCES["shophouse"],))
    if kind in {"apartments", "residential"} and (levels >= 4 or kind == "apartments"):
        if "point" in architecture:
            style = "housing_point_inspired"
        elif "slab" in architecture:
            style = "housing_slab_inspired"
        else:
            style = "housing_flats_inspired"
        return (style, 3, 4, 2, (_STYLE_REFERENCES["housing"],))
    if kind in {"house", "bungalow", "detached", "semidetached_house", "terrace"}:
        return ("landed_home_inspired", 3, 4, 1, (_STYLE_REFERENCES["landed"],))
    if kind in {"industrial", "warehouse"}:
        return ("industrial_estimate", 5, 6, 1, ())
    if kind == "office":
        return ("office_glazing_estimate", 4, 4, 3, ())
    if kind == "commercial":
        return ("commercial_estimate", 4, 4, 2, ())
    return ("neutral_estimate", 4, 3, 1, ())


def choose_palette(feature_id: str | int, tags: Mapping[str, str]) -> Palette:
    """Choose deterministic materials from OSM tags without assuming photos.

    Keep the same feature_id (prefer OSM type/id, e.g. way/123) for all tiles.
    Non-string tag values and an empty feature id are rejected.
    """
    if isinstance(feature_id, bool) or not isinstance(feature_id, (str, int)):
        raise TypeError("feature_id must be a stable string or integer")
    identity = str(feature_id).strip()
    if not identity:
        raise ValueError("feature_id must not be empty")
    if not isinstance(tags, Mapping) or any(
        not isinstance(k, str) or not isinstance(v, str) for k, v in tags.items()
    ):
        raise TypeError("tags must map strings to strings")
    seed = int.from_bytes(hashlib.sha256(identity.encode("utf-8")).digest()[:8], "big")
    kind = _normalise(tags.get("building", "yes"))
    family = _FALLBACKS.get(kind, ("light_gray_concrete", "white_concrete", "smooth_stone"))
    style = _style(tags)
    wall = _surface(tags, "building", family[seed % len(family)])
    roof = _surface(tags, "roof", "gray_concrete")
    estimates = tuple(wall[4] + roof[4] + [
        "window positions, floor bands and trim are estimated global-grid details",
        "style class is inferred from coarse tags, not a surveyed facade or HDB identity",
        "Minecraft blocks approximate source materials and colours; no photo-exact claim",
    ])
    relevant = tuple(sorted((key, value) for key, value in tags.items()
        if key in {"building", "building:material", "building:colour", "building:color",
                   "roof:material", "roof:colour", "roof:color",
                   "building:levels", "building:architecture"}))
    return Palette(
        identity, seed, wall[0], roof[0], "minecraft:smooth_stone",
        "minecraft:light_blue_stained_glass", wall[1], roof[1],
        wall[2], wall[3], roof[2], roof[3], relevant, estimates,
        style_class=style[0], floor_period=style[1], window_period=style[2],
        window_width=style[3], style_reference_urls=style[4],
    )


def wall_material(palette: Palette, global_x: int, y: int, global_z: int,
                  *, face_axis: str | None = None) -> str:
    """Return an estimated facade cell using absolute block coordinates.

    Integer coordinates are required to prevent accidental float truncation.
    The caller chooses exposed wall cells; this function never changes geometry.
    Coarse inferred styles set the global band/window cadence.
    face_axis is the wall normal ('x' or 'z'); the other horizontal axis
    determines windows. Omit it for an estimated diagonal grid on unknown faces.
    """
    for coordinate in (global_x, y, global_z):
        if isinstance(coordinate, bool) or not isinstance(coordinate, int):
            raise TypeError("wall coordinates must be integer global block coordinates")
    if face_axis not in (None, "x", "z"):
        raise ValueError("face_axis must be None, 'x' or 'z'")
    phase_y = palette.seed % palette.floor_period
    if (y + phase_y) % palette.floor_period == 0:
        return palette.trim_block
    # Caller-supplied normals avoid all-glass/solid strips on a fixed plane.
    # Unknown normals use a global diagonal grid, never a tile-local origin.
    horizontal = global_z if face_axis == "x" else global_x
    if face_axis is None:
        horizontal = global_x + global_z
    phase = (palette.seed >> 8) % palette.window_period
    if 0 < (horizontal + phase) % palette.window_period <= palette.window_width:
        return palette.window_block
    return palette.wall_block
