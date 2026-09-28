#!/usr/bin/env python3
"""Generate the NEXUS VIDEO launcher icon in the app's black/pink/cyan neon theme."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
SIZES = {'mipmap-mdpi': 48, 'mipmap-hdpi': 72, 'mipmap-xhdpi': 96,
         'mipmap-xxhdpi': 144, 'mipmap-xxxhdpi': 192}
S = 768
K = S / 512


def xy(v):
    return int(round(v * K))


def gradient(stops, vertical=0):
    strip = Image.new('RGBA', (S, 1), (0, 0, 0, 0))
    pix = strip.load()
    for x in range(S):
        t = x / max(1, S - 1)
        for j in range(len(stops) - 1):
            if stops[j][0] <= t <= stops[j + 1][0]:
                a, ca = stops[j]
                b, cb = stops[j + 1]
                q = (t - a) / (b - a) if b != a else 0
                pix[x, 0] = tuple(round(ca[c] + (cb[c] - ca[c]) * q) for c in range(4))
                break
        else:
            pix[x, 0] = stops[-1][1]
    return strip.resize((S, S), Image.Resampling.BICUBIC)


def make_icon():
    image = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    circle = Image.new('L', (S, S), 0)
    ImageDraw.Draw(circle).ellipse((xy(18), xy(18), xy(494), xy(494)), fill=255)

    # Deep black circular field with a faint colored center bloom.
    field = Image.new('RGBA', (S, S), (4, 4, 9, 255))
    radial = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    rd = ImageDraw.Draw(radial)
    rd.ellipse((xy(80), xy(62), xy(432), xy(450)), fill=(22, 9, 24, 110))
    radial = radial.filter(ImageFilter.GaussianBlur(xy(52)))
    field.alpha_composite(radial)
    image.paste(field, (0, 0), circle)

    # Broken neon ring, magenta on the left and cyan-green on the right.
    ring_glow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(ring_glow)
    box = (xy(43), xy(43), xy(469), xy(469))
    gd.arc(box, 96, 264, fill=(255, 40, 155, 210), width=xy(17))
    gd.arc(box, 276, 354, fill=(35, 255, 205, 210), width=xy(17))
    gd.arc(box, 6, 84, fill=(35, 255, 205, 210), width=xy(17))
    soft = ring_glow.filter(ImageFilter.GaussianBlur(xy(13)))
    image.alpha_composite(soft)
    ring = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    dr = ImageDraw.Draw(ring)
    dr.arc(box, 96, 264, fill=(255, 47, 158, 255), width=xy(8))
    dr.arc(box, 276, 354, fill=(43, 255, 168, 255), width=xy(8))
    dr.arc(box, 6, 84, fill=(43, 255, 168, 255), width=xy(8))
    image.alpha_composite(ring)

    # Bold geometric N, designed to survive the 48px mdpi launcher size.
    points = [(126, 377), (126, 135), (202, 135), (310, 275),
              (310, 135), (386, 135), (386, 377), (311, 377),
              (202, 239), (202, 377)]
    mask = Image.new('L', (S, S), 0)
    md = ImageDraw.Draw(mask)
    md.polygon([(xy(x), xy(y)) for x, y in points], fill=255)
    mask = mask.filter(ImageFilter.MaxFilter(xy(4) // 2 * 2 + 1))
    glow = mask.filter(ImageFilter.GaussianBlur(xy(18)))
    glow_layer = Image.new('RGBA', (S, S), (255, 47, 158, 0))
    glow_alpha = glow.point(lambda p: int(p * .48))
    glow_layer.putalpha(glow_alpha)
    cyan_glow = Image.new('RGBA', (S, S), (43, 255, 168, 0))
    cyan_glow.putalpha(glow.point(lambda p: int(p * .34)))
    image.alpha_composite(glow_layer)
    image.alpha_composite(cyan_glow)

    # Dark keyline, then metallic/neon fill clipped to the N silhouette.
    outline = mask.filter(ImageFilter.MaxFilter(xy(5) // 2 * 2 + 1))
    edge = Image.new('RGBA', (S, S), (5, 7, 14, 255))
    image.paste(edge, (0, 0), outline)
    fill = gradient([
        (0.0, (255, 54, 163, 255)),
        (0.28, (255, 112, 190, 255)),
        (0.50, (230, 237, 245, 255)),
        (0.70, (139, 238, 229, 255)),
        (1.0, (43, 255, 168, 255)),
    ])
    image.paste(fill, (0, 0), mask)

    # Compact play symbol integrated on the diagonal: black core with crisp neon rim.
    tri = [(233, 226), (233, 286), (285, 256)]
    tri_mask = Image.new('L', (S, S), 0)
    td = ImageDraw.Draw(tri_mask)
    td.polygon([(xy(x), xy(y)) for x, y in tri], fill=255)
    tri_mask = tri_mask.filter(ImageFilter.MaxFilter(xy(9) // 2 * 2 + 1))
    tri_glow = tri_mask.filter(ImageFilter.GaussianBlur(xy(8)))
    tg = Image.new('RGBA', (S, S), (43, 255, 168, 0))
    tg.putalpha(tri_glow.point(lambda p: int(p * .8)))
    image.alpha_composite(tg)
    td_layer = Image.new('RGBA', (S, S), (7, 7, 13, 255))
    image.paste(td_layer, (0, 0), tri_mask)
    play = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(play).polygon([(xy(x), xy(y)) for x, y in tri], fill=(245, 255, 255, 255))
    image.alpha_composite(play)

    # Fine metallic inner rim, kept well inside Android's launcher mask.
    rim = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(rim)
    d.ellipse((xy(31), xy(31), xy(481), xy(481)), outline=(232, 240, 250, 115), width=xy(2))
    image.alpha_composite(rim)
    return image


def main():
    icon = make_icon()
    for folder, size in SIZES.items():
        dest = RES / folder / 'ic_launcher.png'
        dest.parent.mkdir(parents=True, exist_ok=True)
        icon.resize((size, size), Image.Resampling.LANCZOS).save(dest, 'PNG', optimize=True)
        print(f'{dest}: {size}x{size} RGBA')

    # Contact sheet of the real mipmap sizes on dark launcher-like backgrounds.
    sheet = Image.new('RGB', (480, 126), (25, 25, 31))
    draw = ImageDraw.Draw(sheet)
    x = 9
    labels = [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]
    for label, size in labels:
        sample = icon.resize((size, size), Image.Resampling.LANCZOS)
        y = 7 + (96 - min(size, 96)) // 2
        if size > 96:
            sample = icon.resize((96, 96), Image.Resampling.LANCZOS)
            y = 7
        sheet.paste(sample, (x, y), sample)
        draw.text((x, 108), label, fill=(230, 230, 235))
        x += max(size if size <= 96 else 96, 48) + 15
    sheet.save('/tmp/nexus-video-icon-contact-sheet.png')
    icon.resize((512, 512), Image.Resampling.LANCZOS).save('/tmp/nexus-video-icon-512.png')
    print('Preview: /tmp/nexus-video-icon-contact-sheet.png')

if __name__ == '__main__':
    main()
