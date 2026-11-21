"""Retrato minimalista no estilo dos pôsteres de cantores brasileiros:
fundo de cor chapada, rosto "invisível" (mesma cor do fundo) e apenas os
traços marcantes desenhados — cabelo, sobrancelhas e barba — com uma frase
em tipografia serifada embaixo.

Os traços foram vetorizados à mão a partir da foto (coordenadas medidas no
recorte da cabeça, ampliado 1,6x).

Uso: python3 poster_minimalista.py saida.png
"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

W, H = 1600, 2400
SS = 2                      # supersampling para bordas suaves
BG = (46, 170, 184)         # turquesa
INK = (52, 33, 24)          # marrom-escuro dos traços
TEXT = (52, 33, 24)
K = 1.75                    # escala do desenho do rosto
OX, OY = 800 - 300 * K, 330 - 60 * K


def spline(pts, closed=True, n=16):
    """Catmull-Rom: transforma poucos pontos em uma curva suave."""
    p = np.array(pts, float)
    if closed:
        p = np.vstack([p[-1], p, p[0], p[1]])
    else:
        p = np.vstack([p[0], p, p[-1]])
    out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
        for t in np.linspace(0, 1, n, endpoint=False):
            t2, t3 = t * t, t * t * t
            out.append(0.5 * ((2 * p1) + (-p0 + p2) * t
                              + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                              + (-p0 + 3 * p1 - 3 * p2 + p3) * t3))
    if not closed:
        out.append(p[-2])
    return [tuple(((x * K + OX) * SS, (y * K + OY) * SS)) for x, y in out]


def stipple(img, poly, density, tone, rng, rmin=2, rmax=4):
    """Preenche a forma com uma leve camada de cor e pontinhos aleatórios,
    imitando cabelo raspado / barba por fazer."""
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).polygon(poly, fill=255)
    m = np.asarray(mask) > 0
    # camada de tom transparente
    arr = np.asarray(img).astype(np.float32)
    ink = np.array(INK, np.float32)
    arr[m] = arr[m] * (1 - tone) + ink * tone
    out = Image.fromarray(arr.astype(np.uint8))
    d = ImageDraw.Draw(out)
    ys, xs = np.nonzero(m)
    n = int(len(xs) * density)
    idx = rng.choice(len(xs), n, replace=False)
    for x, y in zip(xs[idx], ys[idx]):
        r = rng.uniform(rmin, rmax)
        d.ellipse([x - r, y - r, x + r, y + r], fill=INK)
    return out


def main(dst):
    img = Image.new("RGB", (W * SS, H * SS), BG)
    rng = np.random.default_rng(11)

    # orelhas em um tom um pouco mais escuro do fundo, para dar forma à cabeça
    d = ImageDraw.Draw(img)
    ear_tone = tuple(int(c * 0.86) for c in BG)
    ear_l = [(100, 370), (78, 365), (68, 395), (78, 460), (98, 525),
             (112, 535), (110, 450)]
    ear_r = [(600 - x + 4, y) for x, y in ear_l]
    d.polygon(spline(ear_l), fill=ear_tone)
    d.polygon(spline(ear_r), fill=ear_tone)

    # cabelo raspado e ralo: faixa fina nas laterais, sumindo antes do topo
    hair_left = [(104, 385), (104, 300), (114, 230), (138, 170), (170, 128),
                 (178, 138), (152, 185), (132, 245), (124, 310), (122, 380)]
    hair_right = [(600 - x, y) for x, y in hair_left]
    for poly in (hair_left, hair_right):
        img = stipple(img, spline(poly), 0.010, 0.30, rng)

    # barba por fazer: rente à mandíbula, com bigode e cavanhaque ralos
    beard = [
        (110, 470), (114, 545), (132, 615), (168, 678), (218, 724),
        (272, 746), (330, 746), (392, 724), (444, 676), (476, 612),
        (490, 545), (490, 470),
        (472, 490), (458, 560), (430, 620), (392, 640), (360, 615),
        (300, 612), (240, 615), (208, 640), (170, 620), (142, 560),
        (126, 490),
    ]
    img = stipple(img, spline(beard), 0.012, 0.28, rng)
    mustache = [(205, 572), (250, 552), (300, 548), (350, 552), (395, 572),
                (350, 566), (300, 563), (250, 566)]
    img = stipple(img, spline(mustache), 0.016, 0.32, rng)

    # sobrancelhas finas (traço sólido)
    d = ImageDraw.Draw(img)
    brow_l = [(152, 352), (188, 338), (232, 333), (262, 338), (258, 345),
              (225, 341), (188, 346), (156, 358)]
    brow_r = [(322, 338), (356, 330), (400, 332), (440, 344), (436, 350),
              (398, 340), (358, 339), (326, 345)]
    d.polygon(spline(brow_l), fill=INK)
    d.polygon(spline(brow_r), fill=INK)

    # boca: um traço fino
    d.line(spline([(232, 586), (300, 582), (368, 587)], closed=False),
           fill=INK, width=7 * SS)

    # suaviza levemente as bordas e volta para o tamanho final
    img = img.resize((W, H), Image.LANCZOS)

    # textura de papel
    arr = np.asarray(img).astype(np.float32)
    rng = np.random.default_rng(3)
    arr += rng.normal(0, 4, (H, W, 1))
    vign = np.linspace(-1, 1, W)[None, :] ** 2 + np.linspace(-1, 1, H)[:, None] ** 2
    arr *= (1 - 0.06 * vign)[..., None]
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))

    # tipografia
    d = ImageDraw.Draw(img)
    serif_b = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"
    serif_i = "/usr/share/fonts/truetype/liberation/LiberationSerif-BoldItalic.ttf"
    sans = "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"

    def center(text, font, y, fill=TEXT):
        w = d.textlength(text, font=font)
        d.text(((W - w) / 2, y), text, font=font, fill=fill)

    center("Tudo vale a pena", ImageFont.truetype(serif_b, 108), 1780)
    center("se a alma não é pequena", ImageFont.truetype(serif_i, 78), 1915)
    d.line([(W * 0.22, 2060), (W * 0.78, 2060)], fill=TEXT, width=3)
    center("RETRATO  ·  série minimalista  ·  2026",
           ImageFont.truetype(sans, 30), 2085)

    # moldura creme, como um cartaz impresso
    framed = Image.new("RGB", (W + 80, H + 80), (236, 228, 208))
    framed.paste(img, (40, 40))
    framed.save(dst)
    print("ok", dst, framed.size)


if __name__ == "__main__":
    main(sys.argv[1])
