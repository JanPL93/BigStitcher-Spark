# BigStitcher Spark Luxendo Pipeline

Tools for converting Luxendo/BDV datasets into BigStitcher-Spark compatible N5 inputs, registering multiview/tiled acquisitions, fusing native or isotropic outputs, exporting BigTIFF, and exporting registered separate views for overlay/QA.

> New machine? See [`INSTALL.md`](INSTALL.md) for from-scratch build/setup instructions (JDK, Maven, Fiji, Python).
>
> See [`docs/ANALYSIS.md`](docs/ANALYSIS.md) for how this pipeline relates to base BigStitcher-Spark and details of the affine registration capability.

## Current Pipeline

Primary entry point (unified for tiled and multiview acquisitions):

```powershell
tools\run_pipeline.ps1
```

`tools\run_isotropic_pipeline.ps1` is kept as a back-compat shim that forwards to `run_pipeline.ps1 -Sampling isotropic`.

### Acquisition handling and sampling

- `-AcquisitionType auto|tiled|multiview` (default `auto`): `auto` inspects `bdv.xml` — more than one
  distinct angle ⇒ `multiview`, otherwise `tiled`.
- `-Sampling auto|anisotropic|isotropic` (default `auto`): `auto` ⇒ anisotropic for tiled, isotropic
  for multiview.

For a tiled acquisition `auto` keeps native anisotropy and uses tile-overlap stitching; for a
multiview acquisition it uses an isotropic grid, `--compareAngles`, and `-FirstStackAsZero`. Explicit
flags always override the detected defaults.

### Registration engine

- `-RegistrationMode STITCHING` (default): phase-correlation pairwise stitching (translation links).
  Parameters: `-StitchDownsampling`, `-StitchPeaks`, `-StitchMinR`, `-StitchMaxR`.
- `-RegistrationMode INTERESTPOINTS`: **true affine registration** via DoG interest-point detection +
  geometric/ICP matching + global solve. In this mode `-SolverTransformModel` defaults to `AFFINE`.
  Parameters: `-IpLabel`, `-IpSigma`, `-IpThreshold`, `-IpMinIntensity`, `-IpMaxIntensity`
  (both required), `-IpDownsampleXY`, `-IpDownsampleZ`, `-IpType`, `-IpMatchMethod`
  (`FAST_ROTATION`/`FAST_TRANSLATION`/`PRECISE_TRANSLATION`), `-IpOverlappingOnly`, `-IpRefineICP`
  (adds an ICP refinement round), `-IpMaxSpots`. Note: detection runs on the signed-int16 mirror, so
  intensities above 32767 wrap negative — choose `-IpMinIntensity`/`-IpMaxIntensity` accordingly.

### Other features

- `-RegSubtract <int>`: subtracts a background value before registration. Default: `150`.
- `-FusionSubtract <int>`: subtracts a background value from fused outputs. Default: `100`.
- `-TilesAsAnglesReg`: (stitching only) normalizes tile IDs so tiles can register as angles.
- `-FirstStackAsZero`: uses the first stack as the zero-angle reference before relative rotations.
- `-SeparateViews`: exports registered individual views in the final fused sample space.
- `-ExportBigTiff`: exports fused and separate-view N5 datasets to BigTIFF with ImageJ pixel-size metadata.
- `-SolverTransformModel` / `-SolverRegularizationModel`: `TRANSLATION`, `RIGID`, or `AFFINE`.
- Registration transform quantification logs are written to `logs\registration_transform_quantification.tsv` and `.json`.

## Dependencies

This repository package intentionally does not include local data, processed outputs, Fiji, Maven, BigStitcher-Spark build artifacts, generated Java classes, or old multi-GB ZIP packages.

Expected local layout for the existing scripts:

```text
tools\
  BigStitcher-Spark\
    target\BigStitcher-Spark-0.1.0-SNAPSHOT.jar
    target\dependency\
    target\dependency-provided\
  fiji\Fiji.app\java\win64\*\bin\java.exe
```

Python dependencies:

```text
numpy
h5py
```

See `DEPENDENCIES.md` for more detail.

## Examples

Multiview, translation stitching (auto-detected isotropic output):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File tools\run_pipeline.ps1 `
  -DatasetRoot "D:\path\to\luxendo_dataset" `
  -SeparateViews `
  -ExportBigTiff `
  -RegSubtract 150 `
  -FusionSubtract 100
```

Multiview, **true affine** registration via interest points:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File tools\run_pipeline.ps1 `
  -DatasetRoot "D:\path\to\luxendo_dataset" `
  -RegistrationMode INTERESTPOINTS `
  -IpLabel nuclei -IpSigma 1.8 -IpThreshold 0.008 `
  -IpMinIntensity -32768 -IpMaxIntensity 32767 `
  -IpMatchMethod FAST_ROTATION -IpRefineICP `
  -SolverTransformModel AFFINE -SolverRegularizationModel RIGID `
  -SeparateViews -ExportBigTiff
```

Tiled acquisition (auto-detected anisotropic output, tile-overlap stitching):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File tools\run_pipeline.ps1 `
  -DatasetRoot "D:\path\to\tiled_dataset"
```

Tiled acquisition **with a BigTIFF export** of the fused volume:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File tools\run_pipeline.ps1 `
  -DatasetRoot "D:\path\to\tiled_dataset" `
  -ExportBigTiff
```

### BigTIFF export for tiled data

By default the tiled pipeline writes only the fused multiresolution N5 (open
`output\n5\full_isotropic_fused.n5` via its `bdv_xml\full_isotropic_fused.xml` in BigStitcher/BDV).
Add `-ExportBigTiff` to also write a plain, **uncompressed `uint16` BigTIFF** of the full-resolution
(`s0`) fused volume to `output\bigtiff\full_isotropic_fused_s0_bigtiff.tif`, with ImageJ/Bio-Formats
voxel-size calibration baked in (so Fiji reports the correct micron spacing instead of pixels). A
sidecar `...metadata.txt` records the calibration.

Notes specific to tiled runs:

- **File size.** The BigTIFF is uncompressed, so it is much larger than the zstd-compressed N5 — for
  the example tiled run (`8504 x 6955 x 271`, `uint16`) that is roughly 32 GB on disk versus ~9 GB for
  the N5. Make sure the output drive has room. BigTIFF (not classic TIFF) is used so volumes above the
  4 GB TIFF limit are written correctly.
- **One channel ⇒ one file.** A single-channel tiled dataset produces one BigTIFF. Multi-channel
  datasets produce one BigTIFF per channel (named `..._ch<ID>_s0_bigtiff.tif`) plus a manifest.
- **Already fused?** If the fused N5 already exists and you only want to add the BigTIFF without
  rerunning registration/fusion, use `-OnlyExportBigTiff`:
  ```powershell
  powershell.exe -NoProfile -ExecutionPolicy Bypass `
    -File tools\run_pipeline.ps1 `
    -DatasetRoot "D:\path\to\tiled_dataset" `
    -OutputRoot "D:\path\to\tiled_dataset\processed\<existing_run_folder>" `
    -OnlyExportBigTiff
  ```
  (Pass the same `-OutputRoot` as the original run so it finds the existing fused N5.)
- **Tuning the export.** `-BigTiffCompression` (default `Uncompressed`), `-BigTiffTileSize`
  (default `128`), and `-BigTiffMemoryGb` (default `48`) control the writer. Raise `-BigTiffMemoryGb`
  for very large volumes if the export runs short on heap.

## Output Layout

Each run writes a timestamped `processed\bigstitcher_spark_HHMM_MMDDYYYY...` folder containing:

- `bdv_xml\`: working and output BDV XML files.
- `input\`: converted registration/fusion N5 inputs.
- `logs\`: command logs, status logs, transform summaries, fusion sampling JSON.
- `output\n5\`: final fused N5 and separate-view N5 outputs.
- `output\bigtiff\`: final BigTIFF and separate-view BigTIFF outputs.

## Notes

The scripts are Windows/PowerShell oriented and were developed against local Luxendo BDV XML/HDF5 inputs, BigStitcher, and BigStitcher-Spark.
