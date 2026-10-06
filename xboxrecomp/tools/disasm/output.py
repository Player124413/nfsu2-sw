"""Serialization and human-readable output for the XBE disassembler.

The analysis pipeline keeps rich Python objects in memory while it is running.
This module is the small boundary between that in-memory representation and the
JSON databases consumed by ``func_id``, ``abi_analysis`` and ``recomp``.

Addresses in the JSON files are deliberately written as eight-digit hexadecimal
strings.  That is the format used by the rest of the tools and avoids losing
information when a database is edited or inspected by hand.
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import asdict, is_dataclass
from pathlib import Path
from typing import Iterable, Optional

from .engine import DisasmEngine, Instruction
from .functions import FunctionDetector
from .labels import LabelManager
from .loader import BinaryImage, SectionInfo
from .xrefs import XRefTracker


_JSON_INDENT = 2
_HEX_ADDRESS_FIELDS = {"address", "start", "end", "from", "to", "call_target",
                       "jump_target", "memory_ref", "imm_ref"}


def _address(value: int | str) -> int:
    """Return an integer address from the formats accepted by the tools."""
    if isinstance(value, str):
        return int(value, 0)
    return int(value)


def _hex_address(value: int | str) -> str:
    return f"0x{_address(value):08X}"


def _write_json(path: Path, value: object) -> None:
    """Write a UTF-8 JSON document, creating its parent directory first."""
    path.parent.mkdir(parents=True, exist_ok=True)
    # Use a temporary sibling so an interrupted run cannot leave a valid-looking
    # but truncated database for the next workflow stage.
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=_JSON_INDENT, ensure_ascii=False)
        stream.write("\n")
    os.replace(temporary, path)


def _section_dict(section: SectionInfo) -> dict:
    """Serialize section metadata without exposing a dataclass implementation."""
    if is_dataclass(section):
        result = asdict(section)
    else:  # Useful for small test doubles and third-party callers.
        result = {
            name: getattr(section, name)
            for name in ("name", "virtual_addr", "virtual_size", "raw_addr",
                         "raw_size", "writable", "executable", "flags")
            if hasattr(section, name)
        }
    for key in ("virtual_addr", "raw_addr"):
        if key in result:
            result[key] = _hex_address(result[key])
    return result


def _string_dict(string_ref: dict) -> dict:
    """Convert the integer address returned by ``extract_strings`` for JSON."""
    result = dict(string_ref)
    if "address" in result:
        result["address"] = _hex_address(result["address"])
    return result


def _function_dict(function, engine: DisasmEngine) -> dict:
    """Serialize one function, including the instructions in its body."""
    result = function.to_dict()
    instructions = engine.get_instructions_in_range(function.start, function.end)
    result["instructions"] = [instruction.to_dict() for instruction in instructions]
    return result


def _safe_section_name(name: str) -> str:
    """Make a section name safe and deterministic as an output filename."""
    cleaned = re.sub(r"[^A-Za-z0-9_.-]+", "_", name)
    return cleaned if cleaned not in ("", ".", "..") else "section"


class OutputWriter:
    """Write all databases produced by a :class:`Disassembler` run."""

    def __init__(self, output_dir: str | os.PathLike[str],
                 engine: DisasmEngine, func_detector: FunctionDetector,
                 xrefs: XRefTracker, labels: LabelManager,
                 image: BinaryImage, strings: Iterable[dict]):
        self.output_dir = Path(output_dir)
        self.engine = engine
        self.func_detector = func_detector
        self.xrefs = xrefs
        self.labels = labels
        self.image = image
        self.strings = list(strings)

    def write_all(self, sections_to_disasm: Optional[Iterable[SectionInfo]] = None,
                  verbose: bool = False) -> None:
        """Write JSON analysis databases and one readable listing per section."""
        self.output_dir.mkdir(parents=True, exist_ok=True)

        functions = [
            _function_dict(function, self.engine)
            for function in sorted(self.func_detector.functions.values(),
                                   key=lambda item: item.start)
        ]
        xrefs = self.xrefs.to_list()
        strings = [_string_dict(value) for value in self.strings]
        labels = self.labels.to_list()
        summary = self._summary(len(functions), len(xrefs), len(labels),
                                len(strings))

        documents = {
            "functions.json": functions,
            "xrefs.json": xrefs,
            "strings.json": strings,
            "labels.json": labels,
            "summary.json": summary,
            # Older README/docs called this file stats.json. Keep the alias so
            # external scripts using that documented name continue to work.
            "stats.json": summary,
        }
        for filename, document in documents.items():
            _write_json(self.output_dir / filename, document)
            if verbose:
                print(f"  Wrote {filename} ({len(document):,d} entries)"
                      if isinstance(document, (list, dict)) else
                      f"  Wrote {filename}")

        sections = list(sections_to_disasm or [])
        for section in sections:
            path = self.output_dir / f"{_safe_section_name(section.name)}.asm"
            self._write_listing(path, section)
            if verbose:
                print(f"  Wrote {path.name}")

    def _summary(self, function_count: int, xref_count: int,
                 label_count: int, string_count: int) -> dict:
        """Build the metadata consumed by the cache and recompilation tools."""
        detector_summary = self.func_detector.summary()
        section_entries = [_section_dict(section) for section in self.image.sections]
        return {
            "binary": str(self.image.filepath),
            "base_address": _hex_address(self.image.base_address),
            "image_size": self.image.image_size,
            "entry_point": _hex_address(self.image.entry_point),
            "kernel_thunk_address": _hex_address(self.image.kernel_thunk_addr),
            "sections": section_entries,
            "total_instructions": len(self.engine.instructions),
            "total_functions": function_count,
            "total_xrefs": xref_count,
            "total_labels": label_count,
            "total_strings": string_count,
            "function_summary": detector_summary,
            "xref_by_type": self.xrefs.count_by_type(),
        }

    def _write_listing(self, path: Path, section: SectionInfo) -> None:
        """Write a compact annotated assembly listing for one analyzed section."""
        start = section.virtual_addr
        end = start + section.virtual_size
        instructions = self.engine.get_instructions_in_range(start, end)
        functions_by_start = {
            function.start: function
            for function in self.func_detector.functions.values()
        }

        lines = [
            f"; XBE disassembly: {self.image.filepath}",
            f"; section {section.name} VA=0x{start:08X} size=0x{section.virtual_size:X}",
            "",
        ]
        for instruction in instructions:
            function = functions_by_start.get(instruction.address)
            if function is not None:
                lines.append(f"{function.name}:")
            label = self.labels.get_name(instruction.address)
            if label and (function is None or label != function.name):
                lines.append(f"{label}:")
            bytes_text = instruction.bytes_hex.upper()
            operand = f" {instruction.op_str}" if instruction.op_str else ""
            lines.append(
                f"  {instruction.address:08X}  {bytes_text:<24} "
                f"{instruction.mnemonic}{operand}"
            )
        _write_text(path, "\n".join(lines) + "\n")


def _write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(text, encoding="utf-8", newline="\n")
    os.replace(temporary, path)


def print_stats(engine: DisasmEngine, func_detector: FunctionDetector,
                xrefs: XRefTracker, labels: LabelManager,
                strings: Iterable[dict], image: BinaryImage) -> None:
    """Print a concise, stable summary for interactive and CI runs."""
    function_summary = func_detector.summary()
    xref_counts = xrefs.count_by_type()
    string_count = len(list(strings)) if not isinstance(strings, list) else len(strings)

    print(f"\n{'=' * 60}")
    print("  Disassembly Summary")
    print(f"{'=' * 60}")
    print(f"  Binary: {image.filepath}")
    print(f"  Instructions: {len(engine.instructions):,d}")
    print(f"  Functions: {function_summary.get('total_functions', 0):,d}")
    print(f"  Cross-references: {xrefs.count():,d}")
    print(f"  Labels: {labels.count():,d}")
    print(f"  Strings: {string_count:,d}")
    if xref_counts:
        print("  Xrefs by type:")
        for kind, count in sorted(xref_counts.items()):
            print(f"    {kind}: {count:,d}")
    print(f"{'=' * 60}")
