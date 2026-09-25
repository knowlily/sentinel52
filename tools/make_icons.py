"""从一张方形图标源图生成 Android 各密度的启动图标资源。

用法：
    "D:/python/python.exe" tools/make_icons.py design/ic_launcher_source.png

生成（res/ 下）：
    mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png             传统密度图标（给不走自适应图标的场合兜底）
    mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png  自适应图标的前景层（108dp 画布，图缩到 75% 居中）
    values/colors.xml 里的 ic_launcher_background           自适应图标的底色（取源图四角像素）

为什么前景只占 75%：自适应图标的可见安全区是 108dp 里的 72dp，直接把图铺满会被系统遮罩切掉一圈；
而这套图是「白底 + 居中符号」，缩到 75% 居中后，图的白色和底色拼在一起看不出接缝，符号也不会被切。
"""
import sys
from pathlib import Path

from PIL import Image

# 传统图标尺寸（48dp 基准）
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# 自适应图标画布 108dp
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
ART_RATIO = 0.75

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"


def background_color(img: Image.Image) -> tuple[int, int, int]:
    """取图标底色。

    不能取四角：应用图标一般是「圆角方块 + 四角透明」，四角读出来是 (0,0,0,0)。
    改成取四条边的中点（缩进几像素）——那里一定是底，再把不透明像素里最常见的颜色当兜底。
    """
    w, h = img.size
    inset = max(2, w // 64)
    samples = [
        img.getpixel((w // 2, inset)),
        img.getpixel((w // 2, h - 1 - inset)),
        img.getpixel((inset, h // 2)),
        img.getpixel((w - 1 - inset, h // 2)),
    ]
    opaque = [s[:3] for s in samples if s[3] == 255]
    if opaque:
        return max(set(opaque), key=opaque.count)

    counter: dict[tuple[int, int, int], int] = {}
    for x in range(0, w, 2):
        for y in range(0, h, 2):
            r, g, b, a = img.getpixel((x, y))
            if a == 255:
                counter[(r, g, b)] = counter.get((r, g, b), 0) + 1
    if not counter:
        raise SystemExit("这张图没有完全不透明的像素，无法判定底色")
    return max(counter.items(), key=lambda kv: kv[1])[0]


def write(img: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)
    print(f"  {path.relative_to(RES.parent.parent.parent)}  {img.size[0]}x{img.size[1]}")


def monochrome_layer(art: Image.Image, canvas: int, bg: tuple[int, int, int]) -> Image.Image:
    """主题图标（Android 13+ 单色图标）用的剪影层：底色部分透明，其余压成黑色。

    alpha 按「和底色的色差」算（差得越多越实），这样抗锯齿边缘不会变成硬边。
    """
    art_size = int(round(canvas * ART_RATIO))
    scaled = art.resize((art_size, art_size), Image.LANCZOS)
    layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    offset = (canvas - art_size) // 2
    src, dst = scaled.load(), layer.load()
    for x in range(art_size):
        for y in range(art_size):
            r, g, b, a = src[x, y]
            if a == 0:
                continue
            diff = max(abs(r - bg[0]), abs(g - bg[1]), abs(b - bg[2]))
            alpha = min(255, diff * 3)
            if alpha > 0:
                dst[x + offset, y + offset] = (0, 0, 0, min(a, alpha))
    return layer


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    src = Path(sys.argv[1])
    if not src.exists():
        print(f"找不到源图：{src}")
        return 1

    art = Image.open(src).convert("RGBA")
    if art.width != art.height:
        print(f"源图不是正方形（{art.size}），按短边居中裁一下")
        side = min(art.size)
        left = (art.width - side) // 2
        top = (art.height - side) // 2
        art = art.crop((left, top, left + side, top + side))
    print(f"源图 {src.name} {art.size}")

    bg = background_color(art)
    print(f"底色（边缘中点）: #{bg[0]:02x}{bg[1]:02x}{bg[2]:02x}")

    for bucket, size in LEGACY.items():
        write(art.resize((size, size), Image.LANCZOS), RES / f"mipmap-{bucket}" / "ic_launcher.png")

    for bucket, canvas in ADAPTIVE.items():
        art_size = int(round(canvas * ART_RATIO))
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        scaled = art.resize((art_size, art_size), Image.LANCZOS)
        offset = (canvas - art_size) // 2
        layer.paste(scaled, (offset, offset), scaled)
        write(layer, RES / f"mipmap-{bucket}" / "ic_launcher_foreground.png")
        write(monochrome_layer(art, canvas, bg), RES / f"mipmap-{bucket}" / "ic_launcher_monochrome.png")

    hex_color = "#FF{:02X}{:02X}{:02X}".format(*bg)
    print(f"\n自适应图标底色用这个值写进 values/colors.xml：")
    print(f'    <color name="ic_launcher_background">{hex_color}</color>')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
