#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR=""
OUTPUT_DIR=""
TOP="PegasusTop"
PART="xcu280-fsvh2892-2L-e"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --source_dir)
      SOURCE_DIR="$2"
      shift 2
      ;;
    --output_dir)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --top)
      TOP="$2"
      shift 2
      ;;
    --part)
      PART="$2"
      shift 2
      ;;
    *)
      echo "Unknown arg: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$SOURCE_DIR" || -z "$OUTPUT_DIR" ]]; then
  echo "Usage: build-bitstream.sh --source_dir <dir> --output_dir <dir> [--top <top>] [--part <part>]" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TCL_FILE="${SCRIPT_DIR}/scripts/main.tcl"

if [[ ! -f "$TCL_FILE" ]]; then
  echo "Missing TCL script: $TCL_FILE" >&2
  exit 1
fi

unset LD_LIBRARY_PATH
unset LD_PRELOAD
unset PYTHONPATH
unset PYTHONHOME

vivado -mode batch -source "$TCL_FILE" -tclargs "$SOURCE_DIR" "$OUTPUT_DIR" "$TOP" "$PART"
