"""Regenerates the GIF decoder fixtures: python3 make_gifs.py (needs Pillow).

Each case writes <name>.gif with Pillow (which crops frames to changed rectangles and picks
disposal methods itself) plus <name>.argb.gz: the exact frames that went in, as big-endian
0xAARRGGBB ints, frame after frame (gzipped). GifTest decodes the GIF and expects those frames back.
"""
import gzip
import random
import struct
from PIL import Image

HERE = __import__('os').path.dirname(__file__)


def write(name, frames, durations, **kwargs):
    frames[0].save(f'{HERE}/{name}.gif', save_all=True, append_images=frames[1:],
                   duration=durations, loop=0, **kwargs)
    with gzip.open(f'{HERE}/{name}.argb.gz', 'wb') as out:
        for f in frames:
            raw = f.convert('RGBA').tobytes()
            for i in range(0, len(raw), 4):
                r, g, b, a = raw[i:i + 4]
                out.write(struct.pack('>I', 0 if a == 0 else (0xFF << 24) | (r << 16) | (g << 8) | b))


# A red square with a blue dot sliding over transparency: 20 frames (past a checkpoint),
# disposal to background, uneven delays.
moving = []
for i in range(20):
    f = Image.new('RGBA', (40, 30), (0, 0, 0, 0))
    for y in range(8, 20):
        for x in range(i, i + 12):
            f.putpixel((x, y), (230, 40, 40, 255))
    f.putpixel((i + 5, 12), (20, 60, 220, 255))
    moving.append(f)
write('moving', moving, [30 + 10 * (i % 4) for i in range(20)], disposal=2)

# Opaque noise and long runs on a 256-colour palette: exercises the LZW table filling up
# (codes reach 12 bits) and repeated-run codes. Later frames change one band only.
random.seed(7)
palette = [random.randrange(256) for _ in range(256 * 3)]
base = [random.randrange(256) if (y // 6) % 2 else (y * 7) % 256 for y in range(90) for _ in range(120)]
noisy = []
for i in range(5):
    data = list(base)
    for y in range(30 + i * 10, 40 + i * 10):
        for x in range(120):
            data[y * 120 + x] = (x + i * 31) % 256
    f = Image.new('P', (120, 90))
    f.putpalette(palette)
    f.putdata(data)
    noisy.append(f)
write('noisy', noisy, [80] * 5, optimize=False)
