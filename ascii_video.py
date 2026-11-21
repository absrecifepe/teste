"""Vídeo do retrato ASCII: o texto é "digitado" com a câmera bem perto do
cursor e, no fim, um zoom reverso revela a imagem inteira.

Uso: python3 ascii_video.py foto.jpg saida.mp4
"""
import sys
import wave
import subprocess
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

from ascii_art import to_ascii

OUT_W, OUT_H, FPS = 1080, 1920, 30
COLS = 220          # mesmo detalhe de ascii/retrato_ascii.png
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"
FSIZE = 28
BG = (245, 242, 232)   # creme, como a imagem ASCII
FG = (30, 30, 30)
CURSOR = (40, 40, 40)

T_TYPE = 10.0       # duração da digitação
T_HOLD1 = 0.6      # pausa antes do zoom
T_ZOOM = 3.6       # zoom reverso
T_HOLD2 = 2.8      # imagem final


def ease(u):
    return 4 * u ** 3 if u < 0.5 else 1 - (-2 * u + 2) ** 3 / 2


def main(src, dst):
    lines = to_ascii(src, COLS)
    font = ImageFont.truetype(FONT, FSIZE)
    cw = font.getlength("M")
    lh = int(FSIZE * 1.18)
    margin = 1800
    CW = int(cw * COLS)
    CH = lh * len(lines)
    canvas = Image.new("RGB", (CW + 2 * margin, CH + 2 * margin), BG)
    draw = ImageDraw.Draw(canvas)

    # ordem de digitação: só caracteres visíveis (espaços são "pulados")
    seq = [(r, c, ch) for r, line in enumerate(lines)
           for c, ch in enumerate(line) if ch != " "]
    N = len(seq)

    def pos(r, c):
        return margin + c * cw, margin + r * lh

    s_zoom = OUT_W / (cw * 24)                       # ~24 caracteres na tela
    s_full = min(OUT_W / CW, OUT_H / CH) * 0.90
    full_c = (margin + CW / 2, margin + CH / 2)

    total = T_TYPE + T_HOLD1 + T_ZOOM + T_HOLD2
    nframes = int(total * FPS)
    k = 7.0

    ff = subprocess.Popen(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo",
         "-pix_fmt", "rgb24", "-s", f"{OUT_W}x{OUT_H}", "-r", str(FPS),
         "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18",
         "-preset", "medium", dst + ".noaudio.mp4"], stdin=subprocess.PIPE)

    typed = 0
    cam = None
    clicks = []          # (tempo, intensidade) para o som
    rng = np.random.default_rng(1)
    zoom_start = None

    for f in range(nframes):
        t = f / FPS
        # quantos caracteres já foram digitados (acelera exponencialmente)
        u = min(1.0, t / T_TYPE)
        target = int(round(N * (np.exp(k * u) - 1) / (np.exp(k) - 1)))
        target = min(N, max(target, min(N, int(t * 6))))
        new = target - typed
        for i in range(typed, target):
            r, c, ch = seq[i]
            draw.text(pos(r, c), ch, font=font, fill=FG)
        for j in range(min(new, 4)):
            clicks.append((t + rng.uniform(0, 1 / FPS), 1.0 if new < 3 else 0.5))
        typed = target

        # posição do cursor
        if typed < N:
            r, c, _ = seq[typed]
        else:
            r, c = seq[-1][0], seq[-1][1] + 1
        cx, cy = pos(r, c)
        cy += lh / 2

        if t < T_TYPE + T_HOLD1:
            speed = new
            follow = 1 / (1 + speed / 8)          # quanto mais rápido, menos segue no x
            tx = cx * follow + full_c[0] * (1 - follow)
            if cam is None:
                cam = [cx, cy]
            a = 0.25
            cam[0] += (tx - cam[0]) * a
            cam[1] += (cy - cam[1]) * a
            s = s_zoom
            ccx, ccy = cam
        else:
            if zoom_start is None:
                zoom_start = (cam[0], cam[1])
            uz = min(1.0, (t - T_TYPE - T_HOLD1) / T_ZOOM)
            e = ease(uz)
            s = float(np.exp(np.log(s_zoom) * (1 - e) + np.log(s_full) * e))
            ccx = zoom_start[0] * (1 - e) + full_c[0] * e
            ccy = zoom_start[1] * (1 - e) + full_c[1] * e

        # recorta a janela da câmera e redimensiona
        vw, vh = OUT_W / s, OUT_H / s
        x0, y0 = int(round(ccx - vw / 2)), int(round(ccy - vh / 2))
        box = (x0, y0, x0 + int(round(vw)), y0 + int(round(vh)))
        crop = np.asarray(canvas.crop(box))
        interp = cv2.INTER_AREA if s < 1 else cv2.INTER_LINEAR
        frame = cv2.resize(crop, (OUT_W, OUT_H), interpolation=interp)

        # cursor piscando
        blink = typed < N or int(t * 2) % 2 == 0
        if blink:
            px = int((cx - x0) * s)
            py = int((cy - lh / 2 - y0) * s)
            cv2.rectangle(frame, (px, py), (px + int(cw * s), py + int(lh * s * 0.9)),
                          CURSOR, -1)
        ff.stdin.write(frame.astype(np.uint8).tobytes())

    ff.stdin.close()
    ff.wait()

    # som de teclado sintetizado
    sr = 44100
    audio = np.zeros(int(total * sr) + sr, np.float32)
    n = int(0.018 * sr)
    env = np.exp(-np.linspace(0, 9, n))
    for tc, amp in clicks:
        i = int(tc * sr)
        burst = rng.normal(0, 1, n) * env
        burst = np.convolve(burst, [1, -0.9], "same")      # clique mais seco
        audio[i:i + n] += burst[: len(audio) - i] * amp * 0.25
    # "whoosh" suave no zoom
    z0 = int((T_TYPE + T_HOLD1) * sr)
    zn = int(T_ZOOM * sr)
    noise = rng.normal(0, 1, zn).astype(np.float32)
    noise = np.convolve(noise, np.ones(200) / 200, "same")
    audio[z0:z0 + zn] += noise * np.sin(np.linspace(0, np.pi, zn)) * 0.6
    audio = np.clip(audio / max(1e-6, np.abs(audio).max()) * 0.8, -1, 1)
    wav = dst + ".wav"
    with wave.open(wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes((audio * 32767).astype(np.int16).tobytes())

    subprocess.run(["ffmpeg", "-y", "-loglevel", "error",
                    "-i", dst + ".noaudio.mp4", "-i", wav,
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
                    "-shortest", dst], check=True)
    print("ok", dst, nframes, "frames,", N, "caracteres")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
