#!/usr/bin/env python3
"""Shared changelog and release-notes generation helpers for BetterHvNote and NoteLink."""

from __future__ import annotations

import hashlib
import re
import subprocess
from collections.abc import Sequence
from dataclasses import dataclass, field
from pathlib import Path

from resolve_release_tag import TAG_PATTERN, resolve_release_tag

NOTE_EXCLUSIVE_ROOTS = frozenset({"app", "framework-stubs", "third_party", "artifacts"})
NOTELINK_EXCLUSIVE_ROOTS = frozenset({"phone-app", "phone-desktop", "transfer-windows"})
SHARED_PRODUCT_ROOTS = frozenset({"transfer-core", "transfer-android", "update-core"})
MAINTENANCE_SCOPES = frozenset(
    {"ci", "build", "docs", "chore", "style", "lint", "linter", "ide", "test", "tests", "changelog"}
)

PRODUCT_DISPLAY_NAMES = {
    "note": "BetterHvNote",
    "notelink": "NoteLink",
}

TRAILER_RE = re.compile(
    r"^(Signed-off-by|Co-authored-by|Reviewed-by|Acked-by|Tested-by|Change-Id|Changelog-Generated):\s*",
    re.IGNORECASE,
)
CHANGELOG_GENERATED_RE = re.compile(r"^Changelog-Generated:\s*true\s*$", re.IGNORECASE)
BREAKING_CHANGE_RE = re.compile(r"^BREAKING[ -]CHANGE:\s*(.*)$", re.IGNORECASE)
BULLET_RE = re.compile(r"^[-*]\s+(.+)$")
CONVENTIONAL_RE = re.compile(
    r"^(?P<type>[a-zA-Z][a-zA-Z0-9_-]*)(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s*(?P<desc>.+)$"
)
REMOTE_PATTERNS = (
    re.compile(r"^https?://github\.com/(?P<slug>[^/\s]+/[^/\s]+?)(?:\.git)?/?$"),
    re.compile(r"^git@github\.com:(?P<slug>[^/\s]+/[^/\s]+?)(?:\.git)?$"),
    re.compile(r"^ssh://git@github\.com/(?P<slug>[^/\s]+/[^/\s]+?)(?:\.git)?/?$"),
)

COMMIT_MARKER = "<<<BHN_COMMIT_8C21>>>"
BODY_MARKER = "<<<BHN_BODY_8C21>>>"
FILES_MARKER = "<<<BHN_FILES_8C21>>>"

PRIMARY_CATEGORIES = (
    "Features",
    "Bug Fixes",
    "Improvements & Refactoring",
    "Other Changes",
)
MAINTENANCE_CATEGORY = "Build, CI & Maintenance"

CATEGORY_COUNT_LABELS = (
    ("Features", "feature", "features"),
    ("Bug Fixes", "fix", "fixes"),
    ("Improvements & Refactoring", "improvement", "improvements"),
    ("Other Changes", "other", "other"),
    (MAINTENANCE_CATEGORY, "maintenance", "maintenance"),
)


@dataclass
class ReleaseTagRecord:
    tag: str
    product: str
    product_name: str
    version: str
    semver: tuple[int, int, int]
    commit: str
    created_at: int
    release_date: str
    tag_message: str
    previous_tag: str | None = None
    previous_commit: str | None = None


@dataclass(frozen=True)
class TagHighlights:
    headline: str | None
    body_lines: tuple[str, ...] = ()


@dataclass(frozen=True)
class CommitRecord:
    full_hash: str
    short_hash: str
    date: str
    subject: str
    commit_type: str | None
    scope: str | None
    breaking: bool
    breaking_notes: tuple[str, ...]
    description: str
    category: str
    body_items: tuple[str, ...]
    files: tuple[str, ...]
    top_roots: frozenset[str] = field(default_factory=frozenset)


def default_repo_root() -> Path:
    return Path(__file__).resolve().parents[2]


def run_git(
    repo_path: Path,
    args: Sequence[str],
    *,
    allowed_codes: Sequence[int] = (0,),
) -> subprocess.CompletedProcess[str]:
    command = [
        "git",
        "-c",
        "core.quotepath=false",
        "-c",
        "i18n.logOutputEncoding=utf-8",
        "-C",
        str(repo_path),
        *args,
    ]
    completed = subprocess.run(
        command,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode not in allowed_codes:
        details = (completed.stderr or completed.stdout).strip()
        if details:
            raise RuntimeError(f"git {' '.join(args)} failed with exit code {completed.returncode}: {details}")
        raise RuntimeError(f"git {' '.join(args)} failed with exit code {completed.returncode}.")
    return completed


def resolve_github_repo_slug(repo_path: Path, repo_slug: str | None = None) -> str | None:
    if repo_slug and repo_slug.strip():
        return repo_slug.strip().strip("/")

    result = run_git(repo_path, ["remote", "get-url", "origin"], allowed_codes=(0, 2, 128))
    if result.returncode != 0:
        return None

    url = result.stdout.strip()
    for pattern in REMOTE_PATTERNS:
        match = pattern.match(url)
        if match:
            return match.group("slug")
    return None


def get_all_valid_release_tags(repo_path: Path) -> list[str]:
    result = run_git(repo_path, ["tag", "-l", "note-v*", "notelink-v*"])
    seen: set[str] = set()
    tags: list[str] = []
    for raw_line in result.stdout.splitlines():
        tag = raw_line.strip()
        if tag and TAG_PATTERN.match(tag) and tag not in seen:
            seen.add(tag)
            tags.append(tag)
    return tags


def get_release_tag_record(repo_path: Path, tag: str) -> ReleaseTagRecord:
    try:
        resolved = resolve_release_tag(tag)
    except ValueError as error:
        raise ValueError(f"Release tag '{tag}' is invalid: {error}") from error

    commit = run_git(repo_path, ["rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"]).stdout.strip()
    if not commit:
        raise RuntimeError(f"Release tag '{tag}' did not resolve to a commit.")

    meta_line = run_git(
        repo_path,
        [
            "for-each-ref",
            "--format=%(objecttype)%09%(creatordate:unix)%09%(creatordate:format:%Y-%m-%d)",
            f"refs/tags/{tag}",
        ],
    ).stdout.strip()
    if not meta_line:
        raise RuntimeError(f"Release tag '{tag}' does not exist.")

    meta_parts = meta_line.split("\t", 2)
    if len(meta_parts) < 3:
        raise RuntimeError(f"Could not parse metadata for tag '{tag}': {meta_line}")

    object_type = meta_parts[0].strip()
    created_at = int(meta_parts[1].strip())
    release_date = meta_parts[2].strip()

    tag_message = ""
    if object_type == "tag":
        tag_message = run_git(repo_path, ["for-each-ref", "--format=%(contents)", f"refs/tags/{tag}"]).stdout

    product = str(resolved["product"])
    version = str(resolved["version"])
    semver = tuple(int(part) for part in version.split("."))
    if len(semver) != 3:
        raise ValueError(f"Unexpected version format '{version}' for tag '{tag}'.")

    return ReleaseTagRecord(
        tag=tag,
        product=product,
        product_name=PRODUCT_DISPLAY_NAMES[product],
        version=version,
        semver=(semver[0], semver[1], semver[2]),
        commit=commit,
        created_at=created_at,
        release_date=release_date,
        tag_message=tag_message,
    )


def resolve_previous_release_links(repo_path: Path, records: Sequence[ReleaseTagRecord]) -> None:
    for product in ("note", "notelink"):
        product_records = sorted(
            (record for record in records if record.product == product),
            key=lambda record: record.semver,
        )
        for index in range(1, len(product_records)):
            current = product_records[index]
            for prev_index in range(index - 1, -1, -1):
                candidate = product_records[prev_index]
                ancestry = run_git(
                    repo_path,
                    ["merge-base", "--is-ancestor", candidate.commit, current.commit],
                    allowed_codes=(0, 1),
                )
                if ancestry.returncode == 0:
                    current.previous_tag = candidate.tag
                    current.previous_commit = candidate.commit
                    break


def get_clean_tag_highlights(record: ReleaseTagRecord) -> TagHighlights:
    if not record.tag_message or not record.tag_message.strip():
        return TagHighlights(headline=None)

    filtered = [line.rstrip() for line in record.tag_message.splitlines() if not TRAILER_RE.match(line)]
    while filtered and not filtered[0].strip():
        filtered.pop(0)
    while filtered and not filtered[-1].strip():
        filtered.pop()

    if not filtered:
        return TagHighlights(headline=None)

    escaped_version = re.escape(record.version)
    first_line = filtered[0].strip()
    headline: str | None = first_line

    redundant_re = re.compile(rf"^(BetterHvNote|NoteLink|note|notelink)\s+v?{escaped_version}$", re.IGNORECASE)
    prefixed_re = re.compile(
        rf"^(BetterHvNote|NoteLink|note|notelink)\s+v?{escaped_version}\s*[:\-–—]\s*(.+)$",
        re.IGNORECASE,
    )
    if redundant_re.match(first_line):
        headline = None
    else:
        prefixed_match = prefixed_re.match(first_line)
        if prefixed_match:
            headline = prefixed_match.group(2).strip()

    body_start = 1
    while body_start < len(filtered) and not filtered[body_start].strip():
        body_start += 1

    body_lines = tuple(filtered[body_start:]) if body_start < len(filtered) else ()
    return TagHighlights(headline=headline, body_lines=body_lines)


def parse_body_lines(lines: Sequence[str]) -> tuple[list[str], list[str]]:
    cleaned: list[str] = []
    breaking_notes: list[str] = []
    in_breaking = False
    breaking_buffer: list[str] = []

    for raw_line in lines:
        line = raw_line.rstrip()
        if TRAILER_RE.match(line):
            continue

        breaking_match = BREAKING_CHANGE_RE.match(line)
        if breaking_match:
            if in_breaking and breaking_buffer:
                breaking_notes.append(" ".join(breaking_buffer).strip())
                breaking_buffer.clear()
            in_breaking = True
            text = breaking_match.group(1).strip()
            if text:
                breaking_buffer.append(text)
            continue

        if in_breaking:
            if not line.strip():
                if breaking_buffer:
                    breaking_notes.append(" ".join(breaking_buffer).strip())
                    breaking_buffer.clear()
                in_breaking = False
            else:
                breaking_buffer.append(line.strip())
                continue

        cleaned.append(line)

    if in_breaking and breaking_buffer:
        breaking_notes.append(" ".join(breaking_buffer).strip())

    items: list[str] = []
    current = ""
    for line in cleaned:
        trimmed = line.strip()
        if not trimmed:
            if current:
                items.append(current.strip())
                current = ""
            continue

        bullet_match = BULLET_RE.match(trimmed)
        if bullet_match:
            if current:
                items.append(current.strip())
            current = bullet_match.group(1).strip()
            continue

        current = f"{current} {trimmed}" if current else trimmed

    if current:
        items.append(current.strip())

    return items, breaking_notes


def parse_commit(
    full_hash: str,
    short_hash: str,
    date: str,
    subject: str,
    body_lines: Sequence[str],
    files: Sequence[str],
) -> CommitRecord | None:
    if any(CHANGELOG_GENERATED_RE.match(line) for line in body_lines):
        return None

    clean_subject = subject.strip()
    commit_type: str | None = None
    scope: str | None = None
    breaking = False
    description = clean_subject

    match = CONVENTIONAL_RE.match(clean_subject)
    if match:
        commit_type = match.group("type").lower()
        scope = match.group("scope").strip() if match.group("scope") else None
        breaking = match.group("breaking") == "!"
        description = match.group("desc").strip()

    body_items, breaking_notes = parse_body_lines(body_lines)
    if breaking_notes:
        breaking = True

    if commit_type == "feat":
        category = "Features"
    elif commit_type == "fix":
        category = (
            MAINTENANCE_CATEGORY if scope and scope.lower() in MAINTENANCE_SCOPES else "Bug Fixes"
        )
    elif commit_type in {"perf", "refactor"}:
        category = "Improvements & Refactoring"
    elif commit_type in {"docs", "build", "ci", "chore", "style", "test", "linter"}:
        category = MAINTENANCE_CATEGORY
    elif re.match(r"^fix\b", clean_subject, re.IGNORECASE):
        category = "Bug Fixes"
    else:
        category = "Other Changes"

    top_roots = frozenset(
        path.replace("\\", "/").split("/", 1)[0]
        for path in files
        if path.strip()
    )

    return CommitRecord(
        full_hash=full_hash.strip(),
        short_hash=short_hash.strip(),
        date=date.strip(),
        subject=clean_subject,
        commit_type=commit_type,
        scope=scope,
        breaking=breaking,
        breaking_notes=tuple(breaking_notes),
        description=description,
        category=category,
        body_items=tuple(body_items),
        files=tuple(files),
        top_roots=top_roots,
    )


def get_commits_in_range(
    repo_path: Path,
    previous_commit: str | None,
    current_commit: str,
) -> list[CommitRecord]:
    if previous_commit and previous_commit == current_commit:
        return []

    revision = f"{previous_commit}..{current_commit}" if previous_commit else current_commit
    log_format = f"{COMMIT_MARKER}%n%H%n%h%n%cs%n%s%n{BODY_MARKER}%n%b{FILES_MARKER}"
    result = run_git(
        repo_path,
        [
            "log",
            "--topo-order",
            "--no-merges",
            f"--format={log_format}",
            "--name-only",
            revision,
        ],
    )

    commits: list[CommitRecord] = []
    state = "none"
    header_fields: list[str] = []
    body_lines: list[str] = []
    files: list[str] = []

    def flush() -> None:
        if len(header_fields) >= 4:
            parsed = parse_commit(
                full_hash=header_fields[0],
                short_hash=header_fields[1],
                date=header_fields[2],
                subject=header_fields[3],
                body_lines=body_lines,
                files=files,
            )
            if parsed is not None:
                commits.append(parsed)
        header_fields.clear()
        body_lines.clear()
        files.clear()

    for line in result.stdout.splitlines():
        if line == COMMIT_MARKER:
            flush()
            state = "header"
            continue
        if line == BODY_MARKER:
            state = "body"
            continue
        if line == FILES_MARKER:
            state = "files"
            continue

        if state == "header":
            header_fields.append(line)
        elif state == "body":
            body_lines.append(line)
        elif state == "files":
            trimmed = line.strip()
            if trimmed:
                files.append(trimmed)

    flush()
    return commits


def is_commit_relevant_to_product(commit: CommitRecord, product: str) -> bool:
    if product == "note":
        own_exclusive = NOTE_EXCLUSIVE_ROOTS
        other_exclusive = NOTELINK_EXCLUSIVE_ROOTS
    elif product == "notelink":
        own_exclusive = NOTELINK_EXCLUSIVE_ROOTS
        other_exclusive = NOTE_EXCLUSIVE_ROOTS
    else:
        raise ValueError(f"Unsupported product '{product}'.")

    touches_own = bool(commit.top_roots & (own_exclusive | SHARED_PRODUCT_ROOTS))
    if touches_own:
        return True
    touches_other_exclusive = bool(commit.top_roots & other_exclusive)
    if touches_other_exclusive:
        return False
    return True


def format_commit_link(commit: CommitRecord, repo_slug: str | None) -> str:
    if repo_slug:
        return f"[`{commit.short_hash}`](https://github.com/{repo_slug}/commit/{commit.full_hash})"
    return f"`{commit.short_hash}`"


def format_tag_link(tag: str, repo_slug: str | None) -> str:
    if repo_slug:
        return f"[`{tag}`](https://github.com/{repo_slug}/releases/tag/{tag})"
    return f"`{tag}`"


def format_compare_link(
    previous_tag: str,
    current_tag: str,
    repo_slug: str | None,
    *,
    label: str | None = None,
) -> str:
    text = label if label is not None else f"`{previous_tag}...{current_tag}`"
    if repo_slug:
        return f"[{text}](https://github.com/{repo_slug}/compare/{previous_tag}...{current_tag})"
    return text


def format_commit_summary_line(
    commit: CommitRecord,
    repo_slug: str | None,
    *,
    show_type_prefix: bool = False,
) -> str:
    prefix = ""
    if show_type_prefix:
        if commit.commit_type and commit.scope:
            prefix = f"**{commit.commit_type}({commit.scope})**: "
        elif commit.commit_type:
            prefix = f"**{commit.commit_type}**: "
        elif commit.scope:
            prefix = f"**{commit.scope}**: "
    else:
        if commit.scope:
            prefix = f"**{commit.scope}**: "
        elif commit.category == "Improvements & Refactoring" and commit.commit_type:
            prefix = f"**{commit.commit_type}**: "

    hash_link = format_commit_link(commit, repo_slug)
    return f"- {prefix}{commit.description} ({hash_link})"


def format_category_counts_text(commits: Sequence[CommitRecord]) -> str:
    if not commits:
        return "no product commits"

    parts: list[str] = []
    for category, singular, plural in CATEGORY_COUNT_LABELS:
        count = sum(1 for commit in commits if commit.category == category)
        if count > 0:
            label = singular if count == 1 else plural
            parts.append(f"{count} {label}")

    commit_word = "commit" if len(commits) == 1 else "commits"
    if parts:
        return f"**{len(commits)} {commit_word}** ({', '.join(parts)})"
    return f"**{len(commits)} {commit_word}**"


def format_byte_size(num_bytes: int) -> str:
    if num_bytes >= 1_073_741_824:
        return f"{num_bytes / 1_073_741_824:.2f} GB"
    if num_bytes >= 1_048_576:
        return f"{num_bytes / 1_048_576:.2f} MB"
    if num_bytes >= 1_024:
        return f"{num_bytes / 1_024:.1f} KB"
    return f"{num_bytes} B"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


def format_assets_markdown(assets_path: Path) -> str:
    if not assets_path.is_dir():
        raise FileNotFoundError(f"Assets directory '{assets_path}' does not exist.")

    files = sorted((entry for entry in assets_path.iterdir() if entry.is_file()), key=lambda p: p.name)
    if not files:
        return ""

    lines = [
        "## Release Artifacts",
        "",
        "| File | Size | SHA-256 |",
        "| --- | ---: | --- |",
    ]
    for file_path in files:
        lines.append(
            f"| `{file_path.name}` | {format_byte_size(file_path.stat().st_size)} | `{sha256_file(file_path)}` |"
        )
    return "\n".join(lines) + "\n"


def format_release_markdown(
    record: ReleaseTagRecord,
    commits: Sequence[CommitRecord],
    *,
    repo_slug: str | None = None,
    mode: str = "release_notes",
    assets_path: Path | None = None,
) -> str:
    if mode not in {"release_notes", "full_changelog"}:
        raise ValueError(f"Unsupported mode '{mode}'.")

    relevant_commits = [commit for commit in commits if is_commit_relevant_to_product(commit, record.product)]
    summary_counts = format_category_counts_text(relevant_commits)
    highlights = get_clean_tag_highlights(record)

    out: list[str] = []

    if mode == "full_changelog":
        out.append(f"## {record.product_name} {record.version}\n\n")
        meta_parts = [
            f"**Tag:** {format_tag_link(record.tag, repo_slug)}",
            f"**Date:** {record.release_date}",
            (
                f"**Since:** {format_tag_link(record.previous_tag, repo_slug)}"
                if record.previous_tag
                else "**Initial release**"
            ),
            summary_counts,
        ]
        if record.previous_tag:
            meta_parts.append(
                format_compare_link(record.previous_tag, record.tag, repo_slug, label="Full Diff")
            )
        out.append(f"> {' · '.join(meta_parts)}\n\n")
    else:
        header_parts = [
            (
                f"Changes since {format_tag_link(record.previous_tag, repo_slug)}"
                if record.previous_tag
                else "Initial release"
            ),
            summary_counts,
        ]
        if record.previous_tag:
            header_parts.append(
                format_compare_link(record.previous_tag, record.tag, repo_slug, label="Full Diff")
            )
        out.append(f"> {' · '.join(header_parts)}\n\n")

    if highlights.headline or highlights.body_lines:
        if mode == "release_notes":
            out.append("## Highlights\n\n")
            if highlights.headline:
                out.append(f"{highlights.headline}\n\n")
        else:
            if highlights.headline:
                out.append(f"**Release Summary:** {highlights.headline}\n\n")
        if highlights.body_lines:
            out.append("\n".join(highlights.body_lines) + "\n\n")

    if mode == "release_notes":
        out.append("## What's Changed\n\n")

    if not relevant_commits:
        if record.previous_tag:
            out.append(f"No {record.product_name}-specific code changes since `{record.previous_tag}`.\n\n")
        else:
            out.append("No commits recorded for this release.\n\n")
    else:
        breaking_commits = [commit for commit in relevant_commits if commit.breaking]
        if breaking_commits:
            out.append("### Breaking Changes\n\n")
            for commit in breaking_commits:
                out.append(format_commit_summary_line(commit, repo_slug) + "\n")
                for note in commit.breaking_notes:
                    out.append(f"  - {note}\n")
            out.append("\n")

        has_primary_changes = False
        for category in PRIMARY_CATEGORIES:
            items = [commit for commit in relevant_commits if commit.category == category]
            if not items:
                continue
            has_primary_changes = True
            out.append(f"### {category}\n\n")
            for commit in items:
                out.append(format_commit_summary_line(commit, repo_slug) + "\n")
                for detail in commit.body_items:
                    out.append(f"  - {detail}\n")
            out.append("\n")

        maintenance_items = [commit for commit in relevant_commits if commit.category == MAINTENANCE_CATEGORY]
        if maintenance_items:
            m_word = "commit" if len(maintenance_items) == 1 else "commits"
            if has_primary_changes:
                out.append("<details>\n")
                out.append(
                    f"<summary>Build, CI &amp; Maintenance ({len(maintenance_items)} {m_word})</summary>\n\n"
                )
                for commit in maintenance_items:
                    out.append(format_commit_summary_line(commit, repo_slug, show_type_prefix=True) + "\n")
                    for detail in commit.body_items:
                        out.append(f"  - {detail}\n")
                out.append("\n</details>\n\n")
            else:
                out.append(f"### {MAINTENANCE_CATEGORY}\n\n")
                for commit in maintenance_items:
                    out.append(format_commit_summary_line(commit, repo_slug, show_type_prefix=True) + "\n")
                    for detail in commit.body_items:
                        out.append(f"  - {detail}\n")
                out.append("\n")

    if mode == "release_notes":
        if assets_path is not None:
            assets_section = format_assets_markdown(assets_path)
            if assets_section:
                out.append(assets_section + "\n")

        if record.previous_tag:
            compare = format_compare_link(record.previous_tag, record.tag, repo_slug)
            out.append(f"**Full Changelog:** {compare}\n")

    return "".join(out).rstrip() + "\n"


def github_anchor_slug(heading: str) -> str:
    slug = heading.lower()
    slug = re.sub(r"[^a-z0-9\s-]", "", slug)
    return re.sub(r"\s+", "-", slug.strip())


def write_utf8_lf(path: Path, text: str) -> Path:
    resolved = path.resolve()
    resolved.parent.mkdir(parents=True, exist_ok=True)
    with resolved.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    return resolved
