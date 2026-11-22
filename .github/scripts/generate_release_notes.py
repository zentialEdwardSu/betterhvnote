#!/usr/bin/env python3
"""Generate structured GitHub Release notes for a single BetterHvNote or NoteLink tag."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from changelog_common import (
    default_repo_root,
    format_release_markdown,
    get_all_valid_release_tags,
    get_commits_in_range,
    get_release_tag_record,
    resolve_github_repo_slug,
    resolve_previous_release_links,
    write_utf8_lf,
)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate GitHub Release notes comparing a tag against the previous same-product release."
    )
    parser.add_argument("--tag", required=True, help="Target release tag (note-vX.Y.Z or notelink-vX.Y.Z)")
    parser.add_argument("--previous-tag", help="Explicit previous release tag of the same product")
    parser.add_argument("--repo-path", default=str(default_repo_root()), help="Path to the git repository")
    parser.add_argument("--repo-slug", help="GitHub owner/repo slug for links (auto-detected from origin if omitted)")
    parser.add_argument("--assets-dir", help="Optional directory containing staged release assets for SHA-256 table")
    parser.add_argument("--output", help="Optional output markdown file path (writes to stdout if omitted)")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    repo_path = Path(args.repo_path).resolve()
    repo_slug = resolve_github_repo_slug(repo_path, args.repo_slug)
    assets_path = Path(args.assets_dir).resolve() if args.assets_dir else None

    current_record = get_release_tag_record(repo_path, args.tag)

    if args.previous_tag:
        previous_record = get_release_tag_record(repo_path, args.previous_tag)
        if previous_record.product != current_record.product:
            raise ValueError(
                f"Previous tag '{args.previous_tag}' belongs to product '{previous_record.product}', "
                f"which does not match '{args.tag}' ('{current_record.product}')."
            )
        current_record.previous_tag = previous_record.tag
        current_record.previous_commit = previous_record.commit
    else:
        all_tags = get_all_valid_release_tags(repo_path)
        if args.tag not in all_tags:
            all_tags.append(args.tag)

        records = [current_record]
        for candidate_tag in all_tags:
            if candidate_tag == args.tag:
                continue
            candidate_record = get_release_tag_record(repo_path, candidate_tag)
            if candidate_record.product == current_record.product:
                records.append(candidate_record)

        resolve_previous_release_links(repo_path, records)

    commits = get_commits_in_range(repo_path, current_record.previous_commit, current_record.commit)
    markdown = format_release_markdown(
        current_record,
        commits,
        repo_slug=repo_slug,
        mode="release_notes",
        assets_path=assets_path,
    )

    if args.output:
        output_path = Path(args.output)
        if not output_path.is_absolute():
            output_path = repo_path / output_path
        resolved = write_utf8_lf(output_path, markdown)
        print(resolved)
    else:
        sys.stdout.write(markdown)

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
