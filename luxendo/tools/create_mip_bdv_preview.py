import re
import sys
import argparse
from pathlib import Path
from xml.etree import ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parent / "python"))

import h5py
import numpy as np
from PIL import Image


WORKSPACE = Path(__file__).resolve().parents[1]
SOURCE_XML = WORKSPACE / "bdv.xml"
RAW_DIR = WORKSPACE / "raw"
OUT_DIR = WORKSPACE / "processed" / "bigstitcher_mip"
OUT_XML = OUT_DIR / "dataset.xml"
OUT_H5 = OUT_DIR / "mip_bdv.h5"


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


def load_mip(path):
    with Image.open(path) as im:
        arr = np.array(im)
    if arr.dtype != np.uint16:
        arr = arr.astype(np.uint16)
    return arr.view(np.int16)


def write_h5(tile_dirs, out_h5):
    if out_h5.exists():
        out_h5.unlink()

    with h5py.File(out_h5, "w") as h5:
        for setup_id, tile_dir in enumerate(tile_dirs):
            setup = h5.create_group(f"s{setup_id:02d}")
            setup.create_dataset("resolutions", data=np.array([[1.0, 1.0, 1.0]], dtype=np.float64))
            setup.create_dataset("subdivisions", data=np.array([[256.0, 256.0, 1.0]], dtype=np.float64))

            mip_path = tile_dir / "mip" / "Cam_long_00000.max.z.tiff"
            img = load_mip(mip_path)
            data = img[np.newaxis, :, :]
            group = h5.create_group(f"t00000/s{setup_id:02d}/0")
            group.create_dataset(
                "cells",
                data=data,
                chunks=(1, 256, 256),
                compression="gzip",
                compression_opts=1,
            )


def patch_xml(out_xml, out_h5):
    ET.register_namespace("", "")
    tree = ET.parse(SOURCE_XML)
    root = tree.getroot()

    base_path = root.find("BasePath")
    base_path.text = "."
    base_path.set("type", "relative")

    image_loader = root.find("./SequenceDescription/ImageLoader")
    image_loader.set("format", "bdv.hdf5")
    hdf5 = image_loader.find("hdf5")
    hdf5.text = out_h5.name
    hdf5.set("type", "relative")

    for size in root.findall("./SequenceDescription/ViewSetups/ViewSetup/size"):
        size.text = "2048 2048 1"

    for voxel in root.findall("./SequenceDescription/ViewSetups/ViewSetup/voxelSize/size"):
        voxel.text = "2.925 2.925 1.0"

    for affine in root.findall("./ViewRegistrations/ViewRegistration/ViewTransform/affine"):
        values = [float(v) for v in affine.text.split()]
        # Preserve stage-derived X/Y translation from the 3D Luxendo XML,
        # but flatten the max-Z projection into a one-slice Z=0 plane.
        affine.text = (
            f"{values[0]} 0.0 0.0 {values[3]} "
            f"0.0 {values[5]} 0.0 {values[7]} "
            "0.0 0.0 1.0 0.0"
        )

    boxes = root.find("BoundingBoxes")
    if boxes is None:
        boxes = ET.SubElement(root, "BoundingBoxes")
    for existing in list(boxes):
        if existing.get("name") == "mip_preview_full":
            boxes.remove(existing)
    box = ET.SubElement(boxes, "BoundingBoxDefinition", {"name": "mip_preview_full"})
    ET.SubElement(box, "min").text = "-10000 -15000 0"
    ET.SubElement(box, "max").text = "15500 11500 0"

    stitching = root.find("StitchingResults")
    if stitching is None:
        ET.SubElement(root, "StitchingResults")

    indent(root)
    tree.write(out_xml, encoding="UTF-8", xml_declaration=True)


def main():
    parser = argparse.ArgumentParser(description="Create a compact BDV HDF5/XML project from Luxendo max-Z MIPs.")
    parser.add_argument("--out", default=str(OUT_DIR), help="Output directory for dataset.xml and mip_bdv.h5")
    args = parser.parse_args()

    out_dir = Path(args.out)
    if not out_dir.is_absolute():
        out_dir = WORKSPACE / out_dir
    out_xml = out_dir / "dataset.xml"
    out_h5 = out_dir / "mip_bdv.h5"

    out_dir.mkdir(parents=True, exist_ok=True)
    tile_dirs = sorted(
        [p for p in RAW_DIR.glob("stack_1-x*-y*_channel_0_obj_bottom") if p.is_dir()],
        key=lambda p: tuple(map(int, re.search(r"x(\d+)-y(\d+)", p.name).groups())),
    )
    if len(tile_dirs) != 25:
        raise RuntimeError(f"Expected 25 raw Luxendo tile folders, found {len(tile_dirs)}")

    write_h5(tile_dirs, out_h5)
    patch_xml(out_xml, out_h5)
    print(f"Wrote {out_xml}")
    print(f"Wrote {out_h5}")


if __name__ == "__main__":
    main()
