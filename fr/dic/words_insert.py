#!/usr/bin/env python3
"""
Insert additions.csv entries into word.csv without reordering existing rows.

Duplicate identity is (form, POS):
- the same lemma is reported as an exact duplicate and skipped;
- a different lemma is reported as a conflict and skipped.

Existing word.csv bytes, including line endings and CSV quoting, are preserved.
Only the first three fields of each accepted additions.csv row are inserted.
Inserted rows use standard CSV quoting and word.csv's predominant line ending.
"""

from __future__ import annotations

import argparse
import codecs
import csv
import io
import os
import shutil
import sys
import tempfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

COLLATION_LOCALE = "fr_FR"
CSV_HEADER = ("INFLECTED", "POS", "LEMMA")


class LexiconError(Exception):
    """Raised when an input file or runtime dependency is invalid."""


@dataclass(frozen=True)
class Record:
    """A parsed lexicon row and its original physical-line representation."""

    path: Path
    line_number: int
    physical_index: int
    raw: bytes
    form: str
    pos: str
    lemma: str

    @property
    def key(self) -> tuple[str, str]:
        """Return the duplicate key: form and part of speech."""
        return self.form, self.pos

    @property
    def display(self) -> str:
        """Return the original source CSV row without its line ending."""
        return self.raw.decode("utf-8")

    @property
    def output(self) -> bytes:
        """Return this record serialized as exactly three UTF-8 CSV fields."""
        buffer = io.StringIO(newline="")
        writer = csv.writer(buffer, lineterminator="")
        writer.writerow((self.form, self.pos, self.lemma))
        return buffer.getvalue().encode("utf-8")

    @property
    def output_display(self) -> str:
        """Return the exact three-column row written to word.csv."""
        return self.output.decode("utf-8")


@dataclass(frozen=True)
class LoadedFile:
    """A UTF-8 lexicon file loaded as physical lines and parsed records."""

    bom: bytes
    lines: list[bytes]
    records: list[Record]


@dataclass
class DuplicateCounts:
    """Counters for skipped duplicate keys."""

    conflicts: int = 0
    exact: int = 0

    @property
    def total(self) -> int:
        """Return the total number of skipped duplicate keys."""
        return self.conflicts + self.exact


def atomic_replace(
    path: Path,
    bom: bytes,
    lines: list[bytes],
    insertions: dict[int, list[Record]],
    newline: bytes,
) -> None:
    """Write the merged content beside path and atomically replace path."""
    file_descriptor, temporary_name = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
    )
    temporary_path = Path(temporary_name)

    try:
        with os.fdopen(file_descriptor, "wb") as output:
            if bom:
                output.write(bom)

            has_text = False
            ends_with_newline = True

            for physical_index in range(len(lines) + 1):
                for record in insertions.get(physical_index, ()):
                    if has_text and not ends_with_newline:
                        output.write(newline)
                    output.write(record.output)
                    output.write(newline)
                    has_text = True
                    ends_with_newline = True

                if physical_index < len(lines):
                    raw_line = lines[physical_index]
                    output.write(raw_line)
                    if raw_line:
                        has_text = True
                        ends_with_newline = raw_line.endswith((b"\n", b"\r"))

            output.flush()
            os.fsync(output.fileno())

        shutil.copymode(path, temporary_path)
        os.replace(temporary_path, path)
    except BaseException:
        try:
            temporary_path.unlink()
        except FileNotFoundError:
            pass
        raise


def build_collator() -> tuple[Any, Any]:
    """Create an explicit French ICU collator with normalization enabled."""
    try:
        import icu
    except ImportError as error:
        raise LexiconError(
            "PyICU is required. Install it in this Python environment "
            "before running the script."
        ) from error

    collator = icu.Collator.createInstance(icu.Locale(COLLATION_LOCALE))
    collator.setAttribute(
        icu.UCollAttribute.NORMALIZATION_MODE,
        icu.UCollAttributeValue.ON,
    )
    collator.setStrength(icu.Collator.TERTIARY)
    return icu, collator


def choose_newline(lines: Iterable[bytes]) -> bytes:
    """Return the predominant existing line ending."""
    counts: Counter[bytes] = Counter()

    for line in lines:
        if line.endswith(b"\r\n"):
            counts[b"\r\n"] += 1
        elif line.endswith(b"\n"):
            counts[b"\n"] += 1
        elif line.endswith(b"\r"):
            counts[b"\r"] += 1

    if counts:
        return counts.most_common(1)[0][0]
    return os.linesep.encode("ascii")


def filter_additions(
    word_records: list[Record],
    addition_records: list[Record],
) -> tuple[list[Record], DuplicateCounts]:
    """Remove duplicate addition keys and report exact duplicates or conflicts."""
    existing: dict[tuple[str, str], list[Record]] = defaultdict(list)
    for record in word_records:
        existing[record.key].append(record)

    accepted: list[Record] = []
    accepted_by_key: dict[tuple[str, str], Record] = {}
    counts = DuplicateCounts()

    for addition in addition_records:
        previous = existing.get(addition.key)
        if previous:
            matching = next(
                (record for record in previous if record.lemma == addition.lemma),
                None,
            )
            if matching is not None:
                counts.exact += 1
                warn(
                    addition,
                    "exact duplicate of "
                    f"{matching.path}:{matching.line_number}; skipped",
                )
            else:
                counts.conflicts += 1
                lemmas = ", ".join(sorted({record.lemma for record in previous}))
                locations = ", ".join(
                    f"{record.path}:{record.line_number}" for record in previous
                )
                warn(
                    addition,
                    "duplicate (form, POS) with a different lemma; "
                    f"existing lemma(s): {lemmas}; at {locations}; skipped",
                )
            continue

        earlier_addition = accepted_by_key.get(addition.key)
        if earlier_addition is not None:
            if earlier_addition.lemma == addition.lemma:
                counts.exact += 1
                warn(
                    addition,
                    "exact duplicate of "
                    f"{earlier_addition.path}:{earlier_addition.line_number}; skipped",
                )
            else:
                counts.conflicts += 1
                warn(
                    addition,
                    "duplicate (form, POS) with a different lemma; "
                    f"earlier addition uses {earlier_addition.lemma!r} at "
                    f"{earlier_addition.path}:{earlier_addition.line_number}; skipped",
                )
            continue

        accepted.append(addition)
        accepted_by_key[addition.key] = addition

    return accepted, counts


def group_and_sort(
    records: list[Record],
    collator: Any,
) -> list[tuple[str, list[Record], bytes]]:
    """Group exact forms and stably sort form groups with the ICU collator."""
    groups: dict[str, list[Record]] = {}
    for record in records:
        groups.setdefault(record.form, []).append(record)

    sorted_groups = sorted(
        groups.items(),
        key=lambda item: bytes(collator.getSortKey(item[0])),
    )
    return [
        (form, group, bytes(collator.getSortKey(form)))
        for form, group in sorted_groups
    ]


def insertion_plan(
    word_file: LoadedFile,
    additions: list[Record],
    collator: Any,
) -> dict[int, list[Record]]:
    """
    Return insertions keyed by physical-line position.

    Existing forms are inserted after their last exact occurrence. Other forms
    are inserted before the first existing form having a greater ICU sort key.
    Existing lines are never moved, even when their order conflicts with ICU.
    """
    last_occurrence: dict[str, int] = {}
    for record in word_file.records:
        last_occurrence[record.form] = record.physical_index

    existing_with_keys = [
        (record, bytes(collator.getSortKey(record.form)))
        for record in word_file.records
    ]

    insertions: dict[int, list[Record]] = defaultdict(list)
    cursor = 0

    for form, group, form_key in group_and_sort(additions, collator):
        while (
            cursor < len(existing_with_keys)
            and existing_with_keys[cursor][1] <= form_key
        ):
            cursor += 1

        if form in last_occurrence:
            anchor = last_occurrence[form] + 1
        elif cursor < len(existing_with_keys):
            anchor = existing_with_keys[cursor][0].physical_index
        else:
            anchor = len(word_file.lines)

        insertions[anchor].extend(group)

    return dict(insertions)


def load_file(path: Path) -> LoadedFile:
    """Load and validate a UTF-8 CSV file, excluding its optional header."""
    try:
        data = path.read_bytes()
    except OSError as error:
        raise LexiconError(f"Cannot read {path}: {error}") from error

    bom = codecs.BOM_UTF8 if data.startswith(codecs.BOM_UTF8) else b""
    content = data[len(bom) :]
    lines = content.splitlines(keepends=True)
    records: list[Record] = []
    content_row_count = 0

    for physical_index, raw_line in enumerate(lines):
        line_number = physical_index + 1
        raw = strip_line_ending(raw_line)

        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError as error:
            raise LexiconError(
                f"{path}:{line_number}: invalid UTF-8: {error}"
            ) from error

        if not text.strip():
            continue

        try:
            row = next(csv.reader([text], strict=True))
        except csv.Error as error:
            raise LexiconError(
                f"{path}:{line_number}: invalid CSV: {error}"
            ) from error

        if len(row) < 3:
            raise LexiconError(
                f"{path}:{line_number}: expected at least 3 columns, "
                f"found {len(row)}"
            )

        fields = tuple(row[:3])
        if fields == CSV_HEADER:
            if content_row_count != 0:
                raise LexiconError(
                    f"{path}:{line_number}: CSV header must be the first "
                    "non-empty row"
                )
            content_row_count += 1
            continue

        content_row_count += 1
        if any(value == "" for value in fields):
            raise LexiconError(
                f"{path}:{line_number}: form, POS and lemma must be non-empty"
            )

        records.append(
            Record(
                path=path,
                line_number=line_number,
                physical_index=physical_index,
                raw=raw,
                form=fields[0],
                pos=fields[1],
                lemma=fields[2],
            )
        )

    return LoadedFile(bom=bom, lines=lines, records=records)


def parse_arguments() -> argparse.Namespace:
    """Parse command-line arguments."""
    parser = argparse.ArgumentParser(
        description=(
            "Insert additions.csv into word.csv using explicit French ICU "
            "collation without reordering existing rows."
        )
    )
    parser.add_argument(
        "word",
        nargs="?",
        type=Path,
        default=Path("../src/resources/com/github/oeuvres/alix/fr/word.csv"),
        help="lexicon to update in place (default: word.csv)",
    )
    parser.add_argument(
        "additions",
        nargs="?",
        type=Path,
        default=Path("word-candidates.csv"),
        help="rows to insert (default: additions.csv)",
    )
    return parser.parse_args()


def print_report(
    icu: Any,
    inserted: list[Record],
    duplicates: DuplicateCounts,
    word_path: Path,
) -> None:
    """Print inserted rows and aggregate duplicate counts."""
    print(
        f"Collation: {COLLATION_LOCALE}; "
        f"PyICU {icu.VERSION}; ICU {icu.ICU_VERSION}"
    )

    if inserted:
        print(f"Inserted into {word_path}:")
        for record in inserted:
            print(f"  {record.output_display}")
    else:
        print(f"No rows inserted; {word_path} was not modified.")

    print(f"Inserted rows: {len(inserted)}")
    print(
        "Duplicate (form, POS) rows skipped: "
        f"{duplicates.total} "
        f"(exact: {duplicates.exact}, conflicting lemma: {duplicates.conflicts})"
    )


def strip_line_ending(raw_line: bytes) -> bytes:
    """Remove one physical line ending from a byte string."""
    if raw_line.endswith(b"\r\n"):
        return raw_line[:-2]
    if raw_line.endswith((b"\n", b"\r")):
        return raw_line[:-1]
    return raw_line


def warn(record: Record, message: str) -> None:
    """Write a row-specific warning to standard error."""
    print(
        f"WARNING: {record.path}:{record.line_number}: "
        f"{record.display!r}: {message}",
        file=sys.stderr,
    )


def main() -> int:
    """Run the in-place lexicon merge."""
    arguments = parse_arguments()
    word_path = arguments.word
    additions_path = arguments.additions

    try:
        if word_path.resolve() == additions_path.resolve():
            raise LexiconError("word.csv and additions.csv must be different files")
        if not word_path.is_file():
            raise LexiconError(f"Lexicon file does not exist: {word_path}")
        if not additions_path.is_file():
            raise LexiconError(f"Additions file does not exist: {additions_path}")

        icu, collator = build_collator()
        word_file = load_file(word_path)
        additions_file = load_file(additions_path)

        accepted, duplicate_counts = filter_additions(
            word_file.records,
            additions_file.records,
        )
        plan = insertion_plan(word_file, accepted, collator)

        inserted = [
            record
            for anchor in sorted(plan)
            for record in plan[anchor]
        ]

        if inserted:
            atomic_replace(
                path=word_path,
                bom=word_file.bom,
                lines=word_file.lines,
                insertions=plan,
                newline=choose_newline(word_file.lines),
            )

        print_report(
            icu=icu,
            inserted=inserted,
            duplicates=duplicate_counts,
            word_path=word_path,
        )
        return 0
    except LexiconError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2
    except OSError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
