#!/usr/bin/env python3
"""Crop each UI screenshot to its actual content (bounding box of non-background
pixels) with a small margin, so the UI text fills the figure. Run after
screenshot.js:  python3 crop_screenshots.py
"""
from PIL import Image
import glob, os
from collections import Counter

HERE = os.path.dirname(os.path.abspath(__file__))
IMG = os.path.join(HERE, "..", "images")
PAD = 22
TOL = 16

def bg_of(im):
    w, h = im.size
    pts = [(2, 2), (w - 3, 2), (2, h - 3), (w - 3, h - 3)]
    return Counter(im.getpixel(p) for p in pts).most_common(1)[0][0]

def near(a, b):
    return all(abs(x - y) <= TOL for x, y in zip(a[:3], b[:3]))

def content_bbox(im, bg):
    w, h = im.size
    px = im.load()
    step = 3
    minx, miny, maxx, maxy = w, h, 0, 0
    for y in range(0, h, step):
        for x in range(0, w, step):
            if not near(px[x, y], bg):
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
    if maxx <= minx or maxy <= miny:
        return None
    return (max(0, minx - PAD), max(0, miny - PAD), min(w, maxx + PAD), min(h, maxy + PAD))

for f in sorted(glob.glob(os.path.join(IMG, "ui_*.png"))):
    im = Image.open(f).convert("RGB")
    bg = bg_of(im)
    box = content_bbox(im, bg)
    if box:
        im.crop(box).save(f)
        print(f"{os.path.basename(f)}: {im.size} -> {(box[2]-box[0], box[3]-box[1])}")
    else:
        print(f"{os.path.basename(f)}: no content detected, left as is")
