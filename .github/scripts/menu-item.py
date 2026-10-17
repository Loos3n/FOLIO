"""Finds the text-selection menu in a screenshot and prints the centre of item N.

The floating menu is its own popup window, which `uiautomator dump` doesn't include, so
the check locates it by pixels: a white bar (the page behind it is off-white) holding
dark text, whose items are separated by wide gaps.
"""
import sys
from PIL import Image

img = Image.open(sys.argv[1]).convert('RGB')
n = int(sys.argv[2])
w, h = img.size
px = img.load()

def white(x, y):
    r, g, b = px[x, y]
    return r == 255 and g == 255 and b == 255

# Rows that are mostly pure white across a stretch: the menu bar.
rows = [y for y in range(h) if sum(1 for x in range(0, w, 4) if white(x, y)) > w // 4 // 5]
if not rows:
    sys.exit(1)
# Take the first contiguous band.
band = [rows[0]]
for y in rows[1:]:
    if y != band[-1] + 1:
        break
    band.append(y)
top, bottom = band[0], band[-1]
mid = (top + bottom) // 2
xs = [x for x in range(w) if white(x, mid) or sum(px[x, mid]) < 400]
left, right = min(xs), max(xs)
# Columns holding dark (text) pixels anywhere in the band.
dark = [any(sum(px[x, y]) < 300 for y in range(top, bottom + 1)) for x in range(left, right + 1)]
items, start, gap = [], None, 0
for i, d in enumerate(dark + [False] * 40):
    if d:
        if start is None:
            start = i
        gap = 0
        end = i
    elif start is not None:
        gap += 1
        if gap > 25:  # wider than a space between letters
            items.append((start, end))
            start = None
if n >= len(items):
    sys.exit(1)
a, b = items[n]
print(left + (a + b) // 2, mid)
