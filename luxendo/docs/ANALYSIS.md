# Luxendo BigStitcher-Spark Pipeline — Analysis, Comparison & Affine Registration

This document analyzes the Luxendo headless pipeline vendored under `luxendo/`, compares it to the
base [BigStitcher-Spark](https://github.com/JaneliaSciComp/BigStitcher-Spark) it drives, assesses its
affine-registration capability, and documents the interest-point affine path that was added.

## 1. What this project is

It is **not a fork** of BigStitcher-Spark. It is an *orchestration layer* — Windows PowerShell +
Python + a few standalone Java helpers — that automates an end-to-end workflow on Luxendo `.lux.h5`
acquisitions by calling the unmodified BigStitcher-Spark CLI.

The unified entry point is `luxendo/tools/run_pipeline.ps1` (the older `run_isotropic_pipeline.ps1`
is now a thin back-compat shim). A typical run does:

1. **uint16 → int16 bit-pattern mirror** (`create_bdv_int16_mirror.py`) — see §3.
2. **N5 multi-resolution resave** (`resave`).
3. (multiview/stitching only) **tiles-as-angles XML normalization**.
4. **Registration**: phase-correlation `stitching`, or — now — interest-point detect/match (§5).
5. **Global solve** (`solver`).
6. **Transform quantification** (`quantify_registration_transforms.py`).
7. **Native/isotropic fusion XML** (`create_native_fusion_xml.py`).
8. **Fusion** (`create-fusion-container` + `affine-fusion`).
9. Optional **separate registered-view export** and **BigTIFF export** with ImageJ calibration.

`luxendo/tools/bigstitcher-spark.ps1` is a generic dispatcher that maps friendly command names
(`resave`, `detect-interestpoints`, `match-interestpoints`, `stitching`, `solver`,
`create-fusion-container`, `affine-fusion`, `nonrigid-fusion`, …) to the Spark main classes.

## 2. How it differs from base BigStitcher-Spark

**Adds (Luxendo-specific glue not present in base):**

- uint16→int16 bit-pattern mirror to dodge the BDV/HDF5 `H5T__conv_ushort_short` crash on values
  above 32767 (`create_bdv_int16_mirror.py`).
- Background subtraction before registration / before fusion (`-RegSubtract`, `-FusionSubtract`),
  implemented via the `OffsetN5Dataset.java` helper.
- Output-grid rescaling from physical/global units into a native or isotropic voxel grid so Spark
  fusion at scale 1 samples one voxel rather than one micron (`create_native_fusion_xml.py`).
- `-FirstStackAsZero` (define output axes from the first acquired stack) and tiles-as-angles
  normalization for mislabeled multiview metadata.
- Separate registered-view export, BigTIFF export with ImageJ/Bio-Formats calibration, and per-view
  transform quantification logs.

**Uses only a subset of base registration.** Historically the pipeline only ran `stitching` +
`solver`. It never invoked `detect-interestpoints`, `match-interestpoints`, `nonrigid-fusion`, or the
intensity-matching commands — even though the dispatcher already maps them. This is exactly the gap
that limited affine registration (§4) and that the new IP mode (§5) closes.

## 3. The int16 bit-pattern mirror (important context)

Luxendo tiles are HDF5 `uint16`, but the BDV HDF5 Java reader reads into signed Java `short[]`, so
blocks with values > 32767 fail native HDF5 conversion. The fix is a BDV-compatible mirror that
preserves the exact 16-bit pattern via `uint16.view(int16)`. This is correct for phase-correlation
stitching and for fusion (intensities are reinterpreted consistently), but note that values above
32767 appear **negative** in the mirror. This matters for interest-point detection (§5/§6).

## 4. Affine registration assessment (the core finding)

Base BigStitcher-Spark can do **true affine** registration in two related ways:

- `match-interestpoints -tm AFFINE` followed by `solver -s IP -tm AFFINE`: DoG interest points are
  matched pairwise with geometric hashing / ICP under RANSAC, producing **many point
  correspondences per overlapping pair**, which fully constrain a 12-DOF affine model per view.
- (`solver -tm AFFINE` on those interest-point matches.)

The wrapper *exposed* `-SolverTransformModel AFFINE` / `-SolverRegularizationModel AFFINE`, but it
only ever fed the solver **phase-correlation `stitching` links, which are translation-only** — one
shift vector per overlapping pair. **An affine global solve fed only translation links is badly
under-constrained**: a single relative translation per pair carries no information about rotation,
shear, or scale, so the "affine" result collapses toward translation. In other words, the old
pipeline could *request* affine but could not *deliver* a meaningful one.

Secondary issue: the docs advertised `--method TWO_ROUND_ITERATIVE`, while the script hard-coded
`--method ONE_ROUND_SIMPLE --maxError 10.0`.

## 5. What was implemented: an interest-point affine path

`run_pipeline.ps1` now has `-RegistrationMode STITCHING` (default, unchanged behavior) and
`-RegistrationMode INTERESTPOINTS`. The IP path runs, on the resaved N5 XML:

```
detect-interestpoints -l <IpLabel> -s <IpSigma> -t <IpThreshold> -dsxy <..> -dsz <..>
    --type <MIN|MAX|BOTH> --minIntensity <..> --maxIntensity <..> [--overlappingOnly] [--maxSpots N]
match-interestpoints  -l <IpLabel> -m <FAST_ROTATION|FAST_TRANSLATION|PRECISE_TRANSLATION>
    -tm AFFINE -rm <..> --clearCorrespondences [--groupTiles (multiview)]
solver                -s IP -l <IpLabel> -tm AFFINE -rm <..> --method TWO_ROUND_ITERATIVE --maxError 5.0
# optional with -IpRefineICP: re-match with -m ICP, then solve again
```

Key behaviors:

- In IP mode `-SolverTransformModel` defaults to **AFFINE** (vs TRANSLATION for stitching).
- For multiview acquisitions, matching/solving align angle-to-angle via `--groupTiles`. The
  stitching-only tiles-as-angles XML hack is *not* used in IP mode (and is ignored with a note).
- `-IpRefineICP` adds an ICP fine-alignment round (ICP only works once the coarse alignment is good,
  per the BigStitcher docs).
- Downstream (quantification, fusion XML, container, fusion) is unchanged — it just reads the
  composite `ViewRegistration` transforms the IP solver writes. `quantify_registration_transforms.py`
  already reports per-view rotation angle, singular values, determinant, and off-identity linear
  terms, which now serve as a **built-in QA readout** that the affine solve actually moved beyond
  translation.

### Example (multiview, real affine)

```powershell
tools\run_pipeline.ps1 `
  -DatasetRoot "D:\path\to\multiview_dataset" `
  -RegistrationMode INTERESTPOINTS `
  -IpLabel nuclei -IpSigma 1.8 -IpThreshold 0.008 `
  -IpMinIntensity -32768 -IpMaxIntensity 32767 `
  -IpMatchMethod FAST_ROTATION -IpRefineICP `
  -SolverTransformModel AFFINE -SolverRegularizationModel RIGID `
  -SeparateViews -ExportBigTiff
```

### Example (tiled, translation stitching — unchanged default)

```powershell
tools\run_pipeline.ps1 -DatasetRoot "D:\path\to\tiled_dataset"
# auto-detects tiled -> anisotropic sampling, no --compareAngles, translation solve
```

## 6. Unified single script: auto-detection + sampling toggle

`run_pipeline.ps1` now handles **both** acquisition styles from one entry point:

- `-AcquisitionType auto|tiled|multiview` — `auto` inspects `bdv.xml` ViewSetups: **>1 distinct angle
  ⇒ multiview**, otherwise **tiled**.
- `-Sampling auto|anisotropic|isotropic` — `auto` resolves to **anisotropic for tiled** (preserve
  native z spacing for single-angle grids) and **isotropic for multiview** (orthogonal/rotated views
  share one cubic grid).

Profile-driven defaults (each overridable by the user):

| concern | tiled (1 angle) | multiview (opposing/rotated) |
|---|---|---|
| `Sampling=auto` | anisotropic | isotropic |
| stitching pairing | tile overlap (no `--compareAngles`) | `--compareAngles` (+ tiles-as-angles if tiles encode angles) |
| `-FirstStackAsZero` | off | on |
| fusion XML flag | `--sampling anisotropic` | `--sampling isotropic` |

The previously hard-coded stitching parameters are now flags: `-StitchDownsampling`, `-StitchPeaks`,
`-StitchMinR`, `-StitchMaxR`. The detected/effective profile is logged to `logs/pipeline_status.log`.

## 7. Caveat: intensity substrate for interest-point detection

DoG detection runs on the signed-int16 mirror, where voxels above 32767 wrap negative. IP mode
therefore **requires** `-IpMinIntensity`/`-IpMaxIntensity` and warns about the signed range. For
full-range data, `-32768`/`32767` is safe but coarse; better values come from inspecting the data
(or BigStitcher's interactive detection). A cleaner long-term fix is a detection-only mirror that
maps uint16 to true (scaled) intensities — see follow-ups.

## 8. Verification

- `python3 -m py_compile luxendo/tools/*.py` passes.
- The PowerShell flags emitted by the IP branch were cross-checked against the BigStitcher-Spark
  source `@Option` definitions in `SparkInterestPointDetection.java`,
  `SparkGeometricDescriptorMatching.java`, and `Solver.java`.
- End-to-end runs require Windows + Fiji JDK + a built BigStitcher-Spark jar + Luxendo data, so they
  are not runnable in CI. Evidence of a successful affine solve: non-zero
  `composite_rotation_angle_deg` / off-identity linear terms in
  `logs/registration_transform_quantification.tsv`, and a solver log reporting IP correspondences and
  final per-view models.

## 9. Further recommendations (prioritized; #1 done)

1. **Interest-point affine path** — implemented (this change). Biggest capability gap.
2. **True-intensity mirror for IP detection** — avoid DoG on wrapped negative int16 values.
3. **Non-rigid fusion** — expose `nonrigid-fusion` using IP correspondences for distortion-heavy
   multiview data (ICP produces dense matches well-suited to this).
4. **Intensity correction** — wire in `match-intensities` / `solve-intensities` for multiview shading
   differences before fusion.
5. **Parameter auto-estimation** — derive `-IpSigma`/`-IpThreshold`/intensity range from the data.
6. **Cross-platform port** — the wrapper is Windows-only; base Spark runs on Linux/cloud, so a bash
   equivalent would unlock cluster/cloud execution.
