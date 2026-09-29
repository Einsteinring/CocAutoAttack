"""
Собирает встроенный набор шаблонов кнопок для APK.

Вход: снимки экрана в папке raw/screens/ (из архива приложения или обычные скриншоты телефона
в исходном разрешении) и файл tools/pack_spec.json с описанием, какую кнопку откуда вырезать.

Выход: app/src/main/assets/templates/*.png и pack.json с высотой экрана,
по которой приложение потом масштабирует шаблоны под другие телефоны.

Запуск:
    python tools/make_pack.py            собрать набор
    python tools/make_pack.py --check    собрать и проверить на всех снимках (нужен opencv-python)

Формат pack_spec.json:
{
  "lang": "ru",
  "templates": {
    "attack": {"from": "screens/screen_123.png", "box": [x1, y1, x2, y2]},
    "okay":   {"file": "templates/okay.png"}
  }
}
"box" — прямоугольник в пикселях исходного снимка, "file" — готовая вырезка из архива как есть.
"""

import json
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "raw"
SPEC = ROOT / "tools" / "pack_spec.json"
OUT = ROOT / "app" / "src" / "main" / "assets" / "templates"


def load_spec() -> dict:
    with SPEC.open(encoding="utf-8") as f:
        return json.load(f)


def build(spec: dict) -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("*.png"):
        old.unlink()

    heights = set()
    widths = set()
    for name, item in spec["templates"].items():
        if "file" in item:
            src_path = RAW / item["file"]
            img = Image.open(src_path).convert("RGB")
            src_note = item["file"]
            ref = item.get("screen")
            if ref:
                with Image.open(RAW / ref) as screen:
                    heights.add(screen.height)
                    widths.add(screen.width)
        else:
            src_path = RAW / item["from"]
            with Image.open(src_path) as screen:
                heights.add(screen.height)
                widths.add(screen.width)
                x1, y1, x2, y2 = item["box"]
                img = screen.convert("RGB").crop((x1, y1, x2, y2))
            src_note = f'{item["from"]} {item["box"]}'
        img.save(OUT / f"{name}.png")
        print(f"  {name}.png  {img.width}x{img.height}  из {src_note}")

    if len(heights) > 1:
        sys.exit(f"Снимки разной высоты: {sorted(heights)}. Нужны снимки с одного телефона.")
    ref_height = heights.pop() if heights else 0
    ref_width = widths.pop() if len(widths) == 1 else 0
    pack = {
        "refHeight": ref_height,
        "refWidth": ref_width,
        "lang": spec.get("lang", ""),
        "device": spec.get("device", ""),
        "game": spec.get("game", ""),
    }
    (OUT / "pack.json").write_text(json.dumps(pack, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Готово: {len(spec['templates'])} шаблонов, высота экрана {ref_height}")
    return ref_height


def check(spec: dict) -> None:
    """Каждый шаблон против каждого снимка: свой экран должен дать высокий балл, чужие — низкий."""
    try:
        import cv2
    except ImportError:
        sys.exit("Для проверки нужен opencv-python: python -m pip install opencv-python")

    # Проверяем на снимках, приведённых к рабочей высоте, если они есть: в этом масштабе ищет приложение.
    # Снимки могут быть и из приложения (PNG), и системные скриншоты телефона (часто JPG).
    folder = RAW / "norm" if (RAW / "norm").is_dir() else RAW / "screens"
    screens = sorted(
        p for p in folder.iterdir()
        if p.suffix.lower() in (".png", ".jpg", ".jpeg")
    )
    names = list(spec["templates"].keys())
    print()
    print("Лучшее совпадение каждого шаблона на каждом снимке (порог в приложении 0.80):")
    header = "снимок".ljust(34) + "".join(n[:11].rjust(12) for n in names)
    print(header)
    for s in screens:
        img = cv2.imread(str(s))
        row = s.name[:33].ljust(34)
        for n in names:
            tpl = cv2.imread(str(OUT / f"{n}.png"))
            if tpl is None or tpl.shape[0] > img.shape[0] or tpl.shape[1] > img.shape[1]:
                row += "—".rjust(12)
                continue
            score = cv2.minMaxLoc(cv2.matchTemplate(img, tpl, cv2.TM_CCOEFF_NORMED))[1]
            mark = "*" if score >= 0.8 else " "
            row += f"{score:.2f}{mark}".rjust(12)
        print(row)
    print("* — выше порога. У каждой кнопки звёздочка должна стоять только на экранах, где она есть.")


if __name__ == "__main__":
    spec = load_spec()
    build(spec)
    if "--check" in sys.argv:
        check(spec)
