"""Transforma um retrato fotográfico em um pôster no estilo do design gráfico
iraniano experimental (silhueta preta, rosto com ornamentos vermelhos,
roupa com padrão turquesa, fundo creme e tipografia inclinada).

Uso: python3 poster_style.py entrada.jpg saida.png
"""
import sys
import math
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

CREAM = (227, 230, 196)
BLACK = (14, 12, 12)
RED = (232, 72, 44)
TEAL = (38, 160, 176)
SCALE = 2  # trabalha em resolução dobrada para traços nítidos


# --------------------------------------------------------------- padrões
def red_ornament(w, h, cell=150):
    """Linhas vermelhas ornamentais (círculos concêntricos entrelaçados)
    sobre fundo preto, lembrando a caligrafia/arabesco do rosto do pôster."""
    img = Image.new("RGB", (w, h), BLACK)
    d = ImageDraw.Draw(img)
    lw = max(4, cell // 20)
    rows = h // cell + 3
    cols = w // cell + 3
    for r in range(-1, rows):
        for c in range(-1, cols):
            cx = c * cell + (cell // 2 if r % 2 else 0)
            cy = r * int(cell * 0.86)
            # anéis concêntricos
            for k, rad in enumerate((cell * 0.62, cell * 0.44, cell * 0.26)):
                d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad],
                          outline=RED, width=lw)
            # pétalas / arcos interrompidos entre os anéis
            for a in range(0, 360, 45):
                start = a + 8
                d.arc([cx - cell * 0.53, cy - cell * 0.53,
                       cx + cell * 0.53, cy + cell * 0.53],
                      start, start + 22, fill=RED, width=lw + 3)
            # miolo
            rr = cell * 0.08
            d.ellipse([cx - rr, cy - rr, cx + rr, cy + rr], fill=RED)
            # pequenos triângulos nos interstícios
            tx, ty = cx + cell // 2, cy + int(cell * 0.43)
            s = cell * 0.09
            d.polygon([(tx, ty - s), (tx + s, ty + s), (tx - s, ty + s)],
                      fill=RED)
    return np.array(img)


def teal_ornament(w, h, cell=170):
    """Padrão turquesa com motivos pretos (olhos, anéis e faixas em
    zigue-zague), como nos braços do pôster."""
    img = Image.new("RGB", (w, h), TEAL)
    d = ImageDraw.Draw(img)
    lw = max(6, cell // 18)
    band = cell
    rows = h // band + 3
    cols = w // cell + 3
    for r in range(-1, rows):
        y0 = r * band
        # faixa de zigue-zague no topo de cada linha
        pts = []
        step = cell // 4
        for i in range(-2, w // step + 4):
            pts.append((i * step, y0 + (0 if i % 2 else step * 0.6)))
        d.line(pts, fill=BLACK, width=lw)
        for c in range(-1, cols):
            cx = c * cell + (cell // 2 if r % 2 else 0)
            cy = y0 + band * 0.58
            rad = cell * 0.36
            d.ellipse([cx - rad, cy - rad, cx + rad, cy + rad],
                      outline=BLACK, width=lw)
            rad2 = cell * 0.2
            d.ellipse([cx - rad2, cy - rad2, cx + rad2, cy + rad2],
                      outline=BLACK, width=lw)
            rad3 = cell * 0.08
            d.ellipse([cx - rad3, cy - rad3, cx + rad3, cy + rad3],
                      fill=BLACK)
            # "olho" amendoado entre os anéis
            ex = cx + cell // 2
            d.chord([ex - cell * 0.16, cy - cell * 0.09,
                     ex + cell * 0.16, cy + cell * 0.09],
                    0, 360, fill=BLACK)
            # losangos pequenos
            dy = y0 + band * 0.18
            s = cell * 0.06
            d.polygon([(cx, dy - s), (cx + s, dy), (cx, dy + s), (cx - s, dy)],
                      fill=BLACK)
    return np.array(img)


def rotate_pattern(pat, angle):
    h, w = pat.shape[:2]
    m = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    return cv2.warpAffine(pat, m, (w, h), flags=cv2.INTER_NEAREST,
                          borderMode=cv2.BORDER_REFLECT)


# --------------------------------------------------------------- máscaras
def largest_component(mask):
    n, lab, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8))
    if n <= 1:
        return mask
    idx = 1 + np.argmax(stats[1:, cv2.CC_STAT_AREA])
    return lab == idx


def fill_holes(mask):
    m = mask.astype(np.uint8) * 255
    h, w = m.shape
    flood = m.copy()
    ff = np.zeros((h + 2, w + 2), np.uint8)
    cv2.floodFill(flood, ff, (0, 0), 255)
    return (m | cv2.bitwise_not(flood)) > 0


def ellipse_kernel(r):
    return cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))


def main(src, dst):
    bgr = cv2.imread(src)
    bgr = cv2.resize(bgr, None, fx=SCALE, fy=SCALE,
                     interpolation=cv2.INTER_CUBIC)
    H, W = bgr.shape[:2]
    smooth = cv2.bilateralFilter(bgr, 15, 60, 15)
    gray = cv2.cvtColor(smooth, cv2.COLOR_BGR2GRAY).astype(np.float32)
    hsv = cv2.cvtColor(smooth, cv2.COLOR_BGR2HSV)
    ycc = cv2.cvtColor(smooth, cv2.COLOR_BGR2YCrCb)

    # pessoa = tudo que não é o fundo branco
    person = (smooth.min(axis=2) < 225) | (hsv[..., 1] > 40)
    person = cv2.morphologyEx(person.astype(np.uint8), cv2.MORPH_OPEN,
                              ellipse_kernel(3)) > 0
    person = largest_component(person)
    person = fill_holes(cv2.morphologyEx(person.astype(np.uint8),
                                         cv2.MORPH_CLOSE,
                                         ellipse_kernel(9)) > 0)

    # pele (cabeça + pescoço)
    cr, cb = ycc[..., 1], ycc[..., 2]
    skin = (cr > 135) & (cr < 180) & (cb > 80) & (cb < 130) & person
    ys = np.arange(H)[:, None]
    skin &= ys < int(H * 0.47)
    skin = largest_component(skin)
    head = cv2.morphologyEx(skin.astype(np.uint8), cv2.MORPH_CLOSE,
                            ellipse_kernel(25)) > 0
    head = fill_holes(head) & person

    # pescoço/colarinho: corta a cabeça na altura do colarinho
    body = person & ~head

    # tronco: paletó escuro x camisa/gravata
    gb = gray.copy()
    jacket_dark = gb < 70
    jacket = cv2.morphologyEx((jacket_dark & body).astype(np.uint8),
                              cv2.MORPH_OPEN, ellipse_kernel(6))
    jacket = cv2.morphologyEx(jacket, cv2.MORPH_CLOSE, ellipse_kernel(10)) > 0
    jacket &= body
    tie = body & (hsv[..., 1] > 35) & (gb > 110)
    tie = cv2.morphologyEx(tie.astype(np.uint8), cv2.MORPH_CLOSE,
                           ellipse_kernel(12)) > 0
    tie = largest_component(tie & body)
    tie = fill_holes(tie)

    # ------------------------------------------------------- composição
    out = np.zeros((H, W, 3), np.uint8)
    out[:] = CREAM

    # silhueta inteira começa preta
    out[person] = BLACK

    # paletó: padrão turquesa nas áreas mais iluminadas, preto nas sombras
    teal = rotate_pattern(teal_ornament(W, H), -12)
    jvals = gb[jacket]
    thr_j = np.percentile(jvals, 45) if jvals.size else 0
    light_jacket = jacket & (gb > thr_j)
    light_jacket = cv2.morphologyEx(light_jacket.astype(np.uint8),
                                    cv2.MORPH_OPEN, ellipse_kernel(8))
    light_jacket = cv2.morphologyEx(light_jacket, cv2.MORPH_CLOSE,
                                    ellipse_kernel(14)) > 0
    out[light_jacket] = teal[light_jacket]

    # gravata: ornamento vermelho
    red_tie = rotate_pattern(red_ornament(W, H, cell=90), 35)
    out[tie] = red_tie[tie]

    # rosto: preto nas sombras, ornamento vermelho nas luzes,
    # creme nos brilhos mais fortes
    clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8, 8))
    fgray = clahe.apply(gray.astype(np.uint8)).astype(np.float32)
    fgray = cv2.GaussianBlur(fgray, (0, 0), 3)
    hv = fgray[head]
    t_dark = np.percentile(hv, 42)
    t_high = np.percentile(hv, 99.3)
    gray = fgray
    face_light = head & (gray > t_dark)
    face_light = cv2.morphologyEx(face_light.astype(np.uint8),
                                  cv2.MORPH_OPEN, ellipse_kernel(4)) > 0
    face_high = head & (gray > t_high)
    face_high = cv2.morphologyEx(face_high.astype(np.uint8),
                                 cv2.MORPH_OPEN, ellipse_kernel(3)) > 0
    red_face = rotate_pattern(red_ornament(W, H, cell=95), 18)
    out[face_light] = red_face[face_light]
    out[face_high] = CREAM

    # contorno creme separando a cabeça do corpo (como a máscara do pôster)
    ring = cv2.dilate(head.astype(np.uint8), ellipse_kernel(9)) > 0
    ring &= ~head & person
    out[ring] = CREAM

    # contorno preto nítido em volta da cabeça
    halo = cv2.dilate(head.astype(np.uint8), ellipse_kernel(16)) > 0
    halo &= ~cv2.dilate(head.astype(np.uint8), ellipse_kernel(9)).astype(bool)
    halo &= ~person
    out[halo] = BLACK

    # ------------------------------------------------------- textura de papel
    rng = np.random.default_rng(7)
    noise = rng.normal(0, 5, (H, W, 1))
    out = np.clip(out.astype(np.float32) + noise, 0, 255).astype(np.uint8)

    poster = Image.fromarray(out)

    # ------------------------------------------------------- tipografia
    add_type(poster)

    poster.save(dst, quality=95)
    print("ok", dst, poster.size)


def condensed_text(text, font, fill, squeeze=0.72):
    bbox = font.getbbox(text)
    w, h = bbox[2] - bbox[0] + 4, bbox[3] - bbox[1] + 8
    layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(layer).text((-bbox[0] + 2, -bbox[1] + 4), text,
                               font=font, fill=fill)
    return layer.resize((max(1, int(w * squeeze)), h), Image.LANCZOS)


def add_type(poster):
    W, H = poster.size
    inter = "/usr/share/fonts/opentype/inter/Inter-ExtraBold.otf"
    block = Image.new("RGBA", (900, 440), (0, 0, 0, 0))
    y = 0
    big = condensed_text("RETRATO", ImageFont.truetype(inter, 140), RED)
    block.alpha_composite(big, (0, y))
    y += big.size[1] + 6
    for line, size, col in (("retrato em vermelho e turquesa", 50, BLACK),
                            ("pôster experimental — 2026", 44, BLACK),
                            ("RETRATO  RETRATO  RETRATO", 40, RED)):
        t = condensed_text(line, ImageFont.truetype(inter, size), col)
        block.alpha_composite(t, (6, y))
        y += t.size[1] + 8
    # barra vertical tipo colchete
    d = ImageDraw.Draw(block)
    d.rectangle([big.size[0] + 18, 20, big.size[0] + 34, big.size[1] + 10],
                fill=BLACK)
    rotated = block.rotate(-28, expand=True, resample=Image.BICUBIC)
    rotated = rotated.crop(rotated.getbbox())
    poster.paste(rotated, (W - rotated.size[0] - 50, 60), rotated)

    # faixa de crédito no rodapé, como no pôster original
    d = ImageDraw.Draw(poster)
    bar_h = 56
    d.rectangle([W * 0.44, H - bar_h - 26, W - 30, H - 26], fill=BLACK)
    credit = condensed_text("RETRATO \\ experimental poster \\ 2026",
                            ImageFont.truetype(inter, 40), CREAM, 0.8)
    poster.paste(credit, (int(W * 0.46), H - bar_h - 20), credit)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
