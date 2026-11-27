"""Read-only coastline registration analysis. Writes JSON/manifest only; never edits image pixels."""
import argparse
import json
from pathlib import Path
import cv2
import numpy as np
from PIL import Image


def analyze(base_path, art_path):
    base = np.asarray(Image.open(base_path).convert("RGBA"))
    h, w = base.shape[:2]
    if h != w:
        raise ValueError("Expected the square exported survey canvas")
    art = np.asarray(Image.open(art_path).convert("RGBA"))
    # Resampling is for numeric measurement in memory, not a deliverable edited image.
    art = cv2.resize(art, (w, h), interpolation=cv2.INTER_AREA)
    base_water = ((base[:, :, 0] < 140) & (base[:, :, 1] < 160) & (base[:, :, 3] > 128)).astype(np.float32)
    # Teal water has more blue than green; dark sage tree canopies must not count as water.
    rgb=art[:,:,:3].astype(np.int16)
    art_water = ((rgb[:, :, 0] < 145) & (rgb[:, :, 1] < 175) & (rgb[:,:,2]-rgb[:,:,0]>12)
                 & (rgb[:,:,2]-rgb[:,:,1]>2) & (art[:, :, 3] > 128)).astype(np.float32)
    valid = cv2.erode((base[:, :, 3] > 128).astype(np.uint8), np.ones((31, 31), np.uint8)) > 0
    template = cv2.GaussianBlur(base_water, (0, 0), 3)
    source = cv2.GaussianBlur(art_water, (0, 0), 3)
    score, inverse = cv2.findTransformECC(template, source, np.eye(2, 3, dtype=np.float32), cv2.MOTION_AFFINE,
                                         (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 150, 1e-6), valid.astype(np.uint8)*255, 5)
    forward = cv2.invertAffineTransform(inverse)
    a, b, tx, c, d, tz = [float(v) for v in forward.ravel()]
    matrix = [a, b, (tx+.5-.5*a-.5*b)/w, c, d, (tz+.5-.5*c-.5*d)/h]
    if not (.5 <= a <= 2 and .5 <= d <= 2 and abs(b) <= .25 and abs(c) <= .25
            and abs(matrix[2]) <= .25 and abs(matrix[5]) <= .25 and a*d-b*c >= .25):
        raise ValueError("Estimated registration exceeds the mod's bounded transform")

    def overlap(water):
        left, right = base_water > .5, water > .5
        return float(np.count_nonzero(left & right & valid) / max(1, np.count_nonzero((left | right) & valid)))

    warped = cv2.warpAffine(art_water, forward, (w, h), flags=cv2.INTER_NEAREST)
    before, after = overlap(art_water), overlap(warped)
    return {"base": str(base_path), "art": str(art_path), "canvas": [w, h], "ecc": float(score),
            "waterIoUBefore": before, "waterIoUAfter": after, "registration": matrix,
            "accepted": bool(score >= .7 and after >= before+.015),
            "note": "Global affine estimate from actual water masks; local AI deformation and exact landmark positions still require visual review. No PNG pixels written."}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("base", type=Path);p.add_argument("art", type=Path)
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--manifest", type=Path);p.add_argument("--output-manifest", type=Path);p.add_argument("--name")
    args = p.parse_args()
    report = analyze(args.base, args.art)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.output_manifest:
        if not args.manifest or not report["accepted"]:
            raise ValueError("A matching original manifest and improved registration are required")
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        manifest["registration"] = report["registration"]
        if args.name:manifest["variantName"] = args.name
        args.output_manifest.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True))


if __name__ == "__main__":
    main()
