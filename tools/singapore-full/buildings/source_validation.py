"""Separate fatal source contracts from quarantinable building tag failures."""
from __future__ import annotations

from collections.abc import Mapping

from footprints import FootprintError, normalize_feature


class SourceContractError(ValueError):
    """Corrupt structure, geometry, CRS or references cannot become estimates."""


class FeatureTagError(ValueError):
    """Identifiable valid geometry has malformed per-feature semantic tags."""


def source_identity(feature):
    if not isinstance(feature, Mapping) or feature.get("type") != "Feature":
        raise SourceContractError("Source entry must be a GeoJSON Feature object")
    properties=feature.get("properties")
    if properties is None:
        properties={}
    if not isinstance(properties, Mapping):
        raise SourceContractError("Feature properties must be an object or null")
    identity=feature.get("id") or properties.get("id") or properties.get("featureid") or properties.get("@id")
    if isinstance(identity,bool) or not isinstance(identity,(str,int)) or not str(identity).strip():
        raise SourceContractError("Every source feature requires a stable scalar identity")
    return str(identity)


def _tag_error(feature):
    properties=feature.get("properties") or {}
    nested=properties.get("tags")
    if nested is not None and not isinstance(nested,Mapping):
        return "properties.tags must be a tag object"
    tags=dict(nested or {})
    for key,value in tags.items():
        if not isinstance(key,str) or isinstance(value,(Mapping,list,tuple,set)):
            return "OSM tags must have string keys and scalar values"
    for key,value in properties.items():
        if isinstance(key,str) and (key in ("height","min_height","building") or
                                   key.startswith(("building:","roof:"))):
            if isinstance(value,(Mapping,list,tuple,set)):
                return key + " must be a scalar tag value"
    # Existing scalar-height warning/fallback behavior remains unchanged. This
    # validator only stops structural tag containers from escaping report mode.
    return None


def validate_features(features):
    """Validate complete geometry/references globally; return per-feature tag errors.

    CRS is validated by validate_document at the CLI boundary. The direct Python
    API consumes caller-validated projected features. This is not a full polygon
    topology repair pass, and never claims survey or source completeness.
    """
    identities={}
    parents={}
    tag_errors={}
    for feature in features:
        identity=source_identity(feature)
        if identity in identities:
            raise SourceContractError("Duplicate source feature identity: " + identity)
        identities[identity]=feature
        geometry=feature.get("geometry")
        if not isinstance(geometry,Mapping):
            raise SourceContractError(identity + ": missing geometry object")
        # Reuse the exact footprint ring validator with fixed harmless height
        # tags. Real semantic tags are handled independently below.
        try:
            normalize_feature({"type":"Feature","id":identity,"geometry":geometry,
                               "properties":{"height":"1"}})
        except (FootprintError,TypeError,IndexError) as error:
            raise SourceContractError(identity + ": invalid source geometry: " + str(error)) from error
        properties=feature.get("properties") or {}
        parent=properties.get("parent_identity")
        if parent is not None:
            if not isinstance(parent,str) or not parent:
                raise SourceContractError(identity + ": parent_identity must be a nonempty source ID")
            parents[identity]=parent
        error=_tag_error(feature)
        if error:
            tag_errors[identity]=error
    for identity,parent in parents.items():
        if parent not in identities:
            raise SourceContractError(identity + ": missing referenced parent " + parent)
    done=set()
    for start in parents:
        chain=set()
        current=start
        while current in parents and current not in done:
            if current in chain:
                raise SourceContractError("Cyclic building parent references involving " + current)
            chain.add(current)
            current=parents[current]
        done.update(chain)
    return tag_errors


def validate_document(document):
    """CLI input must explicitly declare the projected Minecraft X/Z mapping."""
    if not isinstance(document,Mapping) or document.get("type") != "FeatureCollection":
        raise SourceContractError("Input must be a projected building FeatureCollection")
    if not isinstance(document.get("features"),list):
        raise SourceContractError("FeatureCollection.features must be an array")
    coordinate_system=document.get("coordinateSystem")
    if not isinstance(coordinate_system,Mapping):
        raise SourceContractError("Input must declare coordinateSystem projection and game axes")
    expected_axes=["x=easting_metres","z=60000-northing_metres"]
    if (coordinate_system.get("projection") != "EPSG:3414" or
        coordinate_system.get("axes") != expected_axes or
        coordinate_system.get("units") != "metres" or
        isinstance(coordinate_system.get("blocksPerMetre"),bool) or
        coordinate_system.get("blocksPerMetre") != 1):
        raise SourceContractError("CRS contract requires EPSG:3414, x=E,z=60000-N, metres and one block/metre")
    return document["features"]
