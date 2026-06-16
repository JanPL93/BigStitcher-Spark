# BigStitcher / BigStitcher-Spark Luxendo Workflow

Workspace: `D:\0825_UPMC_MERCY\Cleared Eye LCS`

This workflow sets up Fiji BigStitcher and BigStitcher-Spark, verifies the Luxendo `.lux.h5` data, runs headless registration/fusion on compact tiled previews, and completes a full 3D Spark production registration/fusion path ending in a native-resolution, multiresolution fused N5.

## Sources

- BigStitcher: https://imagej.net/plugins/bigstitcher/
- BigStitcher headless notes: https://imagej.net/plugins/bigstitcher/headless
- BigStitcher-Spark: https://github.com/JaneliaSciComp/BigStitcher-Spark
- Luxendo image format reference: https://github.com/Luxendo/luxendo-image
- Fiji downloads: https://imagej.net/software/fiji/downloads

## Installed Tools

- Fiji: `tools\fiji\Fiji.app`
- BigStitcher Fiji plugin: `tools\fiji\Fiji.app\plugins\Big_Stitcher-2.6.1.jar`
- Fiji-bundled Java 8 JDK: `tools\fiji\Fiji.app\java\win64\zulu8.86.0.25-ca-fx-jdk8.0.452-win_x64`
- Maven: `tools\apache-maven-3.9.11`
- BigStitcher-Spark source/build: `tools\BigStitcher-Spark`
- No-space junction for Java/Spark URI handling: `D:\lcs_bs_work` -> this workspace

Use `D:\lcs_bs_work` in Spark `file:///D:/...` URIs. Spark rejected bare Windows drive paths during testing.

## Dataset Layout

The raw dataset contains 25 Luxendo tiles:

- Tile folders: `raw\stack_1-x??-y??_channel_0_obj_bottom`
- Raw data: `Cam_long_00000.lux.h5`
- HDF5 dataset inside each tile: `/Data`
- Shape: `2765 x 2048 x 2048` stored as `uint16`
- Chunking: `64 x 64 x 64`
- Voxel size from JSON/BDV metadata: `2.925 x 2.925 x 5.0 um`

The repository already contained BDV wrappers:

- `bdv.xml`
- `bdv.h5`

`bdv.h5` uses external HDF5 links to the Luxendo tile `/Data` datasets, so BigStitcher and Spark can load the raw Luxendo images through BDV.

## Local Launchers

Spark wrapper:

```powershell
tools\bigstitcher-spark.cmd <command> [args...]
```

Classic Fiji/BigStitcher registration wrapper:

```powershell
tools\run-bigstitcher-classic.cmd <xml> [dsX dsY dsZ minR maxR maxShiftX maxShiftY maxShiftZ]
```

Classic Fiji/BigStitcher fusion wrapper:

```powershell
tools\run-bigstitcher-fusion.cmd <xml> <output.tif> <boundingBoxName> [downsampling]
```

MIP preview project generator:

```powershell
& "C:\Users\SPIM\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" `
  tools\create_mip_bdv_preview.py --out processed\bigstitcher_mip
```

Production signed-int16 mirror generator:

```powershell
python tools\create_int16_bdv_mirror.py --out processed\bigstitcher_spark_full\int16_input --workers 12 --block-z 64
```

Optional all-plane flatfield correction can be enabled in the same mirror generator:

```powershell
python tools\create_int16_bdv_mirror.py `
  --out processed\bigstitcher_spark_full\int16_input_flatfield `
  --workers 12 `
  --block-z 64 `
  --flatfield
```

With `--flatfield`, the script first averages all Z planes from all 25 raw tiles into one XY correction profile, writes `flatfield_profile.npy`, computes the normalized inverse gain map `flatfield_inverse_gain.npy`, and applies that gain map to every raw tile before writing the signed-int16 BDV mirror. Use `--flatfield-profile-only` to build and inspect the profile/gain files without writing the corrected 3D tile mirror.

The preview sections below record the QC runs that validated the setup. Their generated output folders were deleted after the final native fusion completed.

## Verified Spark MIP Registration And Fusion

The compact MIP project uses the 25 existing `Cam_long_00000.max.z.tiff` files as one-slice BDV HDF5 tiles. It keeps the same stage-derived X/Y tile positions as the raw 3D BDV XML.

Create the MIP BDV project:

```powershell
& "C:\Users\SPIM\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" `
  tools\create_mip_bdv_preview.py --out processed\bigstitcher_mip
```

Run Spark pairwise registration:

```powershell
$env:BIGSTITCHER_SPARK_THREADS = "24"
$env:BIGSTITCHER_SPARK_MEMORY_GB = "64"
tools\bigstitcher-spark.cmd stitching `
  -x file:///D:/lcs_bs_work/processed/bigstitcher_mip/dataset.xml `
  --localSparkBindAddress `
  -ds 2,2,1 -p 5 `
  --minR 0.05 --maxR 0.9999 `
  --maxShiftX 800 --maxShiftY 800 --maxShiftZ 0
```

Run Spark global solve:

```powershell
tools\bigstitcher-spark.cmd solver `
  -x file:///D:/lcs_bs_work/processed/bigstitcher_mip/dataset.xml `
  --localSparkBindAddress `
  -s STITCHING `
  -tm TRANSLATION -rm TRANSLATION `
  --method TWO_ROUND_ITERATIVE `
  --relativeThreshold 3.5 `
  --absoluteThreshold 7.0 `
  --maxError 5.0
```

Create and run Spark fusion:

```powershell
tools\bigstitcher-spark.cmd create-fusion-container `
  -x file:///D:/lcs_bs_work/processed/bigstitcher_mip/dataset.xml `
  -o file:///D:/lcs_bs_work/processed/bigstitcher_mip/spark_fused_preview.n5 `
  -s N5 --bdv `
  -xo file:///D:/lcs_bs_work/processed/bigstitcher_mip/spark_fused_preview.xml `
  -b mip_preview_full `
  -d UINT16 -c Gzip -cl 1 `
  -ds 1,1,1 -ds 2,2,1 -ds 4,4,1

tools\bigstitcher-spark.cmd affine-fusion `
  -o file:///D:/lcs_bs_work/processed/bigstitcher_mip/spark_fused_preview.n5 `
  -s N5 `
  -f AVG_BLEND `
  --blockScale 2,2,1
```

Verified outputs:

- Registered XML: `processed\bigstitcher_mip\dataset.xml`
- Spark fused BDV XML: `processed\bigstitcher_mip\spark_fused_preview.xml`
- Spark fused N5: `processed\bigstitcher_mip\spark_fused_preview.n5`
- Spark logs: `processed\bigstitcher_mip\spark_logs`

Observed run summary:

- Spark stitching retained 36 filtered pairwise links.
- Spark solver wrote 25 final models.
- Spark full-resolution MIP fusion completed in about 294 seconds, then wrote two downsample levels.

## Verified Classic Fiji / BigStitcher Headless Registration And Fusion

Create a separate pristine MIP BDV project for classic BigStitcher:

```powershell
& "C:\Users\SPIM\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe" `
  tools\create_mip_bdv_preview.py --out processed\bigstitcher_fiji_mip
```

Run classic BigStitcher registration headlessly:

```powershell
$env:BIGSTITCHER_CLASSIC_MEMORY_GB = "64"
tools\run-bigstitcher-classic.cmd `
  D:\lcs_bs_work\processed\bigstitcher_fiji_mip\dataset.xml `
  2 2 1 `
  0.05 0.9999 `
  800 800 0
```

Run classic BigStitcher/Fiji fusion headlessly:

```powershell
tools\run-bigstitcher-fusion.cmd `
  D:\lcs_bs_work\processed\bigstitcher_fiji_mip\dataset.xml `
  D:\lcs_bs_work\processed\bigstitcher_fiji_mip\fiji_fused_preview_ds4.tif `
  mip_preview_full `
  4
```

Verified outputs:

- Registered XML: `processed\bigstitcher_fiji_mip\dataset.xml`
- Fused TIFF preview: `processed\bigstitcher_fiji_mip\fiji_fused_preview_ds4.tif`
- Quicklook PNG: `processed\bigstitcher_fiji_mip\fiji_fused_preview_ds4.quicklook.png`
- Logs: `processed\bigstitcher_fiji_mip\logs`

Observed run summary:

- Classic BigStitcher retained 36 filtered pairwise links.
- Classic BigStitcher wrote 25 final transform models.
- Classic downsampled fused TIFF is `6376 x 6626`, float32, nonblank.

## Verified Tiny Raw 3D Luxendo Spark Fusion

A small 3D raw Luxendo fusion was run directly from the `.lux.h5`-backed BDV XML. This uses stage metadata transforms, not full raw pairwise registration.

Working XML:

```text
processed\bigstitcher_spark\dataset.xml
```

Added bounding box:

```text
spark_tiny_raw_preview: [4500, -4600, 1000] -> [4800, -4300, 1063]
```

Run:

```powershell
tools\bigstitcher-spark.cmd create-fusion-container `
  -x file:///D:/lcs_bs_work/processed/bigstitcher_spark/dataset.xml `
  -o file:///D:/lcs_bs_work/processed/bigstitcher_spark/raw_tiny_fused.n5 `
  -s N5 --bdv `
  -xo file:///D:/lcs_bs_work/processed/bigstitcher_spark/raw_tiny_fused.xml `
  -b spark_tiny_raw_preview `
  -d UINT16 -c Gzip -cl 1 `
  -ds 1,1,1 -ds 2,2,1

tools\bigstitcher-spark.cmd affine-fusion `
  -o file:///D:/lcs_bs_work/processed/bigstitcher_spark/raw_tiny_fused.n5 `
  -s N5 `
  -f AVG_BLEND `
  --blockScale 1,1,1
```

Verified outputs:

- Raw tiny fused BDV XML: `processed\bigstitcher_spark\raw_tiny_fused.xml`
- Raw tiny fused N5: `processed\bigstitcher_spark\raw_tiny_fused.n5`
- Logs: `processed\bigstitcher_spark\logs`

Observed run summary:

- Fusion target dimensions: `301 x 301 x 64`
- Spark fused all 25 views into the tiny 3D volume.
- Full resolution and one downsample level were written successfully.

## Completed Full 3D Spark Production Run

The full raw Luxendo BDV wrapper initially failed during full-volume Spark resave with:

```text
hdf.hdf5lib.exceptions.HDF5DatatypeInterfaceException:
H5T__conv_ushort_short(): can't handle conversion exception
```

Cause: the Luxendo tile datasets are HDF5 `uint16`, while the BDV HDF5 Java reader reads into signed Java `short[]`. Blocks containing values above 32767 can fail native HDF5 conversion. The fix was to create a BDV-compatible signed-`int16` mirror that preserves the exact 16-bit pixel bit pattern (`uint16.view(int16)`), then point BDV/BigStitcher-Spark at that mirror.

High-resource settings used:

```powershell
$env:BIGSTITCHER_SPARK_THREADS = "46"
$env:BIGSTITCHER_SPARK_MEMORY_GB = "224"
$env:BIGSTITCHER_TMP = "D:\lcs_bs_work\tools\tmp"
```

The GPU was visible on the workstation, but this BigStitcher-Spark path is CPU/JVM/Spark plus disk I/O; no CUDA/GPU fusion backend was used.

### 1. Create the signed-int16 mirror

```powershell
python tools\create_int16_bdv_mirror.py `
  --out D:\lcs_bs_work\processed\bigstitcher_spark_full\int16_input `
  --workers 12 `
  --block-z 64
```

Optional flatfield-corrected branch:

```powershell
# Build only the correction profile and inverse gain first.
python tools\create_int16_bdv_mirror.py `
  --out D:\lcs_bs_work\processed\bigstitcher_spark_full\int16_input_flatfield `
  --workers 12 `
  --flatfield `
  --flatfield-profile-only

# If the profile looks reasonable, write corrected tile mirrors.
python tools\create_int16_bdv_mirror.py `
  --out D:\lcs_bs_work\processed\bigstitcher_spark_full\int16_input_flatfield `
  --workers 12 `
  --block-z 64 `
  --flatfield
```

Flatfield mode details:

- Default enabled mode: `average-all-planes`
- Profile source: every Z plane from every raw tile
- Correction applied to each pixel: `corrected = raw * mean(profile) / profile`
- Default gain clamp: `0.25..4.0`, configurable with `--flatfield-min-gain` and `--flatfield-max-gain`
- Default application block depth: `--flatfield-apply-block-z 16`, to keep per-worker memory reasonable
- Tile HDF5 outputs record `luxendo_mirror_flatfield_mode` and a gain-map hash, so uncorrected mirrors are not accidentally reused for flatfield runs

This all-plane average profile is intended to correct smooth vignetting or edge/corner falloff. It is not a true calibration flat from blank-field images; if the specimen strongly occupies the same XY regions across most planes/tiles, its structure can leak into the estimated profile.

Outputs:

- Input XML for production: `processed\bigstitcher_spark_full\int16_input\dataset.xml`
- BDV HDF5 metadata with external links: `processed\bigstitcher_spark_full\int16_input\raw_int16_bdv.h5`
- Converted tile HDF5 files: `processed\bigstitcher_spark_full\int16_input\tiles\setup??_int16.h5`
- Size: 25 tile files, about `550.06 GB`
- Log: `processed\bigstitcher_spark_full\logs\04_int16_mirror.log`

The completed final fused output documented below was generated from the uncorrected signed-int16 mirror. To use the optional flatfield branch, point the Spark resave step at `int16_input_flatfield\dataset.xml` and carry the same registration/fusion workflow forward from there.

### 2. Resave to multiresolution N5

BigStitcher-Spark was patched locally to shuffle resave blocks before Spark parallelization, so work is spread across tiles rather than processing one setup at a time.

```powershell
tools\bigstitcher-spark.cmd resave `
  -x file:///D:/lcs_bs_work/processed/bigstitcher_spark_full/int16_input/dataset.xml `
  -xo file:///D:/lcs_bs_work/processed/bigstitcher_spark_full/dataset_n5.xml `
  -o file:///D:/lcs_bs_work/processed/bigstitcher_spark_full/raw_multires.n5 `
  --N5 --localSparkBindAddress `
  --blockSize 128,128,64 `
  --blockScale 8,8,1 `
  -ds "1,1,1;2,2,1;4,4,2;8,8,4;16,16,8;32,32,16" `
  -c Zstandard -cl 1
```

Outputs:

- Multiresolution registered-input XML: `processed\bigstitcher_spark_full\dataset_n5.xml`
- Multiresolution N5: `processed\bigstitcher_spark_full\raw_multires.n5`
- Size: about `272.61 GB`
- Log: `processed\bigstitcher_spark_full\logs\01_resave_full_n5.log`

Observed resave timing:

- `s0`: about `11,127,381 ms` (`~3.09 h`)
- `s1`: about `931,488 ms` (`~15.5 min`)
- `s2`: about `70,551 ms`
- `s3`: about `6,030 ms`
- `s4`: about `1,549 ms`
- `s5`: about `1,375 ms`

### 3. Run Spark pairwise stitching

```powershell
tools\bigstitcher-spark.cmd stitching `
  -x file:///D:/lcs_bs_work/processed/bigstitcher_spark_full/dataset_n5.xml `
  --localSparkBindAddress `
  -ds 16,16,8 `
  -p 5 `
  --minR 0.05 --maxR 0.9999 `
  --maxShiftX 600 --maxShiftY 600 --maxShiftZ 300
```

Observed result:

- Log: `processed\bigstitcher_spark_full\logs\02_stitching_full.log`
- Remaining pairs after filters: `53`
- XML backup before stitching: `processed\bigstitcher_spark_full\dataset_n5.before_stitching.xml`

### 4. Run Spark global solve

```powershell
tools\bigstitcher-spark.cmd solver `
  -x file:///D:/lcs_bs_work/processed/bigstitcher_spark_full/dataset_n5.xml `
  --localSparkBindAddress `
  -s STITCHING `
  -tm TRANSLATION -rm TRANSLATION `
  --method TWO_ROUND_ITERATIVE `
  --relativeThreshold 3.5 `
  --absoluteThreshold 7.0 `
  --maxError 5.0
```

Observed result:

- Log: `processed\bigstitcher_spark_full\logs\03_solver_full.log`
- Final models: `25`
- All views connected
- Stitching result entries in XML: `53`
- XML backup before solver: `processed\bigstitcher_spark_full\dataset_n5.before_solver.xml`

### 5. Convert registered transforms to native voxel coordinates

Do not run final full fusion directly from `dataset_n5.xml`. That XML is registered in physical/global units, so Spark fusion at output scale `1,1,1` samples at one global unit, not one native voxel. For this dataset that would create an accidental oversampled output in the 15+ TiB range.

The final workflow instead creates a fusion-specific XML whose composite registered transforms are scaled into native voxel coordinates. By default, `create_native_fusion_xml.py` now infers the native anisotropic spacing from the registration affine column lengths. This is the desired default for single-angle tiled data.

```powershell
python tools\create_native_fusion_xml.py `
  processed\bigstitcher_spark_full\dataset_n5.xml `
  processed\bigstitcher_spark_full\dataset_n5_native.xml
```

The helper scales the already-solved composite transforms by the inferred native voxel size `2.925 x 2.925 x 5.0`, so solver corrections and stage transforms stay consistent. It also clears stale ROI bounding boxes and sets view voxel sizes to `1.0 1.0 1.0` in the fusion XML.

For orthogonal or multiview data where the final fusion should be isotropic, pass the explicit isotropic option:

```powershell
python tools\create_native_fusion_xml.py `
  path\to\registered.xml `
  path\to\registered_isotropic_fusion.xml `
  --isotropic
```

This uses the lateral pixel size for X, Y, and Z. You can also spell this as `--sampling isotropic`. Leave the default anisotropic mode for single-angle tiled acquisitions.

Observed native estimate:

- Estimated dimensions from helper: `8802 x 8740 x 2828`
- Spark fusion-container dimensions: `8803 x 8741 x 2828`
- Estimated uncompressed uint16 `s0` payload: about `405 GiB`

The one-pixel difference in X/Y is from Spark's inclusive bounding interval calculation.

### 6. Run final full native Spark fusion

The final runner is:

```powershell
processed\bigstitcher_spark_full\run_full_native_fusion.ps1
```

It uses the high-resource Spark settings above, regenerates `dataset_n5_native.xml`, creates the full native N5/BDV container, and runs AVG_BLEND affine fusion:

```powershell
net.preibisch.bigstitcher.spark.CreateFusionContainer `
  -x file:///D:/lcs_bs_work/processed/bigstitcher_spark_full/dataset_n5_native.xml `
  -o file:///D:/lcs_bs_work/processed/bigstitcher_spark_full/full_native_fused.n5 `
  -s N5 --bdv `
  -xo file:///D:/lcs_bs_work/processed/bigstitcher_spark_full/full_native_fused.xml `
  -d UINT16 --minIntensity 0 --maxIntensity 65535 `
  -c Zstandard -cl 1 `
  --blockSize 128,128,64 `
  -ds "1,1,1;2,2,1;4,4,2;8,8,4;16,16,8;32,32,16"

net.preibisch.bigstitcher.spark.SparkAffineFusion `
  -o file:///D:/lcs_bs_work/processed/bigstitcher_spark_full/full_native_fused.n5 `
  -s N5 --localSparkBindAddress `
  -f AVG_BLEND `
  --blockScale 2,2,1
```

Final outputs:

- Full native fused multiresolution N5: `processed\bigstitcher_spark_full\full_native_fused.n5`
- Full native fused BDV XML: `processed\bigstitcher_spark_full\full_native_fused.xml`
- Native fusion source XML: `processed\bigstitcher_spark_full\dataset_n5_native.xml`
- Container creation log: `processed\bigstitcher_spark_full\logs\10_create_full_native_fusion_container.log`
- Fusion/downsampling log: `processed\bigstitcher_spark_full\logs\11_affine_fusion_full_native.log`
- Fusion exit code: `0`
- Final N5 size on disk: about `166.24 GB`
- Final N5 file count: `268,097`

Final pyramid metadata:

```text
s0: 8803 x 8741 x 2828, uint16, 128 x 128 x 64, zstd
s1: 4401 x 4370 x 2828, uint16, 128 x 128 x 64, zstd
s2: 2200 x 2185 x 1414, uint16, 128 x 128 x 64, zstd
s3: 1100 x 1092 x 707,  uint16, 128 x 128 x 64, zstd
s4: 550 x 546 x 353,    uint16, 128 x 128 x 64, zstd
s5: 275 x 273 x 176,    uint16, 128 x 128 x 64, zstd
```

Observed timing:

- Full-resolution fusion `s0`: `1,143,086 ms` (`~19.1 min`)
- Downsample `s1`: `545,452 ms` (`~9.1 min`)
- Downsample `s2`: `36,610 ms`
- Downsample `s3`: `5,256 ms`
- Downsample `s4`: `4,154 ms`
- Downsample `s5`: `1,140 ms`
- Total affine fusion/downsampling wall time: `1,735,726 ms` (`~28.9 min`)

Verification:

- `full_native_fused.xml` points to `full_native_fused.n5` and reports setup size `8803 8741 2828`.
- Smallest pyramid level `s5` scan: dimensions `[275, 273, 176]`, `12,867,295` nonzero values, min `0`, max `15617`.
- Full-resolution `s0` sample blocks: dimensions `[8803, 8741, 2828]`; center chunk was fully nonzero with min `87`, max `130`.

### 7. Cleanup after final fusion

The following generated intermediate data copies were removed after `full_native_fused.n5` verified:

- Signed-int16 mirror: `processed\bigstitcher_spark_full\int16_input`, about `550.06 GB`
- Registered multiresolution tile copy: `processed\bigstitcher_spark_full\raw_multires.n5`, about `272.61 GB`
- Old ROI fusion outputs: `full_registered_*_fused.n5/xml/png`
- Preview/smoke outputs: `processed\bigstitcher_mip`, `processed\bigstitcher_fiji_mip`, `processed\bigstitcher_spark`, `processed\bigstitcher_spark_smoke`

The raw Luxendo source data under `raw\...` was not deleted. XML provenance, logs, scripts, and the final fused N5/XML were kept. After cleanup, `dataset_n5.xml` and `dataset_n5_native.xml` still document the registered-input stages, but they reference the deleted `raw_multires.n5`; use `full_native_fused.xml` for visualization.

The warning below appeared often and was harmless for these runs:

```text
Compression 'org.janelia.saalfeldlab.n5.blosc.BloscCompression' could not be registered
```

## Multiview 092453 isotropic BigTIFF export

For the orthogonal multiview dataset:

```text
D:\0825_UPMC_MERCY\2025-08-19-organoid\2025-08-19_092453
```

the isotropic runner supports a BigTIFF export argument:

```powershell
tools\run_isotropic_pipeline.ps1 -ExportBigTiff
```

If the fused N5 already exists and only the BigTIFF copy is needed, use:

```powershell
tools\run_isotropic_pipeline.ps1 -OnlyExportBigTiff
```

This writes a plain uncompressed uint16 BigTIFF stack from `setup0/timepoint0/s0`:

```text
D:\0825_UPMC_MERCY\2025-08-19-organoid\2025-08-19_092453\processed\bigstitcher_spark_HHMM_MMDDYYYY\output\bigtiff\full_isotropic_fused_s0_bigtiff.tif
```

The voxel calibration is recorded in the sidecar:

```text
D:\0825_UPMC_MERCY\2025-08-19-organoid\2025-08-19_092453\processed\bigstitcher_spark_HHMM_MMDDYYYY\output\bigtiff\full_isotropic_fused_s0_bigtiff.tif.metadata.txt
```

The BigTIFF itself also includes ImageJ-compatible calibration metadata. The exporter writes an `ImageDescription`
block with `unit=micron` and `spacing=<z voxel size>`, plus `XResolution`, `YResolution`, and `ResolutionUnit`
TIFF tags for X/Y calibration. This means opening the BigTIFF through Fiji/ImageJ Bio-Formats should report the
correct voxel size instead of defaulting to 1 pixel units. Existing production BigTIFFs from this run were patched
in place, without rewriting pixel data.

The fused BigTIFF calibration was verified through a Bio-Formats/ImageJ virtual open:

```text
unit=micron
pixelWidth=0.29250001358662564
pixelHeight=0.29250001358662564
pixelDepth=0.292500013564
```

## Multiview 092453 separate registered views

The isotropic runner also supports a separate registered-view export:

```powershell
tools\run_isotropic_pipeline.ps1 -SeparateViews
```

When `-SeparateViews` is enabled, the pipeline creates a registered-view N5 after registration and before final fusion. Each input view is resampled by itself into the same final sample space used by `full_isotropic_fused.n5`.

If the registered isotropic XML already exists and only the separate views need to be generated:

```powershell
tools\run_isotropic_pipeline.ps1 -OnlySeparateViews
```

To also write BigTIFF copies for overlay/QC:

```powershell
tools\run_isotropic_pipeline.ps1 -OnlySeparateViews -ExportBigTiff
```

Outputs:

```text
D:\0825_UPMC_MERCY\2025-08-19-organoid\2025-08-19_092453\processed\bigstitcher_spark_HHMM_MMDDYYYY\output\n5\separate_views\registered_views.n5
D:\0825_UPMC_MERCY\2025-08-19-organoid\2025-08-19_092453\processed\bigstitcher_spark_HHMM_MMDDYYYY\bdv_xml\registered_views.xml
D:\0825_UPMC_MERCY\2025-08-19-organoid\2025-08-19_092453\processed\bigstitcher_spark_HHMM_MMDDYYYY\output\n5\separate_views\registered_views_manifest.tsv
D:\0825_UPMC_MERCY\2025-08-19-organoid\2025-08-19_092453\processed\bigstitcher_spark_HHMM_MMDDYYYY\output\bigtiff\separate_views
```

The registered-view N5/XML has one output channel per input view. For the current dataset:

```text
setup0/timepoint0/s0 = input setup 0, ch:4_st:0_ang:h96-v90_obj:left_cam:left
setup1/timepoint0/s0 = input setup 1, ch:4_st:0_ang:h276-v90_obj:right_cam:right
```

Both registered-view BigTIFFs are plain uncompressed uint16 BigTIFF stacks with the same grid as the final fused BigTIFF: `2307 x 2161 x 2272` as `ZYX`, equivalent to `2272 x 2161 x 2307` as `XYZ`, with voxel size `0.292500013564 micrometer` in X/Y/Z. Their TIFF/ImageJ calibration metadata is written the same way as the fused BigTIFF, so they can be opened for overlay/QC in the same calibrated sample space.

## Tile-as-angle registration normalization

Some multiview Luxendo/BDV exports can encode each view as a different tile even when the views are actually different angles of the same sample. BigStitcher-Spark stitching is run with `--compareAngles`; if the views are split across different tile IDs, no useful angle pairs may be generated.

For those datasets, run the pipeline with:

```powershell
tools\run_isotropic_pipeline.ps1 -TilesAsAnglesReg
```

`-TilesAsAnglesReg` normalizes only the generated working XML used for registration. It does not edit the source dataset `bdv.xml`.

When enabled, the script:

- waits until `dataset_n5.xml` has been generated by the N5 resave step
- backs it up as `bdv_xml\dataset_n5.before_tiles_as_angles_reg.xml`
- sets every `ViewSetup/attributes/tile` value in the working XML to `0`
- replaces the working XML tile attribute table with a single tile named `tiles_as_angles_reg`
- preserves setup IDs, setup names, angle IDs, channels, image paths, voxel sizes, and registrations
- writes a log and exit code under `logs\01b_tiles_as_angles_reg.*`

Use this only for multiview datasets where the metadata incorrectly places angle views into separate tile IDs. Do not use it for normal single-angle tiled acquisitions, where tile IDs represent real stage positions and must be preserved.

## First-stack-as-zero fusion frame

For opposing-view multiview datasets, the angle metadata can put every view into a rotated global frame. If that global frame is not aligned with the first acquired stack, the final fusion can rotate and interpolate even the reference stack. To preserve the best native XY plane for that first stack, run the pipeline with:

```powershell
tools\run_isotropic_pipeline.ps1 -FirstStackAsZero
```

`-FirstStackAsZero` changes only the fusion XML generated after registration. It takes the earliest timepoint and smallest setup ID as the reference stack, treats that stack's orientation as zero, and writes all other registered stacks relative to that frame before voxel scaling. The final fused N5, final BigTIFF export, and `-SeparateViews` outputs all use the same adjusted sample space.

Use it for multiview datasets where the first stack should define the output XY axes. It does not edit the source `bdv.xml`, does not change the registration solver output, and does not remove the relative rotation needed to bring opposing views into the same sample space.

For final fusion, the runner creates one fused output timepoint and fuses `timepoint0` explicitly, one output channel at a time, using the registered input views for that channel. This avoids Spark attempting to fuse every timepoint listed in Luxendo/BDV metadata when the intended output is a single registered volume per channel.

To process only specific input channels, pass a comma-separated channel ID list. For example, `-ChannelIds 1` mirrors and resaves only ViewSetups whose `attributes/channel` is `1`, so other channels are ignored from the start of the run.

The registration solver model can be changed with `-SolverTransformModel`. The default is `TRANSLATION`; use `AFFINE` to request an affine solve. Unless `-SolverRegularizationModel` is supplied, the regularization model follows the transform model.

Example Fish Eye run:

```powershell
tools\run_isotropic_pipeline.ps1 `
  -DatasetRoot "D:\0825_UPMC_MERCY\2025-08-12_150936 Fish Eye" `
  -TilesAsAnglesReg `
  -FirstStackAsZero `
  -SeparateViews `
  -ExportBigTiff
```

When `-OutputRoot` is omitted, the runner writes to:

```text
<dataset>\processed\bigstitcher_spark_HHMM_MMDDYYYY
```

The timestamped output root is organized as:

```text
bdv_xml\
  dataset_n5.before_solver.xml
  dataset_n5.before_stitching.xml
  dataset_n5.before_tiles_as_angles_reg.xml
  dataset_n5.xml
  dataset_n5.xml~1
  dataset_n5.xml~2
  dataset_n5_isotropic.xml
  full_isotropic_fused.xml
  registered_views.xml
input\
  int16_input\
  raw_multires.n5
output\
  n5\
    full_isotropic_fused.n5
    separate_views\registered_views.n5
  bigtiff\
    full_isotropic_fused_s0_bigtiff.tif
    full_isotropic_fused_s0_bigtiff.tif.metadata.txt
    separate_views\
logs\
```

BDV XML files are written under `bdv_xml` and use relative paths to the moved N5 containers in `input` and `output\n5`.

The timestamp is captured when processing starts. Runtime metadata is kept in the output `logs` folder:

```text
logs\pipeline.command.txt
logs\pipeline.pid
logs\pipeline_status.log
logs\fusion_sampling.json
logs\pipeline.stdout.log
logs\pipeline.stderr.log
```

The runner writes `pipeline.command.txt`, `pipeline.pid`, `pipeline_status.log`, and `fusion_sampling.json`. If the run is launched as a background process, redirect stdout/stderr into `logs\pipeline.stdout.log` and `logs\pipeline.stderr.log`.
