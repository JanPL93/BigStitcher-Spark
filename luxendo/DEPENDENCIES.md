# Dependencies

This source package excludes large third-party dependencies and generated outputs so it can be used as the basis for a GitHub repository.

## Required Tools

- Windows PowerShell 5+.
- Python 3 with `numpy` and `h5py`.
- Fiji/ImageJ with a bundled JDK under `tools\fiji\Fiji.app\java\win64`.
- BigStitcher-Spark built under `tools\BigStitcher-Spark`.

The current launcher expects:

```text
tools\BigStitcher-Spark\target\BigStitcher-Spark-0.1.0-SNAPSHOT.jar
tools\BigStitcher-Spark\target\dependency\*
tools\BigStitcher-Spark\target\dependency-provided\*
tools\fiji\Fiji.app\java\win64\*\bin\java.exe
```

## Not Included

- Raw Luxendo data.
- Processed N5/BigTIFF outputs.
- Fiji application directory.
- BigStitcher-Spark cloned repository and Maven build output.
- BigStitcher source checkout.
- Maven distribution.
- Python package cache/vendor directory.
- Generated Java `.class` files.
- Old package ZIP files.

## Python

Minimal Python package list:

```text
numpy
h5py
```

Install into your preferred environment:

```powershell
python -m pip install -r requirements.txt
```

## Java Helpers

Java helper sources live in `tools\java`. The PowerShell wrappers compile the needed helper classes on demand into `tools\java\classes`.
