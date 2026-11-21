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


def main(dst):
    img = Image.new("RGB", (W * SS, H * SS), BG)
    d = ImageDraw.Draw(img)

    # cabelo raspado: "ferradura" grossa nas laterais, sumindo no topo careca
    hair_left = [(98, 380), (100, 300), (112, 225), (140, 160), (180, 112),
                 (225, 82), (240, 92), (200, 130), (165, 185), (142, 250),
                 (132, 320), (130, 385)]
    hair_right = [(600 - x, y)
                  for x, y in hair_left]
    d.polygon(spline(hair_left), fill=INK)
    d.polygon(spline(hair_right), fill=INK)

    # sobrancelhas
    brow_l = [(150, 352), (185, 336), (232, 330), (262, 336), (258, 346),
              (225, 343), (185, 348), (155, 360)]
    brow_r = [(322, 338), (355, 328), (400, 330), (440, 344), (436, 352),
              (398, 342), (358, 341), (326, 348)]
    d.polygon(spline(brow_l), fill=INK)
    d.polygon(spline(brow_r), fill=INK)

    # barba: contorno externo da mandíbula, borda interna nas bochechas
    beard = [
        (106, 455), (110, 540), (128, 615), (165, 680), (215, 728),
        (270, 752), (330, 752), (395, 728), (448, 678), (480, 610),
        (494, 535), (494, 455),
        # volta pelas bochechas (borda interna)
        (478, 470), (462, 530), (436, 585), (398, 565), (350, 545),
        (300, 535), (250, 540), (205, 561), (166, 585), (140, 530),
        (122, 470),
    ]
    d.polygon(spline(beard), fill=INK)

    # boca: fenda na cor do fundo entre bigode e cavanhaque
    mouth = [(228, 584), (265, 578), (300, 580), (335, 578), (366, 585),
             (335, 591), (300, 593), (265, 591)]
    d.polygon(spline(mouth), fill=BG)

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
