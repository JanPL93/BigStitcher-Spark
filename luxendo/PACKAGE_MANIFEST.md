# Package Manifest

Created: 2026-06-14

Included:

- `README.md`
- `DEPENDENCIES.md`
- `requirements.txt`
- `.gitignore`
- `docs/bigstitcher_luxendo_workflow.md`
- `docs/ANALYSIS.md`
- `tools/run_pipeline.ps1` (unified entry point)
- `tools/*.ps1`
- `tools/*.cmd`
- `tools/*.py`
- `tools/java/*.java`
- `tools/java/check_imagej_calibration.ijm`

Excluded:

- Local image data and processed outputs.
- Fiji, Maven, BigStitcher-Spark checkout/build outputs, and BigStitcher source checkout.
- Generated classes/caches/temp files.
- Previous multi-GB package ZIP files.
