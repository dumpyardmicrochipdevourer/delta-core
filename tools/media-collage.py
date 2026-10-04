#!/usr/bin/env python3
"""Lays out the pictures of a post as one grid: media-collage.py <out.jpg> <in1.jpg> <in2.jpg> ...

No cropping and no padding: pictures in a row share a height, rows share a width, post order
is kept. Of all the ways to split into rows it takes the one closest to a square with rows of
similar height. Only the layout is computed here, ffmpeg draws it. Standard library only.
"""
import itertools
import json
import math
import subprocess
import sys

LONG_SIDE = 2560
GAP = 8
TARGET_ASPECT = 1.0
ROW_BALANCE_WEIGHT = 0.5


def aspect_of(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0",
         "-show_entries", "stream=width,height", "-of", "json", path],
        capture_output=True, text=True, check=True).stdout
    stream = json.loads(out)["streams"][0]
    return stream["width"] / stream["height"]


def partitions(n):
    """Every way to cut n pictures in a row into rows; 512 of them for n <= 10."""
    for cuts in itertools.product((False, True), repeat=n - 1):
        rows, current = [], 1
        for cut in cuts:
            if cut:
                rows.append(current)
                current = 1
            else:
                current += 1
        rows.append(current)
        yield rows


def row_heights(rows, aspects):
    """Height of each row at total width 1: a row with summed aspect a is 1/a tall."""
    heights, i = [], 0
    for count in rows:
        heights.append(1 / sum(aspects[i:i + count]))
        i += count
    return heights


def score(rows, aspects):
    heights = row_heights(rows, aspects)
    aspect = 1 / sum(heights)
    shape = abs(math.log(aspect / TARGET_ASPECT))
    balance = math.log(max(heights) / min(heights))
    return shape + ROW_BALANCE_WEIGHT * balance


def even(value):
    return max(2, 2 * round(value / 2))


def main():
    if len(sys.argv) < 4:
        sys.exit("usage: media-collage.py <out.jpg> <in1> <in2> [...]")
    out, inputs = sys.argv[1], sys.argv[2:]

    aspects = [aspect_of(path) for path in inputs]
    rows = min(partitions(len(inputs)), key=lambda r: score(r, aspects))
    heights = row_heights(rows, aspects)

    aspect = 1 / sum(heights)
    width = LONG_SIDE if aspect >= 1 else even(LONG_SIDE * aspect)

    chains, row_labels, i = [], [], 0
    for r, (count, height) in enumerate(zip(rows, heights)):
        tile_height = even(width * height)
        tiles = []
        for k in range(count):
            label = f"t{i}"
            pad = f",pad=iw+{GAP}:ih:0:0:color=black" if k < count - 1 else ""
            chains.append(f"[{i}:v]format=yuv420p,scale=-2:{tile_height}{pad}[{label}]")
            tiles.append(f"[{label}]")
            i += 1
        joined = "".join(tiles)
        stack = f"{joined}hstack=inputs={count}," if count > 1 else f"{joined}"
        pad = f",pad=iw:ih+{GAP}:0:0:color=black" if r < len(rows) - 1 else ""
        chains.append(f"{stack}scale={width}:-2{pad}[r{r}]" if count > 1
                      else f"{joined}scale={width}:-2{pad}[r{r}]")
        row_labels.append(f"[r{r}]")

    if len(rows) > 1:
        chains.append(f"{''.join(row_labels)}vstack=inputs={len(rows)}[out]")
        final = "[out]"
    else:
        final = row_labels[0]

    cmd = ["ffmpeg", "-v", "error", "-y"]
    for path in inputs:
        cmd += ["-i", path]
    cmd += ["-filter_complex", ";".join(chains), "-map", final,
            "-frames:v", "1", "-q:v", "2", out]
    subprocess.run(cmd, check=True)
    print(f"rows={rows} size={width}x{even(width * sum(heights))}", file=sys.stderr)


if __name__ == "__main__":
    main()
