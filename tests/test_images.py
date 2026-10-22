from pathlib import Path

from PIL import Image

from rubup_bot.db import Database
from rubup_bot.images import ImageLibrary


async def test_import_deduplicates_content_and_keeps_sources(db: Database, tmp_path: Path) -> None:
    incoming = tmp_path / "incoming"
    managed = tmp_path / "managed"
    incoming.mkdir()
    Image.new("RGB", (20, 20), "red").save(incoming / "one.png")
    Image.new("RGB", (20, 20), "red").save(incoming / "copy.png")
    (incoming / "broken.png").write_bytes(b"not an image")
    library = ImageLibrary(incoming, managed, db)

    stats = await library.scan()
    assert stats.added == 1
    assert stats.duplicates == 1
    assert stats.invalid == 1
    assert (incoming / "one.png").exists()
    asset = await db.next_available_image()
    assert asset and asset.path.parent == managed
    assert asset.path.name.startswith(asset.sha256)

    stats = await library.scan()
    assert stats.added == 0
    assert stats.duplicates == 2


async def test_changed_source_is_a_new_image(db: Database, tmp_path: Path) -> None:
    incoming = tmp_path / "incoming"
    incoming.mkdir()
    source = incoming / "changed.png"
    Image.new("RGB", (20, 20), "blue").save(source)
    library = ImageLibrary(incoming, tmp_path / "managed", db)
    assert (await library.scan()).added == 1
    Image.new("RGB", (20, 20), "green").save(source)
    assert (await library.scan()).added == 1
    assert (await db.image_counts())["available"] == 2
