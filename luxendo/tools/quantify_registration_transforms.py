#!/usr/bin/env python3
"""Write per-view registration transform summaries from BigStitcher XML."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
import xml.etree.ElementTree as ET

import numpy as np


def text(parent: ET.Element | None, tag: str, default: str = "") -> str:
    if parent is None:
        return default
    child = parent.find(tag)
    if child is None or child.text is None:
        return default
    return child.text.strip()


def affine_from_text(value: str) -> np.ndarray:
    values = [float(item) for item in value.split()]
    if len(values) != 12:
        raise ValueError(f"Expected 12 affine values, found {len(values)}")
    matrix = np.eye(4, dtype=np.float64)
    matrix[:3, :] = np.asarray(values, dtype=np.float64).reshape(3, 4)
    return matrix


def affine_to_values(matrix: np.ndarray) -> list[float]:
    return [float(v) for v in matrix[:3, :].reshape(-1)]


def read_setup_info(root: ET.Element) -> dict[int, dict[str, object]]:
    attribute_names: dict[str, dict[str, str]] = {}
    for attrs in root.findall("./SequenceDescription/ViewSetups/Attributes"):
        attr_name = attrs.attrib.get("name")
        if not attr_name:
            continue
        names: dict[str, str] = {}
        for entry in list(attrs):
            entry_id = text(entry, "id")
            if entry_id:
                names[entry_id] = text(entry, "name", entry_id)
        attribute_names[attr_name] = names

    setups: dict[int, dict[str, object]] = {}
    for setup in root.findall("./SequenceDescription/ViewSetups/ViewSetup"):
        setup_id = int(text(setup, "id"))
        attributes = setup.find("attributes")
        setup_info: dict[str, object] = {
            "setup_id": setup_id,
            "setup_name": text(setup, "name", f"setup{setup_id}"),
        }
        for attr_name in ("channel", "angle", "tile", "illumination"):
            attr_id = text(attributes, attr_name, "0")
            setup_info[f"{attr_name}_id"] = attr_id
            setup_info[f"{attr_name}_name"] = attribute_names.get(attr_name, {}).get(attr_id, attr_id)
        setups[setup_id] = setup_info
    return setups


def read_registrations(xml_path: Path) -> tuple[dict[int, dict[str, object]], dict[tuple[int, int], dict[str, object]]]:
    tree = ET.parse(xml_path)
    root = tree.getroot()
    setups = read_setup_info(root)
    registrations: dict[tuple[int, int], dict[str, object]] = {}

    for registration in root.findall("./ViewRegistrations/ViewRegistration"):
        timepoint = int(registration.attrib.get("timepoint", "0"))
        setup = int(registration.attrib["setup"])
        transforms = []
        composite = np.eye(4, dtype=np.float64)
        for transform in registration.findall("ViewTransform"):
            affine_text = text(transform, "affine")
            if not affine_text:
                continue
            matrix = affine_from_text(affine_text)
            transforms.append(
                {
                    "name": text(transform, "Name", "unnamed"),
                    "matrix": matrix,
                }
            )
            composite = matrix @ composite
        registrations[(timepoint, setup)] = {
            "timepoint": timepoint,
            "setup_id": setup,
            "transforms": transforms,
            "composite": composite,
        }

    return setups, registrations


def safe_inverse(matrix: np.ndarray) -> np.ndarray | None:
    try:
        return np.linalg.inv(matrix)
    except np.linalg.LinAlgError:
        return None


def rotation_angle_degrees(linear: np.ndarray) -> float:
    try:
        u, _, vt = np.linalg.svd(linear)
    except np.linalg.LinAlgError:
        return float("nan")
    rotation = u @ vt
    if np.linalg.det(rotation) < 0:
        u[:, -1] *= -1
        rotation = u @ vt
    value = (np.trace(rotation) - 1.0) / 2.0
    value = max(-1.0, min(1.0, float(value)))
    return float(math.degrees(math.acos(value)))


def singular_values(linear: np.ndarray) -> list[float]:
    try:
        return [float(v) for v in np.linalg.svd(linear, compute_uv=False)]
    except np.linalg.LinAlgError:
        return [float("nan"), float("nan"), float("nan")]


def summarize_matrix(prefix: str, matrix: np.ndarray, row: dict[str, object]) -> None:
    linear = matrix[:3, :3]
    translation = matrix[:3, 3]
    values = affine_to_values(matrix)
    scales = singular_values(linear)
    row[f"{prefix}_tx"] = float(translation[0])
    row[f"{prefix}_ty"] = float(translation[1])
    row[f"{prefix}_tz"] = float(translation[2])
    row[f"{prefix}_translation_norm"] = float(np.linalg.norm(translation))
    row[f"{prefix}_determinant"] = float(np.linalg.det(linear))
    row[f"{prefix}_scale_singular_0"] = scales[0]
    row[f"{prefix}_scale_singular_1"] = scales[1]
    row[f"{prefix}_scale_singular_2"] = scales[2]
    row[f"{prefix}_rotation_angle_deg"] = rotation_angle_degrees(linear)
    row[f"{prefix}_linear_max_abs_off_identity"] = float(np.max(np.abs(linear - np.eye(3))))
    row[f"{prefix}_affine_3x4"] = " ".join(f"{v:.12g}" for v in values)


def make_row(
    setup_info: dict[str, object],
    registration: dict[str, object],
    before: dict[str, object] | None,
) -> dict[str, object]:
    transforms = registration["transforms"]
    first = transforms[0]["matrix"] if transforms else np.eye(4, dtype=np.float64)
    composite = registration["composite"]
    row: dict[str, object] = {
        "timepoint": registration["timepoint"],
        "setup_id": registration["setup_id"],
        "setup_name": setup_info.get("setup_name", ""),
        "channel_id": setup_info.get("channel_id", ""),
        "channel_name": setup_info.get("channel_name", ""),
        "angle_id": setup_info.get("angle_id", ""),
        "angle_name": setup_info.get("angle_name", ""),
        "tile_id": setup_info.get("tile_id", ""),
        "tile_name": setup_info.get("tile_name", ""),
        "illumination_id": setup_info.get("illumination_id", ""),
        "illumination_name": setup_info.get("illumination_name", ""),
        "transform_count": len(transforms),
        "registration_transform_name": transforms[0]["name"] if transforms else "",
    }

    summarize_matrix("registration", first, row)
    summarize_matrix("composite", composite, row)

    if before is not None:
        before_transforms = before["transforms"]
        before_match = None
        registration_name = transforms[0]["name"] if transforms else ""
        if registration_name:
            for candidate in before_transforms:
                if candidate["name"] == registration_name:
                    before_match = candidate
                    break

        before_first = before_match["matrix"] if before_match is not None else np.eye(4, dtype=np.float64)
        before_composite = before["composite"]
        row["before_registration_match"] = before_match["name"] if before_match is not None else "identity_no_matching_transform"
        summarize_matrix("before_registration", before_first, row)
        summarize_matrix("before_composite", before_composite, row)

        first_inv = safe_inverse(before_first)
        composite_inv = safe_inverse(before_composite)
        if first_inv is not None:
            delta_first = first @ first_inv
            summarize_matrix("delta_registration", delta_first, row)
            row["delta_registration_linear_max_abs"] = float(np.max(np.abs(first[:3, :3] - before_first[:3, :3])))
            row["delta_registration_translation_max_abs"] = float(np.max(np.abs(first[:3, 3] - before_first[:3, 3])))
        if composite_inv is not None:
            delta_composite = composite @ composite_inv
            summarize_matrix("delta_composite", delta_composite, row)
            row["delta_composite_linear_max_abs"] = float(np.max(np.abs(composite[:3, :3] - before_composite[:3, :3])))
            row["delta_composite_translation_max_abs"] = float(np.max(np.abs(composite[:3, 3] - before_composite[:3, 3])))

    return row


def json_ready(value):
    if isinstance(value, dict):
        return {k: json_ready(v) for k, v in value.items()}
    if isinstance(value, list):
        return [json_ready(v) for v in value]
    if isinstance(value, (np.integer, np.floating)):
        return value.item()
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--after-xml", required=True, type=Path)
    parser.add_argument("--before-xml", type=Path, default=None)
    parser.add_argument("--out-tsv", required=True, type=Path)
    parser.add_argument("--out-json", required=True, type=Path)
    parser.add_argument("--timepoint", type=int, default=0)
    args = parser.parse_args()

    setups, after_regs = read_registrations(args.after_xml)
    _, before_regs = read_registrations(args.before_xml) if args.before_xml and args.before_xml.exists() else ({}, {})

    rows = []
    for key in sorted(after_regs):
        timepoint, setup_id = key
        if timepoint != args.timepoint:
            continue
        rows.append(make_row(setups.get(setup_id, {"setup_id": setup_id}), after_regs[key], before_regs.get(key)))

    if not rows:
        raise RuntimeError(f"No registrations found for timepoint {args.timepoint} in {args.after_xml}")

    args.out_tsv.parent.mkdir(parents=True, exist_ok=True)
    args.out_json.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = sorted({key for row in rows for key in row.keys()})
    preferred = [
        "timepoint",
        "setup_id",
        "setup_name",
        "channel_id",
        "channel_name",
        "angle_id",
        "angle_name",
        "tile_id",
        "tile_name",
        "illumination_id",
        "illumination_name",
        "transform_count",
        "registration_transform_name",
    ]
    fieldnames = preferred + [key for key in fieldnames if key not in preferred]

    with args.out_tsv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames, delimiter="\t", extrasaction="ignore")
        writer.writeheader()
        writer.writerows(rows)

    args.out_json.write_text(json.dumps(json_ready(rows), indent=2), encoding="utf-8")
    print(f"Wrote {len(rows)} transform row(s) to {args.out_tsv}")
    print(f"Wrote {args.out_json}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
