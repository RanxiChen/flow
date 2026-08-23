#!/usr/bin/env python3
"""Filter a Flow compact event history without loading it into memory."""

import argparse
import sys


def parse_number(value):
    return int(value, 0)


def parse_fields(line):
    fields = {}
    for token in line.split():
        if "=" in token:
            key, value = token.split("=", 1)
            fields[key] = value
    return fields


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("trace")
    parser.add_argument("--kind", action="append")
    parser.add_argument("--hart", type=int)
    parser.add_argument("--cycle-start", type=parse_number)
    parser.add_argument("--cycle-end", type=parse_number)
    parser.add_argument("--pc", type=parse_number)
    parser.add_argument("--address-start", type=parse_number)
    parser.add_argument("--address-end", type=parse_number)
    args = parser.parse_args()

    kinds = set(args.kind or [])
    with open(args.trace, "r") as trace:
        for line in trace:
            if line.startswith("#"):
                continue
            fields = parse_fields(line)
            if kinds and fields.get("kind") not in kinds:
                continue
            if args.hart is not None and fields.get("hart") != str(args.hart):
                continue
            cycle = parse_number(fields.get("cycle", "0"))
            if args.cycle_start is not None and cycle < args.cycle_start:
                continue
            if args.cycle_end is not None and cycle >= args.cycle_end:
                continue
            if args.pc is not None and parse_number(fields.get("pc", "-1")) != args.pc:
                continue
            if args.address_start is not None or args.address_end is not None:
                if "addr" not in fields:
                    continue
                if fields.get("kind") == "R" and fields.get("memen") != "1":
                    continue
                address = parse_number(fields["addr"])
                if args.address_start is not None and address < args.address_start:
                    continue
                if args.address_end is not None and address >= args.address_end:
                    continue
            print(line, end="")


if __name__ == "__main__":
    try:
        main()
    except BrokenPipeError:
        sys.stdout.close()
