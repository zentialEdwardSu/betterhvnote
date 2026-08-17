"""Create launcher resources from the supplied transparent character artwork."""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = {
    "app": ROOT / "icon-assets/sources/normal.png",
    "phone-app": ROOT / "icon-assets/sources/cheer.png",
}
BACKGROUND = {"app": "#F5E8DF", "phone-app": "#2D2425"}


def write_icon(module: str, source: Path) -> None:
    if not source.exists():
        raise SystemExit(f"Missing source image: {source}")
    res = ROOT / module / "src/main/res"
    adaptive = res / "mipmap-anydpi-v26"
    adaptive_v33 = res / "mipmap-anydpi-v33"
    foreground_dir = res / "drawable-nodpi"
    adaptive.mkdir(parents=True, exist_ok=True)
    adaptive_v33.mkdir(parents=True, exist_ok=True)
    foreground_dir.mkdir(parents=True, exist_ok=True)

    artwork = Image.open(source).convert("RGBA")
    alpha = artwork.getchannel("A")
    bbox = alpha.getbbox()
    if bbox:
        artwork = artwork.crop(bbox)
    side = max(artwork.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.alpha_composite(artwork, ((side - artwork.width) // 2, (side - artwork.height) // 2))

    # Adaptive icons reserve a 66% safe zone. Keep the character inside 72%
    # of the 108dp viewport so circle/squircle launchers retain the subject.
    foreground = Image.new("RGBA", (1080, 1080), (0, 0, 0, 0))
    scaled = square.resize((778, 778), Image.Resampling.LANCZOS)
    foreground.alpha_composite(scaled, ((1080 - scaled.width) // 2, (1080 - scaled.height) // 2))
    foreground.save(foreground_dir / "ic_launcher_foreground.png", optimize=True)
    monochrome = Image.new("RGBA", foreground.size, (0, 0, 0, 0))
    monochrome.putalpha(foreground.getchannel("A"))
    monochrome.save(foreground_dir / "ic_launcher_monochrome.png", optimize=True)

    (adaptive / "ic_launcher.xml").write_text(
        f'''<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@color/icon_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n</adaptive-icon>\n''',
        encoding="utf-8",
    )
    (adaptive / "ic_launcher_round.xml").write_text(
        (adaptive / "ic_launcher.xml").read_text(encoding="utf-8"), encoding="utf-8"
    )
    themed_xml = f'''<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@color/icon_background" />\n    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n'''
    (adaptive_v33 / "ic_launcher.xml").write_text(themed_xml, encoding="utf-8")
    (adaptive_v33 / "ic_launcher_round.xml").write_text(themed_xml, encoding="utf-8")
    values = res / "values"
    values.mkdir(parents=True, exist_ok=True)
    color_file = values / "icon_colors.xml"
    color_file.write_text(
        f'''<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="icon_background">{BACKGROUND[module]}</color>\n</resources>\n''',
        encoding="utf-8",
    )


for module, source in SOURCE.items():
    write_icon(module, source)
