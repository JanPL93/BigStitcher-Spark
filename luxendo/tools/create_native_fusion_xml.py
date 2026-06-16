#!/usr/bin/env python3
"""Create a BigStitcher fusion XML whose transforms are in output voxels.

BigStitcher registration XMLs for this Luxendo dataset use physical/global
coordinates, usually micrometers. Spark fusion samples the output at one unit in
the coordinate system of the registrations, so fusing that XML directly at scale
1 would oversample the data. This helper collapses each registered composite
transform, scales it into an explicit output voxel grid, and writes a
fusion-specific XML for full-resolution fusion.

By default the output grid preserves native anisotropy, which is the desired
path for single-angle tiled data. For orthogonal/multiview acquisitions, pass
``--isotropic`` or ``--sampling isotropic`` to use the lateral pixel size for
all axes.
"""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import statistics
import sys
import xml.etree.ElementTree as ET


def identity_matrix() -> list[list[float]]:
    return [
        [1.0, 0.0, 0.0, 0.0],
        [0.0, 1.0, 0.0, 0.0],
        [0.0, 0.0, 1.0, 0.0],
        [0.0, 0.0, 0.0, 1.0],
    ]


def parse_vector(text: str, expected: int, label: str) -> list[float]:
    values = [float(v) for v in text.split()]
    if len(values) != expected:
        raise ValueError(f"{label} has {len(values)} values, expected {expected}")
    return values


def affine_to_matrix(values: list[float]) -> list[list[float]]:
    return [
        [values[0], values[1], values[2], values[3]],
        [values[4], values[5], values[6], values[7]],
        [values[8], values[9], values[10], values[11]],
        [0.0, 0.0, 0.0, 1.0],
    ]


def matrix_to_affine(matrix: list[list[float]]) -> list[float]:
    return [
        matrix[0][0],
        matrix[0][1],
        matrix[0][2],
        matrix[0][3],
        matrix[1][0],
        matrix[1][1],
        matrix[1][2],
        matrix[1][3],
        matrix[2][0],
        matrix[2][1],
        matrix[2][2],
        matrix[2][3],
    ]


def matmul(a: list[list[float]], b: list[list[float]]) -> list[list[float]]:
    out = [[0.0 for _ in range(4)] for _ in range(4)]
    for r in range(4):
        for c in range(4):
            out[r][c] = sum(a[r][k] * b[k][c] for k in range(4))
    return out


def transform_point(matrix: list[list[float]], point: tuple[float, float, float]) -> tuple[float, float, float]:
    x, y, z = point
    return (
        matrix[0][0] * x + matrix[0][1] * y + matrix[0][2] * z + matrix[0][3],
        matrix[1][0] * x + matrix[1][1] * y + matrix[1][2] * z + matrix[1][3],
        matrix[2][0] * x + matrix[2][1] * y + matrix[2][2] * z + matrix[2][3],
    )


def format_float(value: float) -> str:
    if abs(value) < 1e-12:
        value = 0.0
    return f"{value:.12g}"


def direct_child_text(parent: ET.Element, tag: str) -> str:
    child = parent.find(tag)
    if child is None or child.text is None:
        raise ValueError(f"Missing <{tag}> under <{parent.tag}>")
    return child.text.strip()


def view_setup_sizes(root: ET.Element) -> dict[str, tuple[int, int, int]]:
    sizes: dict[str, tuple[int, int, int]] = {}
    for setup in root.findall("./SequenceDescription/ViewSetups/ViewSetup"):
        setup_id = direct_child_text(setup, "id")
        size_values = [int(v) for v in direct_child_text(setup, "size").split()]
        if len(size_values) != 3:
            raise ValueError(f"ViewSetup {setup_id} size is not 3D")
        sizes[setup_id] = (size_values[0], size_values[1], size_values[2])
    return sizes


def view_setup_angle_ids(root: ET.Element) -> set[str]:
    angle_ids: set[str] = set()
    for setup in root.findall("./SequenceDescription/ViewSetups/ViewSetup"):
        angle = setup.find("./attributes/angle")
        if angle is not None and angle.text is not None:
            angle_ids.add(angle.text.strip())
    return angle_ids


def update_voxel_sizes(root: ET.Element) -> None:
    for setup in root.findall("./SequenceDescription/ViewSetups/ViewSetup"):
        voxel_size = setup.find("voxelSize")
        if voxel_size is None:
            continue
        unit = voxel_size.find("unit")
        if unit is not None:
            unit.text = "px"
        size = voxel_size.find("size")
        if size is not None:
            size.text = "1.0 1.0 1.0"


def clear_bounding_boxes(root: ET.Element) -> None:
    boxes = root.find("./BoundingBoxes")
    if boxes is not None:
        for child in list(boxes):
            boxes.remove(child)


def native_scale_matrix(voxel_size: tuple[float, float, float]) -> list[list[float]]:
    sx, sy, sz = voxel_size
    if sx <= 0 or sy <= 0 or sz <= 0:
        raise ValueError("Native voxel sizes must be positive")
    return [
        [1.0 / sx, 0.0, 0.0, 0.0],
        [0.0, 1.0 / sy, 0.0, 0.0],
        [0.0, 0.0, 1.0 / sz, 0.0],
        [0.0, 0.0, 0.0, 1.0],
    ]


def registration_matrix(view_registration: ET.Element) -> list[list[float]]:
    matrix = identity_matrix()
    transforms = view_registration.findall("ViewTransform")
    if not transforms:
        raise ValueError(f"ViewRegistration {view_registration.attrib} has no ViewTransform")
    for transform in transforms:
        affine = transform.find("affine")
        if affine is None or affine.text is None:
            raise ValueError(f"ViewTransform in {view_registration.attrib} has no affine")
        matrix = matmul(matrix, affine_to_matrix(parse_vector(affine.text, 12, "affine")))
    return matrix


def column_norms(matrix: list[list[float]]) -> tuple[float, float, float]:
    return tuple(
        math.sqrt(matrix[0][c] ** 2 + matrix[1][c] ** 2 + matrix[2][c] ** 2)
        for c in range(3)
    )


def infer_anisotropic_voxel_size(root: ET.Element) -> tuple[float, float, float]:
    norms_by_axis = [[], [], []]
    for view_registration in root.findall("./ViewRegistrations/ViewRegistration"):
        norms = column_norms(registration_matrix(view_registration))
        for axis, value in enumerate(norms):
            if value <= 0 or not math.isfinite(value):
                raise ValueError(f"Invalid inferred voxel spacing on axis {axis}: {value}")
            norms_by_axis[axis].append(value)

    if any(not values for values in norms_by_axis):
        raise ValueError("Could not infer voxel size from ViewRegistration affine columns")

    return tuple(statistics.median(values) for values in norms_by_axis)  # type: ignore[return-value]


def sorted_view_registrations(root: ET.Element) -> list[ET.Element]:
    def sort_key(view_registration: ET.Element) -> tuple[int, int, str, str]:
        timepoint = view_registration.attrib.get("timepoint", "0")
        setup = view_registration.attrib.get("setup", "0")
        try:
            timepoint_sort = int(timepoint)
        except ValueError:
            timepoint_sort = 0
        try:
            setup_sort = int(setup)
        except ValueError:
            setup_sort = 0
        return (timepoint_sort, setup_sort, timepoint, setup)

    return sorted(root.findall("./ViewRegistrations/ViewRegistration"), key=sort_key)


def orientation_frame_from_reference(
    root: ET.Element,
) -> tuple[list[list[float]], dict[str, object]]:
    registrations = sorted_view_registrations(root)
    if not registrations:
        raise ValueError("Could not find a ViewRegistration to use as the first-stack reference")

    reference = registrations[0]
    reference_matrix = registration_matrix(reference)
    scales = column_norms(reference_matrix)
    if any(scale <= 0 or not math.isfinite(scale) for scale in scales):
        raise ValueError(f"Invalid reference affine column lengths: {scales}")

    orientation = [
        [reference_matrix[r][c] / scales[c] for c in range(3)]
        for r in range(3)
    ]

    frame = identity_matrix()
    for r in range(3):
        for c in range(3):
            frame[r][c] = orientation[c][r]

    dot_products: list[list[float]] = []
    max_off_diagonal_dot = 0.0
    for a in range(3):
        row: list[float] = []
        for b in range(3):
            dot = sum(orientation[r][a] * orientation[r][b] for r in range(3))
            row.append(dot)
            if a != b:
                max_off_diagonal_dot = max(max_off_diagonal_dot, abs(dot))
        dot_products.append(row)

    determinant = (
        orientation[0][0] * (orientation[1][1] * orientation[2][2] - orientation[1][2] * orientation[2][1])
        - orientation[0][1] * (orientation[1][0] * orientation[2][2] - orientation[1][2] * orientation[2][0])
        + orientation[0][2] * (orientation[1][0] * orientation[2][1] - orientation[1][1] * orientation[2][0])
    )

    metadata = {
        "reference_timepoint": reference.attrib.get("timepoint"),
        "reference_setup": reference.attrib.get("setup"),
        "reference_column_lengths": list(scales),
        "reference_orientation_matrix": orientation,
        "reference_orientation_determinant": determinant,
        "reference_orientation_dot_products": dot_products,
        "reference_orientation_max_off_diagonal_dot": max_off_diagonal_dot,
        "frame_matrix": frame,
    }
    return frame, metadata


def isotropic_voxel_size_from_anisotropic(voxel_size: tuple[float, float, float]) -> tuple[float, float, float]:
    lateral = statistics.median((voxel_size[0], voxel_size[1]))
    return (lateral, lateral, lateral)


def corners_for_size(size: tuple[int, int, int]) -> list[tuple[float, float, float]]:
    max_x, max_y, max_z = (float(size[0] - 1), float(size[1] - 1), float(size[2] - 1))
    return [
        (x, y, z)
        for x in (0.0, max_x)
        for y in (0.0, max_y)
        for z in (0.0, max_z)
    ]


def replace_registration_transform(view_registration: ET.Element, matrix: list[list[float]]) -> None:
    for child in list(view_registration):
        if child.tag == "ViewTransform":
            view_registration.remove(child)

    transform = ET.SubElement(view_registration, "ViewTransform", {"type": "affine"})
    name = ET.SubElement(transform, "Name")
    name.text = "registered transform scaled to native voxel coordinates"
    affine = ET.SubElement(transform, "affine")
    affine.text = " ".join(format_float(v) for v in matrix_to_affine(matrix))


def convert_xml(
    input_xml: Path,
    output_xml: Path,
    voxel_size: tuple[float, float, float],
    first_stack_as_zero: bool = False,
) -> tuple[list[int], float, dict[str, object] | None]:
    tree = ET.parse(input_xml)
    root = tree.getroot()
    sizes = view_setup_sizes(root)
    native_scale = native_scale_matrix(voxel_size)
    frame_matrix: list[list[float]] | None = None
    frame_metadata: dict[str, object] | None = None
    if first_stack_as_zero:
        frame_matrix, frame_metadata = orientation_frame_from_reference(root)

    mins = [math.inf, math.inf, math.inf]
    maxs = [-math.inf, -math.inf, -math.inf]

    for view_registration in root.findall("./ViewRegistrations/ViewRegistration"):
        setup_id = view_registration.attrib.get("setup")
        if setup_id is None or setup_id not in sizes:
            raise ValueError(f"Cannot find ViewSetup for ViewRegistration {view_registration.attrib}")

        global_matrix = registration_matrix(view_registration)
        if frame_matrix is not None:
            global_matrix = matmul(frame_matrix, global_matrix)
        native_matrix = matmul(native_scale, global_matrix)
        replace_registration_transform(view_registration, native_matrix)

        for corner in corners_for_size(sizes[setup_id]):
            point = transform_point(native_matrix, corner)
            for d in range(3):
                mins[d] = min(mins[d], point[d])
                maxs[d] = max(maxs[d], point[d])

    update_voxel_sizes(root)
    clear_bounding_boxes(root)

    output_xml.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(tree, space="  ")
    tree.write(output_xml, encoding="UTF-8", xml_declaration=True)

    dims = [int(math.floor(maxs[d]) - math.floor(mins[d]) + 1) for d in range(3)]
    gib = dims[0] * dims[1] * dims[2] * 2 / (1024**3)
    return dims, gib, frame_metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_xml", type=Path)
    parser.add_argument("output_xml", type=Path)
    parser.add_argument(
        "--voxel-size",
        default=None,
        help=(
            "Explicit output voxel size in registered global units, as x,y,z. "
            "When omitted, it is inferred from the registration affine column lengths."
        ),
    )
    parser.add_argument(
        "--sampling",
        choices=("anisotropic", "isotropic"),
        default="anisotropic",
        help=(
            "Output grid sampling when --voxel-size is omitted. "
            "anisotropic preserves native x/y/z spacing; isotropic uses lateral spacing on every axis. "
            "Default: %(default)s"
        ),
    )
    parser.add_argument(
        "--isotropic",
        action="store_true",
        help="Shortcut for --sampling isotropic; intended for orthogonal/multiview acquisitions.",
    )
    parser.add_argument(
        "--metadata-json",
        type=Path,
        default=None,
        help="Optional path to write output sampling metadata for downstream fusion/export steps.",
    )
    parser.add_argument(
        "--first-stack-as-zero",
        action="store_true",
        help=(
            "Use the earliest/smallest ViewRegistration orientation as the output frame before voxel scaling. "
            "This makes the first stack axis-aligned and leaves other stacks in relative orientation."
        ),
    )
    args = parser.parse_args()

    tree = ET.parse(args.input_xml)
    root = tree.getroot()
    angle_ids = view_setup_angle_ids(root)
    angle_count = len(angle_ids)

    if args.voxel_size is not None:
        if args.isotropic or args.sampling != "anisotropic":
            parser.error("--voxel-size cannot be combined with --isotropic or --sampling isotropic")
        voxel_values = tuple(float(v) for v in args.voxel_size.split(","))
        if len(voxel_values) != 3:
            raise ValueError("--voxel-size must have three comma-separated numbers")
        sampling = "explicit"
    else:
        sampling = "isotropic" if args.isotropic else args.sampling
        anisotropic = infer_anisotropic_voxel_size(root)
        if sampling == "isotropic":
            if angle_count <= 1:
                print(
                    "WARNING: isotropic output was requested for a single-angle XML; "
                    "anisotropic output is normally preferred for tiled single-view data.",
                    file=sys.stderr,
                )
            voxel_values = isotropic_voxel_size_from_anisotropic(anisotropic)
        else:
            voxel_values = anisotropic

    dims, gib, frame_metadata = convert_xml(
        args.input_xml,
        args.output_xml,
        voxel_values,  # type: ignore[arg-type]
        first_stack_as_zero=args.first_stack_as_zero,
    )
    print(f"Wrote native fusion XML: {args.output_xml}")
    print(f"Detected angle count: {angle_count if angle_ids else 'unknown'}")
    print(f"Output sampling: {sampling}")
    if args.first_stack_as_zero:
        assert frame_metadata is not None
        print(
            "FirstStackAsZero reference: "
            f"timepoint={frame_metadata['reference_timepoint']} setup={frame_metadata['reference_setup']}"
        )
    print(
        "Output voxel size in registered units: "
        f"{format_float(voxel_values[0])} x {format_float(voxel_values[1])} x {format_float(voxel_values[2])}"
    )
    print(f"Estimated full fused native dimensions: {dims[0]} x {dims[1]} x {dims[2]}")
    print(f"Estimated uint16 s0 payload: {gib:.2f} GiB")
    if args.metadata_json is not None:
        metadata = {
            "input_xml": str(args.input_xml),
            "output_xml": str(args.output_xml),
            "angle_count": angle_count if angle_ids else None,
            "sampling": sampling,
            "output_voxel_size": list(voxel_values),
            "output_voxel_unit": "micrometer",
            "first_stack_as_zero": args.first_stack_as_zero,
            "first_stack_as_zero_frame": frame_metadata,
            "dims_xyz": dims,
            "estimated_uint16_gib": gib,
        }
        args.metadata_json.parent.mkdir(parents=True, exist_ok=True)
        args.metadata_json.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
        print(f"Wrote fusion metadata JSON: {args.metadata_json}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
