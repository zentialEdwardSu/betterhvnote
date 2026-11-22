#!/usr/bin/env python3
"""Validate and parse BetterHvNote / NoteLink release tags.

Supported formats:
    note-vX.Y.Z
    notelink-vX.Y.Z
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from typing import Any

TAG_PATTERN = re.compile(
    r"^(?P<product>note|notelink)-v(?P<major>0|[1-9][0-9]*)\.(?P<minor>0|[1-9][0-9]*)\.(?P<patch>0|[1-9][0-9]*)$"
)
MAX_VERSION_CODE = 2_100_000_000


def resolve_release_tag(value: str) -> dict[str, Any]:
    match = TAG_PATTERN.match(value)
    if not match:
        raise ValueError(f"Invalid release tag '{value}'. Expected note-vX.Y.Z or notelink-vX.Y.Z.")

    major = int(match.group("major"))
    minor = int(match.group("minor"))
    patch = int(match.group("patch"))
    if minor > 999 or patch > 999:
        raise ValueError("Minor and patch versions must be between 0 and 999.")

    version_code = major * 1_000_000 + minor * 1_000 + patch
    if version_code < 1:
        raise ValueError("Android versionCode must be at least 1; version 0.0.0 cannot be released.")
    if version_code > MAX_VERSION_CODE:
        raise ValueError(f"Android versionCode {version_code} exceeds {MAX_VERSION_CODE}.")

    return {
        "tag": value,
        "product": match.group("product"),
        "version": f"{major}.{minor}.{patch}",
        "versionCode": version_code,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate a release tag and print resolved JSON metadata.")
    parser.add_argument("tag_positional", nargs="?", help="Release tag (note-vX.Y.Z or notelink-vX.Y.Z)")
    parser.add_argument("--tag", dest="tag_option", help="Release tag (note-vX.Y.Z or notelink-vX.Y.Z)")
    args = parser.parse_args(argv)

    tag = args.tag_option or args.tag_positional
    if not tag:
        parser.error("A release tag must be provided via --tag or as a positional argument.")

    try:
        resolved = resolve_release_tag(tag)
    except ValueError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print(json.dumps(resolved, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
