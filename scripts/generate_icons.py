#!/usr/bin/env python3
"""
Generate MobiWall app icon and Play Store icon.
Creates a shield + barrier logo in theme colors (deep orange / dark).
"""
from pathlib import Path
from PIL import Image, ImageDraw

# Theme: deep orange primary; icon has transparent background
PRIMARY = (255, 87, 34, 255)   # deep orange 500
PRIMARY_DARK = (230, 74, 25, 255)
TRANSPARENT = (0, 0, 0, 0)
WHITE = (255, 255, 255, 255)

def draw_shield_icon(size: int, padding_ratio: float = 0.12) -> Image.Image:
    """Draw MobiWall logo: shield with horizontal barrier lines (firewall), transparent background."""
    img = Image.new("RGBA", (size, size), TRANSPARENT)
    d = ImageDraw.Draw(img)
    p = int(size * padding_ratio)
    w, h = size - 2 * p, size - 2 * p
    cx, cy = size // 2, p + h // 2

    # Shield path (rounded top, pointed bottom) in relative coords 0..1
    def shield_path():
        # x,y as fraction of w,h from top-left of content box
        return [
            (0.50, 0.00), (0.98, 0.12), (0.98, 0.55), (0.50, 0.98), (0.02, 0.55), (0.02, 0.12),
        ]

    def to_pixel(px: float, py: float):
        x = p + px * w
        y = p + py * h
        return (x, y)

    points = [to_pixel(px, py) for px, py in shield_path()]

    # Draw shield fill (primary color)
    d.polygon(points, fill=PRIMARY, outline=PRIMARY_DARK, width=max(1, size // 48))

    # Draw 3 horizontal "barrier" lines inside shield (firewall motif)
    line_y_fracs = [0.35, 0.50, 0.65]
    line_h = max(2, size // 32)
    for i, yf in enumerate(line_y_fracs):
        y = int(p + yf * h)
        # Clip line to shield width at this y (approx ellipse)
        half_w = int(w * 0.35 * (1 - 1.2 * abs(yf - 0.5)))
        x1, x2 = cx - half_w, cx + half_w
        d.rectangle([x1, y - line_h // 2, x2, y + line_h // 2], fill=WHITE)

    return img


def main():
    script_dir = Path(__file__).resolve().parent
    project_root = script_dir.parent
    res = project_root / "android" / "app" / "src" / "main" / "res"

    # Android mipmap sizes (dp): mdpi=48, hdpi=72, xhdpi=96, xxhdpi=144, xxxhdpi=192
    mipmaps = [
        ("mipmap-mdpi", 48),
        ("mipmap-hdpi", 72),
        ("mipmap-xhdpi", 96),
        ("mipmap-xxhdpi", 144),
        ("mipmap-xxxhdpi", 192),
    ]

    for folder, px in mipmaps:
        out_dir = res / folder
        out_dir.mkdir(parents=True, exist_ok=True)
        icon = draw_shield_icon(px)
        icon.save(out_dir / "ic_launcher.png", "PNG", optimize=True)
        print(f"  {folder}/ic_launcher.png ({px}x{px})")

    # Play Store: 512x512 (use for store listing)
    store_dir = project_root / "store"
    store_dir.mkdir(parents=True, exist_ok=True)
    play_icon = draw_shield_icon(512)
    play_icon.save(store_dir / "ic_launcher_512.png", "PNG", optimize=True)
    print(f"  store/ic_launcher_512.png (512x512, for Play Store)")

    print("Done. App icon and Play Store icon generated.")


if __name__ == "__main__":
    main()
