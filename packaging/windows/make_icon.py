from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--ico", type=Path, required=True)
    parser.add_argument("--web", type=Path, required=True)
    args = parser.parse_args()

    with Image.open(args.source) as source:
        image = source.convert("RGBA")
        if image.width != image.height:
            edge = min(image.width, image.height)
            left = (image.width - edge) // 2
            top = (image.height - edge) // 2
            image = image.crop((left, top, left + edge, top + edge))

        args.ico.parent.mkdir(parents=True, exist_ok=True)
        image.save(
            args.ico,
            format="ICO",
            sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (256, 256)],
        )

        args.web.parent.mkdir(parents=True, exist_ok=True)
        web_image = image.resize((128, 128), Image.Resampling.LANCZOS)
        web_image.save(args.web, format="PNG", optimize=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
