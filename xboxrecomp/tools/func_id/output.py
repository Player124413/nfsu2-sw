"""Output helpers for the function-identification pipeline.

The identifiers use integer VAs internally, while the other tools exchange
function databases as JSON with hexadecimal ``start`` fields.  Keep that
conversion in one place so ``abi_analysis`` and ``recomp`` can consume the
result without special cases.
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Optional


def _write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)
        stream.write("\n")
    os.replace(temporary, path)


def _classification(address: int, rw_results: dict, crt_results: dict,
                    propagated: dict, stub_results: Optional[dict]) -> dict:
    """Return the strongest classification available for one function."""
    # Direct evidence wins over call-graph propagation.  Vtable and stub
    # results are already folded into propagated by identify.run, but keeping
    # stub_results as a fallback makes this helper safe for older callers too.
    candidates = (
        (rw_results.get(address), "rw"),
        (crt_results.get(address), "crt"),
        ((stub_results or {}).get(address), "stub"),
        (propagated.get(address), "propagated"),
    )
    for info, source in candidates:
        if info:
            result = dict(info)
            if source == "crt":
                result.setdefault("category", "crt")
            result.setdefault("category", "unknown")
            result.setdefault("confidence", 0.0)
            result.setdefault("method", "unknown")
            return result
    return {"category": "unknown", "confidence": 0.0, "method": "unidentified"}


def _build_enriched_db(functions: list, rw_results: dict, crt_results: dict,
                       propagated: dict, rw_modules: dict,
                       stub_results: Optional[dict] = None) -> list:
    """Merge classifications into the complete disassembler function list.

    This is kept as a separate helper because reports and small validation
    tools use the merge without wanting to write the full output directory.
    ``rw_modules`` is accepted as part of the historical helper signature; the
    module details are already present in each RenderWare classification.
    """
    del rw_modules  # retained for API compatibility
    records = []
    for function in sorted(functions, key=lambda item: int(item["start"], 16)):
        address = int(function["start"], 16)
        record = dict(function)
        info = _classification(address, rw_results, crt_results, propagated,
                               stub_results)

        # Identification metadata is additive: preserve disassembler fields
        # such as end, size, calls_to and called_by for downstream consumers.
        record.update(info)
        record["start"] = f"0x{address:08X}"
        # ``name`` from a direct signature is useful; inferred classifications
        # should not erase the disassembler's stable sub_ name.
        if not info.get("name"):
            record["name"] = function.get("name", f"sub_{address:08X}")
        records.append(record)
    return records


def write_results(functions: list, rw_results: dict, crt_results: dict,
                  propagated: dict, rw_modules: dict, output_dir: str,
                  verbose: bool = False, stub_results: Optional[dict] = None) -> dict:
    """Write identified function records and return aggregate statistics.

    ``functions`` is the disassembler's list of function records.  Every input
    function is retained, including unknown functions, because the recompiler
    uses this file as a complete address-indexed classification database.
    """
    output = Path(output_dir)
    records = _build_enriched_db(functions, rw_results, crt_results,
                                 propagated, rw_modules, stub_results)
    category_counts = {}
    method_counts = {}
    for record in records:
        category = record.get("category", "unknown")
        method = record.get("method", "unknown")
        category_counts[category] = category_counts.get(category, 0) + 1
        method_counts[method] = method_counts.get(method, 0) + 1

    identified = sum(1 for record in records
                     if record.get("category") not in (None, "", "unknown"))
    summary = {
        "total": len(records),
        "identified": identified,
        "unidentified": len(records) - identified,
        "by_category": dict(sorted(category_counts.items())),
        "by_method": dict(sorted(method_counts.items())),
        "rw_modules": len(rw_modules or {}),
    }

    _write_json(output / "identified_functions.json", records)
    _write_json(output / "summary.json", summary)
    # This supplemental file is useful to reports and preserves the module
    # information that is otherwise only available during this process.
    _write_json(output / "rw_modules.json", rw_modules or {})

    if verbose:
        print(f"  Identified: {identified:,d}/{len(records):,d}")
        for category, count in sorted(category_counts.items()):
            print(f"    {category}: {count:,d}")

    return summary
