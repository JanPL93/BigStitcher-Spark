# Install / build from scratch (Windows)

These steps set up the Luxendo BigStitcher-Spark pipeline on a Windows PC with **nothing
pre-installed**. The scripts are Windows PowerShell oriented. When finished you will have this layout
under `luxendo\tools` (the paths the wrappers expect):

```text
luxendo\tools\
  BigStitcher-Spark\                         <- BigStitcher-Spark source, built
    target\BigStitcher-Spark-0.1.0-SNAPSHOT.jar
    target\dependency\                        <- runtime/compile dependency jars
    target\dependency-provided\               <- Spark/Scala (provided) dependency jars
  fiji\Fiji.app\                              <- Fiji install (provides Java + classic BigStitcher)
    java\win64\*jdk*\bin\java.exe
```

> Use a workspace path **without spaces** (e.g. `D:\bs_work`). Spark/Java reject some bare or
> spaced Windows paths; the scripts already convert to `file:///` URIs, but a space-free root avoids
> trouble.

---

## 1. Prerequisites

Install these once. The quickest way is [winget](https://learn.microsoft.com/windows/package-manager/winget/)
(built into Windows 10/11); manual installer links are given as fallback.

| Tool | winget | Manual |
|---|---|---|
| Git | `winget install Git.Git` | https://git-scm.com/download/win |
| Temurin JDK 8 | `winget install EclipseAdoptium.Temurin.8.JDK` | https://adoptium.net/temurin/releases/?version=8 |
| Maven | `winget install Apache.Maven` | https://maven.apache.org/download.cgi |
| Python 3 | `winget install Python.Python.3.12` | https://www.python.org/downloads/windows/ |

Notes:
- **JDK 8** matches the Java that Fiji bundles and that BigStitcher-Spark (Spark 3.3.2 / Scala 2.12)
  is built against. JDK 11 also works. A JDK (not just a JRE) is required because two small helpers
  are compiled on the fly.
- After installing, **open a new PowerShell window** so `git`, `mvn`, `java`, and `python` are on
  `PATH`. Verify:
  ```powershell
  git --version; mvn -version; java -version; python --version
  ```
  `mvn -version` must report a **JDK** (a `Java version: 1.8...` line with a JDK home). If it points
  at a JRE, set `JAVA_HOME` to the JDK, e.g.:
  ```powershell
  setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-8.0.xxx-hotspot"
  ```
  then reopen PowerShell.

---

## 2. Get the pipeline

Clone this repository (it contains the `luxendo` pipeline) into your workspace:

```powershell
cd D:\bs_work
git clone https://github.com/JanPL93/BigStitcher-Spark.git
cd BigStitcher-Spark\luxendo
```

All remaining commands are run from this `luxendo` folder.

---

## 3. Install Fiji (+ BigStitcher) under tools\fiji

The pipeline uses Fiji's bundled Java to launch BigStitcher-Spark, and the classic-BigStitcher
wrappers use the BigStitcher Fiji plugin.

1. Download Fiji for Windows (64-bit): https://imagej.net/software/fiji/downloads
2. Unzip so the application sits at exactly:
   ```text
   D:\bs_work\BigStitcher-Spark\luxendo\tools\fiji\Fiji.app
   ```
3. Launch `Fiji.app\ImageJ-win64.exe` once, then **Help ▸ Update…**, click **Manage update sites**,
   enable **BigStitcher**, apply, and let it restart. (Recent Fiji builds already include
   BigStitcher; enabling the site guarantees it.)
4. Confirm the bundled JDK is present:
   ```powershell
   Get-ChildItem tools\fiji\Fiji.app\java\win64\*\bin\java.exe
   ```

---

## 4. Build BigStitcher-Spark under tools\BigStitcher-Spark

Clone the BigStitcher-Spark source into `tools\BigStitcher-Spark` and build it into the layout the
wrappers expect:

```powershell
cd D:\bs_work\BigStitcher-Spark\luxendo\tools
git clone https://github.com/JaneliaSciComp/BigStitcher-Spark.git
cd BigStitcher-Spark

# Build the (thin) jar -> target\BigStitcher-Spark-0.1.0-SNAPSHOT.jar
mvn -DskipTests clean package

# Copy runtime/compile dependencies -> target\dependency\
mvn dependency:copy-dependencies -DincludeScope=runtime  -DoutputDirectory=target\dependency

# Copy Spark/Scala (provided) dependencies -> target\dependency-provided\
mvn dependency:copy-dependencies -DincludeScope=provided -DoutputDirectory=target\dependency-provided
```

The first build downloads many dependencies and can take several minutes. When done, verify:

```powershell
Test-Path target\BigStitcher-Spark-0.1.0-SNAPSHOT.jar      # True
(Get-ChildItem target\dependency\*.jar).Count              # > 0
(Get-ChildItem target\dependency-provided\*.jar).Count     # > 0 (includes spark-core_2.12-3.3.2.jar)
```

> You can build your own fork instead of the JaneliaSciComp upstream — any BigStitcher-Spark
> checkout works, as long as the three `target\…` paths above end up populated.

---

## 5. Python environment (numpy, h5py)

Create a virtual environment and install the two Python dependencies:

```powershell
cd D:\bs_work\BigStitcher-Spark\luxendo
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -r requirements.txt   # numpy, h5py
```

If `Activate.ps1` is blocked, allow scripts for your user once:
```powershell
Set-ExecutionPolicy -Scope CurrentUser -ExecutionPolicy RemoteSigned
```

Activate the venv (`.\.venv\Scripts\Activate.ps1`) in any PowerShell session before running the
pipeline, so its `python` resolves to numpy/h5py. (The pipeline calls `python`; the activated venv
puts the right one first on `PATH`.)

---

## 6. Smoke test

With the venv active and from `luxendo`:

```powershell
# Dispatcher resolves Java from Fiji and prints BigStitcher-Spark help for a command.
tools\bigstitcher-spark.cmd resave

# Pipeline help (parameters, including -RegistrationMode and -Sampling).
Get-Help tools\run_pipeline.ps1 -Detailed   # or: powershell -File tools\run_pipeline.ps1 -?
```

Then run on a dataset (folder containing `bdv.xml` + `bdv.h5`):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File tools\run_pipeline.ps1 `
  -DatasetRoot "D:\bs_work\my_dataset"
```

See `README.md` and `docs/ANALYSIS.md` for the tiled vs multiview options and the interest-point
affine path.

---

## Tuning notes

- The pipeline sets high defaults inside `run_pipeline.ps1`
  (`BIGSTITCHER_SPARK_THREADS=46`, `BIGSTITCHER_SPARK_MEMORY_GB=224`). On a smaller PC, lower these
  (edit the script, or set the `BIGSTITCHER_SPARK_THREADS` / `BIGSTITCHER_SPARK_MEMORY_GB`
  environment variables, which `bigstitcher-spark.ps1` honors). Memory in GB should stay below your
  physical RAM.
- The harmless warning `Compression 'org.janelia.saalfeldlab.n5.blosc.BloscCompression' could not be
  registered` can be ignored.

## Linux / macOS

The wrappers in `tools` are Windows-only. On Linux/macOS, build BigStitcher-Spark with its own
`./install -t <cores> -m <GB>` script (creates `resave`, `stitching`, `detect-interestpoints`,
`match-interestpoints`, `solver`, `affine-fusion`, … launchers) and call those commands directly; the
Python helpers (`create_bdv_int16_mirror.py`, `create_native_fusion_xml.py`,
`quantify_registration_transforms.py`) are cross-platform.
