#!/usr/bin/env python3
"""Build the three BetterHvNote release deliverables.

Examples:
    python tools/build_release.py
    python tools/build_release.py n10pro notelink-android --install
    python tools/build_release.py notelink-windows --clean

The Android APKs can be installed with adb. The Windows target produces a
portable ZIP and is intentionally not passed to adb.
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Target:
    name: str
    label: str
    gradle_task: str
    clean_task: str
    artifact_dir: Path
    artifact_glob: str
    adb_package: str | None


TARGETS = {
    "n10pro": Target(
        name="n10pro",
        label="N10Pro",
        gradle_task=":app:assembleRelease",
        clean_task=":app:clean",
        artifact_dir=ROOT / "build" / "app" / "outputs" / "apk",
        artifact_glob="release/*.apk",
        adb_package="com.betterhv.note",
    ),
    "notelink-android": Target(
        name="notelink-android",
        label="Android NoteLink",
        gradle_task=":phone-app:assembleRelease",
        clean_task=":phone-app:clean",
        artifact_dir=ROOT / "build" / "phone-app" / "outputs" / "apk",
        artifact_glob="release/*.apk",
        adb_package="com.betterhv.note.sender",
    ),
    "notelink-windows": Target(
        name="notelink-windows",
        label="Windows NoteLink",
        gradle_task=":phone-desktop:packagePortableZip",
        clean_task=":phone-desktop:clean",
        artifact_dir=ROOT / "build" / "phone-desktop" / "distributions",
        artifact_glob="NoteLink-*-windows-x64.zip",
        adb_package=None,
    ),
}


class BuildError(RuntimeError):
    pass


def gradle_executable() -> Path:
    executable = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not executable.is_file():
        raise BuildError(f"Gradle wrapper not found: {executable}")
    return executable


def run(command: list[str], *, capture: bool = False) -> subprocess.CompletedProcess[str]:
    printable = " ".join(f'"{part}"' if " " in part else part for part in command)
    print(f"\n> {printable}")
    completed = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        check=False,
        capture_output=capture,
    )
    if completed.returncode != 0:
        raise BuildError(f"Command failed with exit code {completed.returncode}: {printable}")
    return completed


def newest_artifact(target: Target) -> Path:
    candidates = sorted(
        target.artifact_dir.glob(target.artifact_glob),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise BuildError(
            f"{target.label} build succeeded, but no artifact matched "
            f"{target.artifact_dir / target.artifact_glob}"
        )
    return candidates[0]


def adb_executable(requested: str | None) -> str:
    adb_name = "adb.exe" if os.name == "nt" else "adb"
    candidates: list[Path] = []
    if requested:
        candidates.append(Path(requested))
    on_path = shutil.which("adb")
    if on_path:
        candidates.append(Path(on_path))

    sdk_roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    local_properties = ROOT / "local.properties"
    if local_properties.is_file():
        for line in local_properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                sdk_roots.append(line.removeprefix("sdk.dir=").replace("\\:", ":"))
    if os.name == "nt" and os.environ.get("LOCALAPPDATA"):
        sdk_roots.append(str(Path(os.environ["LOCALAPPDATA"]) / "Android" / "Sdk"))

    candidates.extend(Path(root) / "platform-tools" / adb_name for root in sdk_roots if root)
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise BuildError("adb was not found; install Android SDK platform-tools, add adb to PATH, or pass --adb PATH")


def adb_device(adb: str, serial: str | None) -> str:
    result = run([adb, "devices"], capture=True)
    devices: list[tuple[str, str]] = []
    for line in result.stdout.splitlines()[1:]:
        columns = line.split()
        if len(columns) >= 2:
            devices.append((columns[0], columns[1]))

    if serial:
        state = next((state for device, state in devices if device == serial), None)
        if state != "device":
            raise BuildError(f"adb serial {serial!r} is not ready (state: {state or 'not found'})")
        return serial

    ready = [device for device, state in devices if state == "device"]
    if len(ready) == 1:
        return ready[0]
    if not ready:
        raise BuildError("adb found no ready device; connect the N10Pro or Android NoteLink device first")
    raise BuildError("multiple adb devices found; select one with --serial: " + ", ".join(ready))


def install_apks(
    artifacts: list[tuple[Target, Path]],
    requested_adb: str | None,
    serial: str | None,
    target_serials: dict[str, str | None],
) -> None:
    android_artifacts = [(target, path) for target, path in artifacts if target.adb_package]
    if not android_artifacts:
        print("\nadb install skipped: the selected target only produces Windows artifacts.")
        return

    adb = adb_executable(requested_adb)
    for target, artifact in android_artifacts:
        device = adb_device(adb, target_serials.get(target.name) or serial)
        print(f"\nInstalling {target.label} on {device}:")
        run([adb, "-s", device, "install", "-r", str(artifact)])
        print(f"  installed {target.label} ({target.adb_package})")

    windows = [path for target, path in artifacts if target.name == "notelink-windows"]
    if windows:
        print(f"\nWindows portable ZIP is not adb-installable: {windows[0]}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build BetterHvNote release packages and optionally install APKs with adb.")
    parser.add_argument(
        "targets",
        nargs="*",
        choices=[*TARGETS, "all"],
        help="targets to build (default: all): n10pro, notelink-android, notelink-windows",
    )
    parser.add_argument("--install", action="store_true", help="install built Android APKs with adb -r")
    parser.add_argument("--serial", help="common adb serial; used when only one target/device is involved")
    parser.add_argument("--n10pro-serial", help="adb serial for the N10Pro APK")
    parser.add_argument("--notelink-serial", help="adb serial for the Android NoteLink APK")
    parser.add_argument("--adb", help="path to adb executable (default: search PATH)")
    parser.add_argument("--clean", action="store_true", help="clean selected modules before building")
    parser.add_argument("--dry-run", action="store_true", help="print Gradle commands without running them")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    requested = args.targets or ["all"]
    names = list(TARGETS) if "all" in requested else list(dict.fromkeys(requested))
    selected = [TARGETS[name] for name in names]
    tasks = list(dict.fromkeys(target.clean_task for target in selected)) if args.clean else []
    tasks.extend(target.gradle_task for target in selected)
    command = [str(gradle_executable()), *tasks, "--console=plain"]

    try:
        if args.dry_run:
            print(" ".join(command))
            return 0

        run(command)
        artifacts = [(target, newest_artifact(target)) for target in selected]
        print("\nRelease artifacts:")
        for target, artifact in artifacts:
            print(f"  {target.label}: {artifact}")

        if args.install:
            install_apks(
                artifacts,
                args.adb,
                args.serial,
                {"n10pro": args.n10pro_serial, "notelink-android": args.notelink_serial},
            )
        return 0
    except BuildError as error:
        print(f"\nERROR: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nCancelled.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
