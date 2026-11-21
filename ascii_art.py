"""Converte uma foto em arte ASCII (texto .txt e imagem .png).

Uso: python3 ascii_art.py foto.jpg pasta_saida [colunas]
"""
import sys
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont, ImageOps

RAMP = " .'`^,:;Il!i><~+_-?][}{1)(|/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"


def person_mask(img, box, full):
    """Máscara da pessoa via GrabCut, iniciada com a posição aproximada da
    cabeça (elipse) e dos ombros, em coordenadas relativas à foto inteira."""
    x0, y0, x1, y1 = box
    W, H = full
    sc = 700 / img.shape[1]
    sm = cv2.resize(img, None, fx=sc, fy=sc, interpolation=cv2.INTER_AREA)
    h, w = sm.shape[:2]

    def to_sm(fx, fy):
        return int((fx * W - x0) * sc), int((fy * H - y0) * sc)

    m = np.full((h, w), cv2.GC_PR_BGD, np.uint8)
    cx, cy = to_sm(0.49, 0.47)
    ax, ay = int(0.25 * W * sc), int(0.21 * H * sc)
    cv2.ellipse(m, (cx, cy), (ax, ay), 0, 0, 360, cv2.GC_PR_FGD, -1)
    cv2.ellipse(m, (cx, cy), (int(ax * .6), int(ay * .6)), 0, 0, 360,
                cv2.GC_FGD, -1)
    sx, sy = to_sm(0.0, 0.70)
    ex, _ = to_sm(0.80, 0)
    m[sy:, :ex] = cv2.GC_PR_FGD
    m[to_sm(0, 0.80)[1]:, :to_sm(0.65, 0)[0]] = cv2.GC_FGD
    m[:to_sm(0, 0.27)[1], :] = cv2.GC_BGD
    m[:to_sm(0, 0.5)[1], to_sm(0.86, 0)[0]:] = cv2.GC_BGD
    m[:to_sm(0, 0.38)[1], :to_sm(0.18, 0)[0]] = cv2.GC_BGD
    bgd, fgd = np.zeros((1, 65)), np.zeros((1, 65))
    cv2.grabCut(sm, m, None, bgd, fgd, 6, cv2.GC_INIT_WITH_MASK)
    fg = ((m == cv2.GC_FGD) | (m == cv2.GC_PR_FGD)).astype(np.uint8)
    fg = cv2.resize(fg, (img.shape[1], img.shape[0]),
                    interpolation=cv2.INTER_NEAREST)
    k = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (15, 15))
    fg = cv2.morphologyEx(fg, cv2.MORPH_CLOSE, k)
    return fg > 0


def to_ascii(path, cols, crop=(0.08, 0.24, 0.94, 0.88)):
    pil = ImageOps.exif_transpose(Image.open(path)).convert("RGB")
    img = cv2.cvtColor(np.array(pil), cv2.COLOR_RGB2BGR)
    h, w = img.shape[:2]
    x0, y0, x1, y1 = [int(v) for v in (crop[0] * w, crop[1] * h,
                                       crop[2] * w, crop[3] * h)]
    img = cv2.bilateralFilter(img[y0:y1, x0:x1], 9, 40, 9)
    g = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY).astype(np.float32)
    sat = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)[..., 1].astype(np.float32)

    # separa a pessoa da parede com GrabCut (pele e parede têm brilho parecido)
    fg = person_mask(img, (x0, y0, x1, y1), (w, h))
    fgf = cv2.GaussianBlur(fg.astype(np.float32), (0, 0), 4)

    # tons da pessoa normalizados só dentro dela
    gc = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(6, 6)).apply(
        g.astype(np.uint8)).astype(np.float32)
    lo, hi = np.percentile(gc[fg], 2), np.percentile(gc[fg], 97)
    t = np.clip((gc - lo) / (hi - lo), 0, 1)
    dark = (1 - t) ** 1.5 * 0.92 + 0.04     # pele clara fica leve
    # reforça contornos (óculos, olhos, boca)
    edges = cv2.Canny(cv2.GaussianBlur(g, (0, 0), 2).astype(np.uint8), 20, 60)
    edges = cv2.GaussianBlur(edges.astype(np.float32) / 255, (0, 0), 3)
    dark = np.clip(dark + edges * 1.2, 0, 1) * fgf

    rows = int(cols * dark.shape[0] / dark.shape[1] * 0.5)
    small = cv2.resize(dark, (cols, rows), interpolation=cv2.INTER_AREA)
    idx = (small * (len(RAMP) - 1)).round().astype(int)
    return ["".join(RAMP[i] for i in row).rstrip() for row in idx]


def render(lines, out, size=14, fg=(30, 30, 30), bg=(245, 242, 232)):
    font = ImageFont.truetype(FONT, size)
    cw = font.getlength("M")
    lh = int(size * 1.18)
    w = int(cw * max(len(l) for l in lines)) + 80
    h = lh * len(lines) + 80
    im = Image.new("RGB", (w, h), bg)
    d = ImageDraw.Draw(im)
    for i, line in enumerate(lines):
        d.text((40, 40 + i * lh), line, font=font, fill=fg)
    im.save(out)


if __name__ == "__main__":
    src, outdir = sys.argv[1], sys.argv[2]
    cols = int(sys.argv[3]) if len(sys.argv) > 3 else 100
    small = to_ascii(src, cols)
    open(f"{outdir}/retrato_ascii.txt", "w").write("\n".join(small) + "\n")
    big = to_ascii(src, 220)
    render(big, f"{outdir}/retrato_ascii.png", size=12)
    print("ok", len(small), "linhas")
