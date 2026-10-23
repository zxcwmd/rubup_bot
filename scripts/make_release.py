from __future__ import annotations

from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "release" / "rubup_bot.zip"
PREFIX = Path("rubup_bot_project")


def release_files() -> list[Path]:
    files = [
        ROOT / name
        for name in (
            ".env.example",
            ".gitignore",
            "README.md",
            "pyproject.toml",
            "requirements.txt",
            "requirements-dev.txt",
            "selectors.json",
        )
    ]
    for folder, patterns in (
        ("rubup_bot", ("*.py",)),
        ("templates", ("*.txt",)),
        ("scripts", ("*.sh", "*.bat", "*.py")),
        ("scripts/android", ("*.sh",)),
        ("tests", ("*.py",)),
        ("docs", ("*.md",)),
        ("images", (".gitkeep",)),
    ):
        for pattern in patterns:
            files.extend((ROOT / folder).glob(pattern))
    return sorted(set(files))


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    files = release_files()
    with ZipFile(OUTPUT, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for path in files:
            if not path.is_file() or path.is_symlink():
                raise RuntimeError(f"Invalid release input: {path}")
            archive.write(path, PREFIX / path.relative_to(ROOT))
    print(f"Created {OUTPUT} ({len(files)} files, {OUTPUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
