import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path
from xml.etree import ElementTree as ET

import numpy as np
try:
    import h5py
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parent / "python"))
    import h5py


WORKSPACE = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE_XML = WORKSPACE / "processed" / "bigstitcher_spark_full" / "dataset_raw.xml"
RAW_DIR = WORKSPACE / "raw"
DEFAULT_OUT_DIR = WORKSPACE / "processed" / "bigstitcher_spark_full" / "int16_input"
RAW_DATASET = "Data"
SHAPE_ZYX = (2765, 2048, 2048)
CHUNKS_ZYX = (64, 64, 64)
FLATFIELD_NONE = "none"
FLATFIELD_AVERAGE_ALL_PLANES = "average-all-planes"
FLATFIELD_MODE_ATTR = "luxendo_mirror_flatfield_mode"
FLATFIELD_GAIN_SHA_ATTR = "luxendo_mirror_flatfield_gain_sha256"


def indent(elem, level=0):
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


def tile_sort_key(path):
    match = re.search(r"x(\d+)-y(\d+)", path.name)
    if not match:
        raise ValueError(f"Cannot parse x/y tile coordinates from {path}")
    return tuple(map(int, match.groups()))


def find_tiles(raw_dir):
    tiles = sorted(
        [p for p in raw_dir.glob("stack_1-x*-y*_channel_0_obj_bottom") if p.is_dir()],
        key=tile_sort_key,
    )
    if len(tiles) != 25:
        raise RuntimeError(f"Expected 25 raw Luxendo tile folders, found {len(tiles)}")
    return tiles


def attr_to_str(value):
    if value is None:
        return None
    if isinstance(value, bytes):
        return value.decode("utf-8")
    return str(value)


def validate_existing(path, flatfield_mode=FLATFIELD_NONE, gain_sha=None):
    try:
        with h5py.File(path, "r") as h5:
            data = h5[RAW_DATASET]
            if not (data.shape == SHAPE_ZYX and data.dtype == np.dtype("int16") and data.chunks == CHUNKS_ZYX):
                return False

            existing_mode = attr_to_str(data.attrs.get(FLATFIELD_MODE_ATTR, FLATFIELD_NONE))
            if flatfield_mode == FLATFIELD_NONE:
                return existing_mode in (None, FLATFIELD_NONE)

            if existing_mode != flatfield_mode:
                return False
            if gain_sha:
                existing_sha = attr_to_str(data.attrs.get(FLATFIELD_GAIN_SHA_ATTR))
                return existing_sha == gain_sha
            return True
    except Exception:
        return False


def copy_attrs(src, dst):
    for key, value in src.attrs.items():
        dst.attrs[key] = value


def raw_tile_path(tile_dir):
    return tile_dir / "Cam_long_00000.lux.h5"


def compute_tile_profile(task):
    setup_id, src_path, block_z = task
    started = time.time()
    with h5py.File(src_path, "r", rdcc_nbytes=512 * 1024 * 1024) as src_h5:
        src = src_h5[RAW_DATASET]
        if src.shape != SHAPE_ZYX:
            raise RuntimeError(f"{src_path} has shape {src.shape}, expected {SHAPE_ZYX}")
        if src.dtype != np.dtype("uint16"):
            raise RuntimeError(f"{src_path} has dtype {src.dtype}, expected uint16")

        profile_sum = np.zeros(src.shape[1:], dtype=np.float64)
        plane_count = 0
        for z0 in range(0, src.shape[0], block_z):
            z1 = min(z0 + block_z, src.shape[0])
            block = src[z0:z1, :, :]
            profile_sum += block.sum(axis=0, dtype=np.float64)
            plane_count += z1 - z0

    return setup_id, profile_sum, plane_count, time.time() - started


def compute_average_flatfield_profile(tile_paths, block_z, workers):
    total_sum = None
    total_count = 0
    tasks = [(setup_id, str(path), block_z) for setup_id, path in enumerate(tile_paths)]

    print(
        f"Computing flatfield profile from all planes in {len(tasks)} tile(s) "
        f"with {workers} worker(s), block_z={block_z}",
        flush=True,
    )
    with concurrent.futures.ProcessPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(compute_tile_profile, task) for task in tasks]
        for future in concurrent.futures.as_completed(futures):
            setup_id, profile_sum, plane_count, seconds = future.result()
            if total_sum is None:
                total_sum = profile_sum
            else:
                total_sum += profile_sum
            total_count += plane_count
            print(
                f"setup{setup_id:02d}: added {plane_count} plane(s) to flatfield profile "
                f"in {seconds / 60:.1f} min",
                flush=True,
            )

    if total_sum is None or total_count == 0:
        raise RuntimeError("No data was accumulated for the flatfield profile")
    return total_sum / total_count


def sha256_array(array):
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
    if mean_profile <= 0:
        raise RuntimeError(f"Flatfield profile mean is not positive: {mean_profile}")

    denominator = profile.copy()
    denominator[~positive] = mean_profile
    if max_gain and max_gain > 0:
        denominator = np.maximum(denominator, mean_profile / max_gain)

    gain = mean_profile / denominator
    if min_gain and min_gain > 0:
        gain = np.maximum(gain, min_gain)
    if max_gain and max_gain > 0:
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


def apply_flatfield_block(block, gain):
    corrected = block.astype(np.float32)
    corrected *= gain[np.newaxis, :, :]
    np.rint(corrected, out=corrected)
    np.clip(corrected, 0, 65535, out=corrected)
    return corrected.astype(np.uint16)


def convert_tile(task):
    (
        setup_id,
        src_path,
        dst_path,
        block_z,
        force,
        flatfield_mode,
        gain_path,
        gain_sha,
    ) = task
    dst_path = Path(dst_path)
    partial_path = dst_path.with_suffix(dst_path.suffix + ".partial")

    if dst_path.exists() and not force and validate_existing(dst_path, flatfield_mode, gain_sha):
        return setup_id, "skipped", str(dst_path), 0.0

    dst_path.parent.mkdir(parents=True, exist_ok=True)
    if partial_path.exists():
        partial_path.unlink()

    started = time.time()
    gain = None
    if flatfield_mode != FLATFIELD_NONE:
        gain = np.load(gain_path).astype(np.float32, copy=False)
        if gain.shape != SHAPE_ZYX[1:]:
            raise RuntimeError(f"{gain_path} has shape {gain.shape}, expected {SHAPE_ZYX[1:]}")

    with h5py.File(src_path, "r", rdcc_nbytes=512 * 1024 * 1024) as src_h5:
        src = src_h5[RAW_DATASET]
        if src.shape != SHAPE_ZYX:
            raise RuntimeError(f"{src_path} has shape {src.shape}, expected {SHAPE_ZYX}")
        if src.dtype != np.dtype("uint16"):
            raise RuntimeError(f"{src_path} has dtype {src.dtype}, expected uint16")

        with h5py.File(partial_path, "w", rdcc_nbytes=512 * 1024 * 1024) as dst_h5:
            dst = dst_h5.create_dataset(
                RAW_DATASET,
                shape=src.shape,
                dtype=np.int16,
                chunks=CHUNKS_ZYX,
            )
            copy_attrs(src, dst)
            dst.attrs[FLATFIELD_MODE_ATTR] = flatfield_mode
            if gain_sha:
                dst.attrs[FLATFIELD_GAIN_SHA_ATTR] = gain_sha

            for z0 in range(0, src.shape[0], block_z):
                z1 = min(z0 + block_z, src.shape[0])
                block = src[z0:z1, :, :]
                if gain is None:
                    dst[z0:z1, :, :] = block.view(np.int16)
                else:
                    corrected = apply_flatfield_block(block, gain)
                    dst[z0:z1, :, :] = corrected.view(np.int16)

    if dst_path.exists():
        dst_path.unlink()
    os.replace(partial_path, dst_path)
    return setup_id, "converted", str(dst_path), time.time() - started


def write_bdv_h5(out_h5, tile_files):
    if out_h5.exists():
        out_h5.unlink()

    with h5py.File(out_h5, "w") as h5:
        for setup_id, tile_file in enumerate(tile_files):
            setup = h5.create_group(f"s{setup_id:02d}")
            setup.create_dataset("resolutions", data=np.array([[1.0, 1.0, 1.0]], dtype=np.float64))
            setup.create_dataset("subdivisions", data=np.array([[64.0, 64.0, 64.0]], dtype=np.float64))

            group = h5.create_group(f"t00000/s{setup_id:02d}/0")
            rel = Path("tiles") / Path(tile_file).name
            group["cells"] = h5py.ExternalLink(rel.as_posix(), RAW_DATASET)


def patch_xml(source_xml, out_xml, out_h5):
    tree = ET.parse(source_xml)
    root = tree.getroot()

    base_path = root.find("BasePath")
    base_path.text = "."
    base_path.set("type", "relative")

    image_loader = root.find("./SequenceDescription/ImageLoader")
    image_loader.set("format", "bdv.hdf5")
    hdf5 = image_loader.find("hdf5")
    hdf5.text = out_h5.name
    hdf5.set("type", "relative")

    if root.find("StitchingResults") is None:
        ET.SubElement(root, "StitchingResults")

    indent(root)
    tree.write(out_xml, encoding="UTF-8", xml_declaration=True)


def parse_tile_ids(value):
    if not value:
        return None
    ids = []
    for item in value.split(","):
        item = item.strip()
        if not item:
            continue
        ids.append(int(item))
    return set(ids)


def main():
    parser = argparse.ArgumentParser(
        description="Create a BDV-compatible signed-int16 mirror of Luxendo uint16 HDF5 tiles."
    )
    parser.add_argument("--source-xml", default=str(DEFAULT_SOURCE_XML), help="Source BDV XML to patch")
    parser.add_argument("--raw-dir", default=str(RAW_DIR), help="Luxendo raw tile directory")
    parser.add_argument("--out", default=str(DEFAULT_OUT_DIR), help="Output directory")
    parser.add_argument("--workers", type=int, default=max(1, min(8, (os.cpu_count() or 8) // 2)))
    parser.add_argument("--block-z", type=int, default=64, help="Z planes copied per read/write block")
    parser.add_argument("--tiles", default=None, help="Optional comma-separated setup IDs to convert")
    parser.add_argument("--force", action="store_true", help="Rebuild existing tile mirrors")
    parser.add_argument(
        "--flatfield",
        nargs="?",
        const=FLATFIELD_AVERAGE_ALL_PLANES,
        default=FLATFIELD_NONE,
        choices=(FLATFIELD_NONE, FLATFIELD_AVERAGE_ALL_PLANES),
        help=(
            "Optional flatfield mode. Use '--flatfield' or "
            f"'--flatfield {FLATFIELD_AVERAGE_ALL_PLANES}' to average all planes from all tiles "
            "into one XY profile and apply its inverse gain to every tile."
        ),
    )
    parser.add_argument(
        "--flatfield-profile",
        default=None,
        help="Path to the flatfield profile .npy. If missing it is created under the output directory.",
    )
    parser.add_argument(
        "--flatfield-gain",
        default=None,
        help="Path to the inverse flatfield gain .npy. If missing it is created under the output directory.",
    )
    parser.add_argument(
        "--flatfield-recompute-profile",
        action="store_true",
        help="Recompute the flatfield profile even if --flatfield-profile already exists.",
    )
    parser.add_argument(
        "--flatfield-profile-only",
        action="store_true",
        help="Compute/write the flatfield profile and inverse gain, then exit before converting tile mirrors.",
    )
    parser.add_argument(
        "--flatfield-profile-block-z",
        type=int,
        default=64,
        help="Z planes per read while computing the average flatfield profile",
    )
    parser.add_argument(
        "--flatfield-apply-block-z",
        type=int,
        default=16,
        help="Z planes per read/write while applying flatfield correction",
    )
    parser.add_argument(
        "--flatfield-min-gain",
        type=float,
        default=0.25,
        help="Minimum inverse gain applied during flatfield correction",
    )
    parser.add_argument(
        "--flatfield-max-gain",
        type=float,
        default=4.0,
        help="Maximum inverse gain applied during flatfield correction",
    )
    args = parser.parse_args()

    if args.block_z <= 0 or args.flatfield_profile_block_z <= 0 or args.flatfield_apply_block_z <= 0:
        raise ValueError("block-z and flatfield block sizes must be positive")
    if args.flatfield_min_gain <= 0 or args.flatfield_max_gain <= 0:
        raise ValueError("flatfield min/max gain must be positive")
    if args.flatfield_min_gain > args.flatfield_max_gain:
        raise ValueError("flatfield min gain cannot exceed flatfield max gain")
    if args.flatfield_profile_only and args.flatfield == FLATFIELD_NONE:
        raise ValueError("--flatfield-profile-only requires --flatfield")

    source_xml = Path(args.source_xml)
    if not source_xml.is_absolute():
        source_xml = WORKSPACE / source_xml
    raw_dir = Path(args.raw_dir)
    if not raw_dir.is_absolute():
        raw_dir = WORKSPACE / raw_dir
    out_dir = Path(args.out)
    if not out_dir.is_absolute():
        out_dir = WORKSPACE / out_dir

    out_dir.mkdir(parents=True, exist_ok=True)
    tiles_dir = out_dir / "tiles"
    tiles_dir.mkdir(parents=True, exist_ok=True)

    tiles = find_tiles(raw_dir)
    selected = parse_tile_ids(args.tiles)
    tile_files = [tiles_dir / f"setup{setup_id:02d}_int16.h5" for setup_id in range(len(tiles))]

    if args.flatfield == FLATFIELD_NONE:
        gain_sha = None
        gain_path = None
        conversion_block_z = args.block_z
    else:
        tile_paths = []
        for tile_dir in tiles:
            src_path = raw_tile_path(tile_dir)
            if not src_path.exists():
                raise FileNotFoundError(src_path)
            tile_paths.append(src_path)

        profile_path = Path(args.flatfield_profile) if args.flatfield_profile else out_dir / "flatfield_profile.npy"
        if not profile_path.is_absolute():
            profile_path = WORKSPACE / profile_path
        gain_path = Path(args.flatfield_gain) if args.flatfield_gain else out_dir / "flatfield_inverse_gain.npy"
        if not gain_path.is_absolute():
            gain_path = WORKSPACE / gain_path
        metadata_path = gain_path.with_suffix(gain_path.suffix + ".json")

        if profile_path.exists() and not args.flatfield_recompute_profile:
            print(f"Loading existing flatfield profile {profile_path}", flush=True)
            profile = np.load(profile_path)
        else:
            profile_path.parent.mkdir(parents=True, exist_ok=True)
            profile = compute_average_flatfield_profile(
                tile_paths,
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
            return

    tasks = []
    for setup_id, tile_dir in enumerate(tiles):
        if selected is not None and setup_id not in selected:
            continue
        src_path = raw_tile_path(tile_dir)
        if not src_path.exists():
            raise FileNotFoundError(src_path)
        tasks.append(
            (
                setup_id,
                str(src_path),
                str(tile_files[setup_id]),
                conversion_block_z,
                args.force,
                args.flatfield,
                str(gain_path) if gain_path else None,
                gain_sha,
            )
        )

    if tasks:
        print(
            f"Converting {len(tasks)} tile(s) with {args.workers} worker(s), "
            f"block_z={conversion_block_z}, flatfield={args.flatfield}",
            flush=True,
        )
        with concurrent.futures.ProcessPoolExecutor(max_workers=args.workers) as pool:
            futures = [pool.submit(convert_tile, task) for task in tasks]
            for future in concurrent.futures.as_completed(futures):
                setup_id, status, path, seconds = future.result()
                if seconds:
                    print(f"setup{setup_id:02d}: {status} in {seconds / 60:.1f} min -> {path}", flush=True)
                else:
                    print(f"setup{setup_id:02d}: {status} -> {path}", flush=True)

    missing = [str(path) for path in tile_files if not validate_existing(path, args.flatfield, gain_sha)]
    if missing:
        raise RuntimeError("Missing or invalid converted tile(s):\n" + "\n".join(missing))

    out_h5 = out_dir / "raw_int16_bdv.h5"
    out_xml = out_dir / "dataset.xml"
    write_bdv_h5(out_h5, tile_files)
    patch_xml(source_xml, out_xml, out_h5)
    print(f"Wrote {out_h5}", flush=True)
    print(f"Wrote {out_xml}", flush=True)


if __name__ == "__main__":
    main()
