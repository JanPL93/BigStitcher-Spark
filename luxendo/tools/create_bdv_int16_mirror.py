#!/usr/bin/env python3
"""Create a Spark-safe signed-int16 mirror from a BDV HDF5 XML.

Luxendo BDV HDF5 wrappers commonly point at external ``uint16`` HDF5 datasets.
The Java HDF5 stack used by BigStitcher-Spark can stumble on unsigned-short
blocks, so this creates small BDV metadata plus external mirror files whose
pixel bit pattern is unchanged but stored as signed int16.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import sys
import time
import xml.etree.ElementTree as ET

import numpy as np

try:
    import h5py
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parent / "python"))
    import h5py


FLATFIELD_NONE = "none"
FLATFIELD_AVERAGE_ALL_PLANES = "average-all-planes"
FLATFIELD_MODE_ATTR = "luxendo_mirror_flatfield_mode"
FLATFIELD_GAIN_SHA_ATTR = "luxendo_mirror_flatfield_gain_sha256"
SUBTRACT_ATTR = "luxendo_mirror_subtract"


def attr_to_str(value):
    if value is None:
        return None
    if isinstance(value, bytes):
        return value.decode("utf-8")
    return str(value)


def copy_attrs(src, dst) -> None:
    for key, value in src.attrs.items():
        dst.attrs[key] = value


def sha256_array(array: np.ndarray) -> str:
    contiguous = np.ascontiguousarray(array)
    digest = hashlib.sha256()
    digest.update(str(contiguous.shape).encode("utf-8"))
    digest.update(str(contiguous.dtype).encode("utf-8"))
    digest.update(contiguous.view(np.uint8).tobytes())
    return digest.hexdigest()


def build_flatfield_gain(profile, min_gain=0.25, max_gain=4.0):
    profile = np.asarray(profile, dtype=np.float64)
    finite = np.isfinite(profile)
    positive = finite & (profile > 0)
    if not np.any(positive):
        raise RuntimeError("Flatfield profile has no positive finite pixels")

    mean_profile = float(profile[positive].mean())
    denominator = profile.copy()
    denominator[~positive] = mean_profile
    denominator = np.maximum(denominator, mean_profile / max_gain)

    gain = mean_profile / denominator
    gain = np.maximum(gain, min_gain)
    gain = np.minimum(gain, max_gain)
    return gain.astype(np.float32), mean_profile


def write_flatfield_metadata(metadata_path, profile_path, gain_path, profile, gain, mean_profile):
    metadata = {
        "mode": FLATFIELD_AVERAGE_ALL_PLANES,
        "profile": str(profile_path),
        "inverse_gain": str(gain_path),
        "shape_yx": list(profile.shape),
        "mean_profile": mean_profile,
        "profile_min": float(np.nanmin(profile)),
        "profile_max": float(np.nanmax(profile)),
        "gain_min": float(np.nanmin(gain)),
        "gain_max": float(np.nanmax(gain)),
        "profile_sha256": sha256_array(profile),
        "gain_sha256": sha256_array(gain),
    }
    metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    return metadata


def direct_child_text(parent: ET.Element, tag: str) -> str:
    child = parent.find(tag)
    if child is None or child.text is None:
        raise ValueError(f"Missing <{tag}> under <{parent.tag}>")
    return child.text.strip()


def bdv_h5_path_from_xml(xml_path: Path) -> Path:
    tree = ET.parse(xml_path)
    root = tree.getroot()
    base_path_el = root.find("BasePath")
    if base_path_el is None or base_path_el.text is None:
        base_path = xml_path.parent
    else:
        base_text = base_path_el.text.strip()
        base_type = base_path_el.attrib.get("type", "relative")
        base_path = (xml_path.parent / base_text).resolve() if base_type == "relative" else Path(base_text)

    hdf5_el = root.find("./SequenceDescription/ImageLoader/hdf5")
    if hdf5_el is None or hdf5_el.text is None:
        raise ValueError(f"{xml_path} does not contain an ImageLoader/hdf5 path")
    h5_text = hdf5_el.text.strip()
    h5_type = hdf5_el.attrib.get("type", "relative")
    return (base_path / h5_text).resolve() if h5_type == "relative" else Path(h5_text).resolve()


def setup_ids_from_xml(xml_path: Path) -> list[int]:
    tree = ET.parse(xml_path)
    root = tree.getroot()
    ids = []
    for setup in root.findall("./SequenceDescription/ViewSetups/ViewSetup"):
        ids.append(int(direct_child_text(setup, "id")))
    return ids


def parse_setup_ids(value: str | None) -> set[int] | None:
    if not value:
        return None
    return {int(item.strip()) for item in value.split(",") if item.strip()}


def discover_sources(xml_path: Path, selected: set[int] | None):
    h5_path = bdv_h5_path_from_xml(xml_path)
    setup_ids = setup_ids_from_xml(xml_path)
    sources = []

    with h5py.File(h5_path, "r") as h5:
        for setup_id in setup_ids:
            if selected is not None and setup_id not in selected:
                continue
            cells_group = f"t00000/s{setup_id:02d}/0"
            if cells_group not in h5:
                raise RuntimeError(f"{h5_path} is missing {cells_group}")
            link = h5[cells_group].get("cells", getlink=True)
            if not isinstance(link, h5py.ExternalLink):
                raise RuntimeError(f"{cells_group}/cells is not an external link")
            src_file = Path(link.filename)
            src_path = (h5_path.parent / src_file).resolve() if not src_file.is_absolute() else src_file
            dataset_path = link.path.strip("/")
            with h5py.File(src_path, "r") as src_h5:
                src = src_h5[dataset_path]
                sources.append(
                    {
                        "setup_id": setup_id,
                        "src_path": str(src_path),
                        "dataset_path": dataset_path,
                        "shape": tuple(src.shape),
                        "dtype": str(src.dtype),
                        "chunks": tuple(src.chunks or src.shape),
                        "resolutions": np.asarray(h5[f"s{setup_id:02d}/resolutions"][:1], dtype=np.float64),
                        "subdivisions": np.asarray(h5[f"s{setup_id:02d}/subdivisions"][:1], dtype=np.float64),
                    }
                )
    return h5_path, sources


def validate_existing(path, dataset_path, shape, chunks, flatfield_mode=FLATFIELD_NONE, gain_sha=None, subtract=0):
    try:
        with h5py.File(path, "r") as h5:
            data = h5[dataset_path]
            if not (
                data.shape == tuple(shape)
                and data.dtype == np.dtype("int16")
                and data.chunks == tuple(chunks)
            ):
                return False

            existing_subtract = int(attr_to_str(data.attrs.get(SUBTRACT_ATTR, "0")))
            if existing_subtract != int(subtract):
                return False

            existing_mode = attr_to_str(data.attrs.get(FLATFIELD_MODE_ATTR, FLATFIELD_NONE))
            if flatfield_mode == FLATFIELD_NONE:
                return existing_mode in (None, FLATFIELD_NONE)

            if existing_mode != flatfield_mode:
                return False
            if gain_sha:
                return attr_to_str(data.attrs.get(FLATFIELD_GAIN_SHA_ATTR)) == gain_sha
            return True
    except Exception:
        return False


def compute_source_profile(task):
    setup_id, src_path, dataset_path, block_z = task
    started = time.time()
    with h5py.File(src_path, "r", rdcc_nbytes=512 * 1024 * 1024) as src_h5:
        src = src_h5[dataset_path]
        if src.dtype != np.dtype("uint16"):
            raise RuntimeError(f"{src_path}:{dataset_path} has dtype {src.dtype}, expected uint16")
        profile_sum = np.zeros(src.shape[1:], dtype=np.float64)
        plane_count = 0
        for z0 in range(0, src.shape[0], block_z):
            z1 = min(z0 + block_z, src.shape[0])
            block = src[z0:z1, :, :]
            profile_sum += block.sum(axis=0, dtype=np.float64)
            plane_count += z1 - z0
    return setup_id, profile_sum, plane_count, time.time() - started


def compute_average_flatfield_profile(sources, block_z, workers):
    yx_shapes = {tuple(source["shape"][1:]) for source in sources}
    if len(yx_shapes) != 1:
        raise RuntimeError(f"Cannot build one flatfield profile for differing YX shapes: {sorted(yx_shapes)}")

    total_sum = None
    total_count = 0
    tasks = [
        (source["setup_id"], source["src_path"], source["dataset_path"], block_z)
        for source in sources
    ]
    print(
        f"Computing flatfield profile from all planes in {len(tasks)} view(s) "
        f"with {workers} worker(s), block_z={block_z}",
        flush=True,
    )
    with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(compute_source_profile, task) for task in tasks]
        for future in concurrent.futures.as_completed(futures):
            setup_id, profile_sum, plane_count, seconds = future.result()
            total_sum = profile_sum if total_sum is None else total_sum + profile_sum
            total_count += plane_count
            print(
                f"setup{setup_id:02d}: added {plane_count} plane(s) to flatfield profile "
                f"in {seconds / 60:.1f} min",
                flush=True,
            )

    if total_sum is None or total_count == 0:
        raise RuntimeError("No data was accumulated for the flatfield profile")
    return total_sum / total_count


def apply_flatfield_block(block, gain):
    corrected = block.astype(np.float32)
    corrected *= gain[np.newaxis, :, :]
    np.rint(corrected, out=corrected)
    np.clip(corrected, 0, 65535, out=corrected)
    return corrected.astype(np.uint16)


def subtract_block(block, subtract):
    corrected = block.astype(np.int32)
    corrected -= int(subtract)
    np.clip(corrected, 0, 65535, out=corrected)
    return corrected.astype(np.uint16)


def convert_source(task):
    source, dst_path, block_z, force, flatfield_mode, gain_path, gain_sha, subtract = task
    setup_id = source["setup_id"]
    dst_path = Path(dst_path)
    partial_path = dst_path.with_suffix(dst_path.suffix + ".partial")

    if dst_path.exists() and not force and validate_existing(
        dst_path,
        source["dataset_path"],
        source["shape"],
        source["chunks"],
        flatfield_mode,
        gain_sha,
        subtract,
    ):
        return setup_id, "skipped", str(dst_path), 0.0

    dst_path.parent.mkdir(parents=True, exist_ok=True)
    if partial_path.exists():
        partial_path.unlink()

    gain = None
    if flatfield_mode != FLATFIELD_NONE:
        gain = np.load(gain_path).astype(np.float32, copy=False)
        if gain.shape != tuple(source["shape"][1:]):
            raise RuntimeError(f"{gain_path} has shape {gain.shape}, expected {source['shape'][1:]}")

    started = time.time()
    with h5py.File(source["src_path"], "r", rdcc_nbytes=512 * 1024 * 1024) as src_h5:
        src = src_h5[source["dataset_path"]]
        if src.dtype != np.dtype("uint16"):
            raise RuntimeError(f"{source['src_path']}:{source['dataset_path']} has dtype {src.dtype}, expected uint16")
        with h5py.File(partial_path, "w", rdcc_nbytes=512 * 1024 * 1024) as dst_h5:
            parent_path = str(Path(source["dataset_path"]).parent).replace("\\", "/")
            if parent_path not in ("", "."):
                dst_h5.require_group(parent_path)
            dst = dst_h5.create_dataset(
                source["dataset_path"],
                shape=source["shape"],
                dtype=np.int16,
                chunks=source["chunks"],
            )
            copy_attrs(src, dst)
            dst.attrs[FLATFIELD_MODE_ATTR] = flatfield_mode
            if gain_sha:
                dst.attrs[FLATFIELD_GAIN_SHA_ATTR] = gain_sha
            dst.attrs[SUBTRACT_ATTR] = int(subtract)

            for z0 in range(0, src.shape[0], block_z):
                z1 = min(z0 + block_z, src.shape[0])
                block = src[z0:z1, :, :]
                if gain is None and int(subtract) == 0:
                    dst[z0:z1, :, :] = block.view(np.int16)
                else:
                    corrected = block if gain is None else apply_flatfield_block(block, gain)
                    if int(subtract) != 0:
                        corrected = subtract_block(corrected, subtract)
                    dst[z0:z1, :, :] = corrected.view(np.int16)

    if dst_path.exists():
        dst_path.unlink()
    os.replace(partial_path, dst_path)
    return setup_id, "converted", str(dst_path), time.time() - started


def write_bdv_h5(out_h5: Path, sources, tile_files) -> None:
    if out_h5.exists():
        out_h5.unlink()

    with h5py.File(out_h5, "w") as h5:
        for source in sources:
            setup_id = source["setup_id"]
            setup = h5.create_group(f"s{setup_id:02d}")
            setup.create_dataset("resolutions", data=source["resolutions"])
            setup.create_dataset("subdivisions", data=source["subdivisions"])
            group = h5.create_group(f"t00000/s{setup_id:02d}/0")
            rel = Path("tiles") / Path(tile_files[setup_id]).name
            group["cells"] = h5py.ExternalLink(rel.as_posix(), source["dataset_path"])


def indent(elem: ET.Element, level=0) -> None:
    pad = "\n" + level * "  "
    if len(elem):
        if not elem.text or not elem.text.strip():
            elem.text = pad + "  "
        for child in elem:
            indent(child, level + 1)
        if not child.tail or not child.tail.strip():
            child.tail = pad
    if level and (not elem.tail or not elem.tail.strip()):
        elem.tail = pad


def filter_xml_to_setups(root: ET.Element, selected: set[int] | None) -> None:
    if selected is None:
        return

    view_setups = root.find("./SequenceDescription/ViewSetups")
    if view_setups is None:
        raise ValueError("XML is missing SequenceDescription/ViewSetups")

    used_attribute_ids: dict[str, set[str]] = {}
    for setup in list(view_setups.findall("ViewSetup")):
        setup_id = int(direct_child_text(setup, "id"))
        if setup_id not in selected:
            view_setups.remove(setup)
            continue

        attributes = setup.find("attributes")
        if attributes is None:
            continue
        for attribute in list(attributes):
            if attribute.text is not None:
                used_attribute_ids.setdefault(attribute.tag, set()).add(attribute.text.strip())

    for attributes in view_setups.findall("Attributes"):
        name = attributes.attrib.get("name")
        if not name or name not in used_attribute_ids:
            continue
        allowed = used_attribute_ids[name]
        for value in list(attributes):
            value_id = value.find("id")
            if value_id is not None and value_id.text is not None and value_id.text.strip() not in allowed:
                attributes.remove(value)

    view_registrations = root.find("ViewRegistrations")
    if view_registrations is not None:
        for registration in list(view_registrations.findall("ViewRegistration")):
            setup = registration.attrib.get("setup")
            if setup is not None and int(setup) not in selected:
                view_registrations.remove(registration)

    missing_views = root.find("./SequenceDescription/MissingViews")
    if missing_views is not None:
        for view_id in list(missing_views):
            setup = view_id.attrib.get("setup")
            if setup is not None and int(setup) not in selected:
                missing_views.remove(view_id)

    view_interest_points = root.find("ViewInterestPoints")
    if view_interest_points is not None:
        for view_interest_point in list(view_interest_points):
            setup = view_interest_point.attrib.get("setup")
            if setup is not None and int(setup) not in selected:
                view_interest_points.remove(view_interest_point)

    stitching_results = root.find("StitchingResults")
    if stitching_results is not None:
        for pairwise in list(stitching_results):
            view_setup_a = pairwise.attrib.get("view_setup_a", "")
            view_setup_b = pairwise.attrib.get("view_setup_b", "")
            setup_ids: set[int] = set()
            for value in (view_setup_a, view_setup_b):
                for item in value.split(","):
                    item = item.strip()
                    if item:
                        setup_ids.add(int(item))
            if setup_ids and not setup_ids.issubset(selected):
                stitching_results.remove(pairwise)


def patch_xml(source_xml: Path, out_xml: Path, out_h5: Path, selected: set[int] | None = None) -> None:
    tree = ET.parse(source_xml)
    root = tree.getroot()
    filter_xml_to_setups(root, selected)

    base_path = root.find("BasePath")
    if base_path is None:
        base_path = ET.Element("BasePath", {"type": "relative"})
        root.insert(0, base_path)
    base_path.text = "."
    base_path.set("type", "relative")

    image_loader = root.find("./SequenceDescription/ImageLoader")
    if image_loader is None:
        raise ValueError("XML is missing SequenceDescription/ImageLoader")
    image_loader.set("format", "bdv.hdf5")
    hdf5 = image_loader.find("hdf5")
    if hdf5 is None:
        hdf5 = ET.SubElement(image_loader, "hdf5")
    hdf5.text = out_h5.name
    hdf5.set("type", "relative")

    if root.find("StitchingResults") is None:
        ET.SubElement(root, "StitchingResults")

    out_xml.parent.mkdir(parents=True, exist_ok=True)
    indent(root)
    tree.write(out_xml, encoding="UTF-8", xml_declaration=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-xml", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--setups", default=None, help="Optional comma-separated setup IDs to mirror")
    parser.add_argument("--workers", type=int, default=max(1, min(8, (os.cpu_count() or 8) // 2)))
    parser.add_argument("--block-z", type=int, default=64)
    parser.add_argument("--force", action="store_true")
    parser.add_argument(
        "--flatfield",
        nargs="?",
        const=FLATFIELD_AVERAGE_ALL_PLANES,
        default=FLATFIELD_NONE,
        choices=(FLATFIELD_NONE, FLATFIELD_AVERAGE_ALL_PLANES),
    )
    parser.add_argument("--flatfield-profile", default=None)
    parser.add_argument("--flatfield-gain", default=None)
    parser.add_argument("--flatfield-recompute-profile", action="store_true")
    parser.add_argument("--flatfield-profile-only", action="store_true")
    parser.add_argument("--flatfield-profile-block-z", type=int, default=64)
    parser.add_argument("--flatfield-apply-block-z", type=int, default=16)
    parser.add_argument("--flatfield-min-gain", type=float, default=0.25)
    parser.add_argument("--flatfield-max-gain", type=float, default=4.0)
    parser.add_argument("--subtract", type=int, default=0, help="Subtract this value from uint16 pixels before writing the int16 mirror")
    args = parser.parse_args()

    source_xml = args.source_xml.resolve()
    out_dir = args.out.resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    tiles_dir = out_dir / "tiles"
    tiles_dir.mkdir(parents=True, exist_ok=True)

    if args.block_z <= 0 or args.flatfield_profile_block_z <= 0 or args.flatfield_apply_block_z <= 0:
        raise ValueError("block-z and flatfield block sizes must be positive")
    if args.flatfield_min_gain <= 0 or args.flatfield_max_gain <= 0:
        raise ValueError("flatfield min/max gain must be positive")
    if args.flatfield_min_gain > args.flatfield_max_gain:
        raise ValueError("flatfield min gain cannot exceed flatfield max gain")
    if args.flatfield_profile_only and args.flatfield == FLATFIELD_NONE:
        raise ValueError("--flatfield-profile-only requires --flatfield")
    if args.subtract < 0:
        raise ValueError("--subtract must be non-negative")

    selected = parse_setup_ids(args.setups)
    _, sources = discover_sources(source_xml, selected)
    if not sources:
        raise RuntimeError("No source BDV cells links were found")

    tile_files = {
        source["setup_id"]: tiles_dir / f"setup{source['setup_id']:02d}_int16.h5"
        for source in sources
    }

    gain_sha = None
    gain_path = None
    conversion_block_z = args.block_z
    if args.flatfield != FLATFIELD_NONE:
        profile_path = Path(args.flatfield_profile).resolve() if args.flatfield_profile else out_dir / "flatfield_profile.npy"
        gain_path = Path(args.flatfield_gain).resolve() if args.flatfield_gain else out_dir / "flatfield_inverse_gain.npy"
        metadata_path = gain_path.with_suffix(gain_path.suffix + ".json")

        if profile_path.exists() and not args.flatfield_recompute_profile:
            print(f"Loading existing flatfield profile {profile_path}", flush=True)
            profile = np.load(profile_path)
        else:
            profile_path.parent.mkdir(parents=True, exist_ok=True)
            profile = compute_average_flatfield_profile(
                sources,
                args.flatfield_profile_block_z,
                args.workers,
            )
            np.save(profile_path, profile.astype(np.float32))
            print(f"Wrote flatfield profile {profile_path}", flush=True)

        gain_path.parent.mkdir(parents=True, exist_ok=True)
        gain, mean_profile = build_flatfield_gain(profile, args.flatfield_min_gain, args.flatfield_max_gain)
        np.save(gain_path, gain)
        metadata = write_flatfield_metadata(metadata_path, profile_path, gain_path, profile, gain, mean_profile)
        gain_sha = metadata["gain_sha256"]
        conversion_block_z = min(args.block_z, args.flatfield_apply_block_z)
        print(f"Wrote inverse flatfield gain {gain_path}", flush=True)
        print(f"Wrote flatfield metadata {metadata_path}", flush=True)
        print(
            f"Flatfield mean={mean_profile:.3f}, gain range={metadata['gain_min']:.4f}..{metadata['gain_max']:.4f}, "
            f"gain_sha256={gain_sha}",
            flush=True,
        )
        if args.flatfield_profile_only:
            return 0

    print(
        f"Converting {len(sources)} view(s) with {args.workers} worker(s), "
        f"block_z={conversion_block_z}, flatfield={args.flatfield}, subtract={args.subtract}",
        flush=True,
    )
    tasks = [
        (
            source,
            str(tile_files[source["setup_id"]]),
            conversion_block_z,
            args.force,
            args.flatfield,
            str(gain_path) if gain_path else None,
            gain_sha,
            args.subtract,
        )
        for source in sources
    ]
    with concurrent.futures.ProcessPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(convert_source, task) for task in tasks]
        for future in concurrent.futures.as_completed(futures):
            setup_id, status, path, seconds = future.result()
            if seconds:
                print(f"setup{setup_id:02d}: {status} in {seconds / 60:.1f} min -> {path}", flush=True)
            else:
                print(f"setup{setup_id:02d}: {status} -> {path}", flush=True)

    missing = [
        str(tile_files[source["setup_id"]])
        for source in sources
        if not validate_existing(
            tile_files[source["setup_id"]],
            source["dataset_path"],
            source["shape"],
            source["chunks"],
            args.flatfield,
            gain_sha,
            args.subtract,
        )
    ]
    if missing:
        raise RuntimeError("Missing or invalid converted view(s):\n" + "\n".join(missing))

    out_h5 = out_dir / "raw_int16_bdv.h5"
    out_xml = out_dir / "dataset.xml"
    write_bdv_h5(out_h5, sources, tile_files)
    patch_xml(source_xml, out_xml, out_h5, selected)
    print(f"Wrote {out_h5}", flush=True)
    print(f"Wrote {out_xml}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
