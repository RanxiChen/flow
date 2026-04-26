#!/usr/bin/env python3
"""Generate BreezeCore simulation JSON from a linked ELF image."""

from __future__ import annotations

import argparse
import json
import os
import struct
from dataclasses import dataclass
from pathlib import Path


PT_LOAD = 1


@dataclass(frozen=True)
class ElfProgramHeader:
    p_type: int
    p_offset: int
    p_vaddr: int
    p_paddr: int
    p_filesz: int
    p_memsz: int


@dataclass(frozen=True)
class ElfImage:
    entry: int
    program_headers: list[ElfProgramHeader]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert a linked ELF file into BreezeCore memory.json format."
    )
    parser.add_argument("--elf", required=True, help="Input ELF file.")
    parser.add_argument("--output", required=True, help="Output JSON path.")
    parser.add_argument(
        "--bootaddr",
        default=None,
        help="Override simulation.bootaddr. Defaults to ELF entry address.",
    )
    parser.add_argument(
        "--tandem-log",
        default="true",
        choices=("true", "false"),
        help="Whether to set simulation.tandemLog in the output JSON.",
    )
    return parser.parse_args()


def parse_numeric(value: str) -> int:
    text = value.strip().lower()
    if text.startswith("0x"):
        return int(text, 16)
    return int(text, 10)


def parse_elf_image(elf_path: Path) -> ElfImage:
    raw = elf_path.read_bytes()
    if len(raw) < 16 or raw[:4] != b"\x7fELF":
        raise ValueError(f"{elf_path} is not a valid ELF file")

    elf_class = raw[4]
    data_encoding = raw[5]

    if data_encoding == 1:
        endian = "<"
    elif data_encoding == 2:
        endian = ">"
    else:
        raise ValueError(f"unsupported ELF data encoding: {data_encoding}")

    if elf_class == 1:
        header_fmt = endian + "HHIIIIIHHHHHH"
        ph_fmt = endian + "IIIIIIII"
        (
            _e_type,
            _e_machine,
            _e_version,
            e_entry,
            e_phoff,
            _e_shoff,
            _e_flags,
            _e_ehsize,
            e_phentsize,
            e_phnum,
            _e_shentsize,
            _e_shnum,
            _e_shstrndx,
        ) = struct.unpack_from(header_fmt, raw, 16)
    elif elf_class == 2:
        header_fmt = endian + "HHIQQQIHHHHHH"
        ph_fmt = endian + "IIQQQQQQ"
        (
            _e_type,
            _e_machine,
            _e_version,
            e_entry,
            e_phoff,
            _e_shoff,
            _e_flags,
            _e_ehsize,
            e_phentsize,
            e_phnum,
            _e_shentsize,
            _e_shnum,
            _e_shstrndx,
        ) = struct.unpack_from(header_fmt, raw, 16)
    else:
        raise ValueError(f"unsupported ELF class: {elf_class}")

    expected_ph_size = struct.calcsize(ph_fmt)
    if e_phentsize != expected_ph_size:
        raise ValueError(
            f"unexpected program header size: got {e_phentsize}, expected {expected_ph_size}"
        )

    program_headers: list[ElfProgramHeader] = []
    for index in range(e_phnum):
        ph_off = e_phoff + index * e_phentsize
        if elf_class == 1:
            (
                p_type,
                p_offset,
                p_vaddr,
                p_paddr,
                p_filesz,
                p_memsz,
                _p_flags,
                _p_align,
            ) = struct.unpack_from(ph_fmt, raw, ph_off)
        else:
            (
                p_type,
                _p_flags,
                p_offset,
                p_vaddr,
                p_paddr,
                p_filesz,
                p_memsz,
                _p_align,
            ) = struct.unpack_from(ph_fmt, raw, ph_off)

        program_headers.append(
            ElfProgramHeader(
                p_type=p_type,
                p_offset=p_offset,
                p_vaddr=p_vaddr,
                p_paddr=p_paddr,
                p_filesz=p_filesz,
                p_memsz=p_memsz,
            )
        )

    return ElfImage(entry=e_entry, program_headers=program_headers)


def build_memory_image(elf_path: Path, elf: ElfImage) -> dict[int, int]:
    raw = elf_path.read_bytes()
    memory_bytes: dict[int, int] = {}

    for ph in elf.program_headers:
        if ph.p_type != PT_LOAD or ph.p_memsz == 0:
            continue

        load_addr = ph.p_paddr if ph.p_paddr != 0 else ph.p_vaddr
        segment = raw[ph.p_offset : ph.p_offset + ph.p_filesz]

        for offset, byte in enumerate(segment):
            memory_bytes[load_addr + offset] = byte

        for offset in range(ph.p_filesz, ph.p_memsz):
            memory_bytes.setdefault(load_addr + offset, 0)

    if not memory_bytes:
        raise ValueError(f"{elf_path} has no PT_LOAD segments to export")

    return memory_bytes


def pack_memory_map(memory_bytes: dict[int, int]) -> dict[str, str]:
    start_addr = min(memory_bytes)
    end_addr = max(memory_bytes)
    aligned_start = start_addr & ~0x7
    aligned_end = (end_addr + 8) & ~0x7

    memory_map: dict[str, str] = {}
    for base in range(aligned_start, aligned_end, 8):
        word = 0
        for lane in range(8):
            word |= memory_bytes.get(base + lane, 0) << (lane * 8)
        memory_map[f"0x{base:x}"] = f"0x{word:016x}"

    return memory_map


def build_output_document(
    *,
    test_name: str,
    source_path: Path,
    elf_path: Path,
    output_path: Path,
    bootaddr: int,
    tandem_log: bool,
    memory_map: dict[str, str],
) -> dict[str, object]:
    return {
        "meta": {
            "name": test_name,
            "source": os.path.relpath(source_path, start=output_path.parent.parent.parent),
            "elf": os.path.relpath(elf_path, start=output_path.parent.parent.parent),
            "generator": os.path.relpath(Path(__file__), start=output_path.parent.parent.parent),
        },
        "simulation": {
            "bootaddr": f"0x{bootaddr:x}",
            "tandemLog": tandem_log,
        },
        "memoryMap": memory_map,
    }


def main() -> None:
    args = parse_args()

    elf_path = Path(args.elf).resolve()
    output_path = Path(args.output).resolve()
    test_dir = Path(__file__).resolve().parent
    source_path = test_dir / f"{elf_path.stem}.S"

    elf = parse_elf_image(elf_path)
    memory_bytes = build_memory_image(elf_path, elf)
    memory_map = pack_memory_map(memory_bytes)

    bootaddr = parse_numeric(args.bootaddr) if args.bootaddr is not None else elf.entry
    tandem_log = args.tandem_log == "true"

    doc = build_output_document(
        test_name=elf_path.stem,
        source_path=source_path,
        elf_path=elf_path,
        output_path=output_path,
        bootaddr=bootaddr,
        tandem_log=tandem_log,
        memory_map=memory_map,
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(doc, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
