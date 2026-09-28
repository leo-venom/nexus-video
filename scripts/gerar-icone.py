#!/usr/bin/env python3
"""
Ícone do NEXUS MUSIC 2 no tema do background atual.

Composição pensada para LER EM 48px (testado reduzindo):
  - malha diamante bem discreta (tema do fundo do app)
  - monograma N grande em cromo (gradiente metálico, traço grosso)
  - aro metálico fino (mesmo aro dos cards do app)

Sem texto pequeno nem detalhes que somem em tamanho reduzido.
Gera todos os mipmaps em PNG RGBA. Quadrado (o launcher aplica a máscara).
"""
from PIL import Image, ImageDraw, ImageFilter, ImageFont

FUNDO = "app/src/main/assets/img/fundo.jpg"
FONTE = "app/src/main/assets/fonts/Orbitron-700.woff2"
SAIDA = "app/src/main/res/mipmap-{}/ic_launcher.png"
TAMANHOS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BASE = 512


def malha():
    """Malha diamante do fundo do app, bem escurecida (fundo do ícone)."""
    im = Image.open(FUNDO).convert("RGB")
    w, h = im.size
    im = im.crop((0, int(h * 0.30), w, int(h * 0.72))).resize((BASE, BASE),
                                                             Image.LANCZOS)
    im = Image.blend(im, Image.new("RGB", (BASE, BASE), (6, 6, 9)), 0.62)
    return im.convert("RGBA")


def gradiente_cromo(tam):
    """Gradiente metálico na diagonal: escuro -> claro -> escuro."""
    g = Image.new("L", (tam, tam), 0)
    px = g.load()
    for y in range(tam):
        for x in range(tam):
            t = (x + y) / (2.0 * tam)
            # pico de luz no terço superior esquerdo
            v = 40 + 215 * max(0.0, 1.0 - abs(t - 0.32) * 2.1)
            v = max(30, min(255, v))
            px[x, y] = int(v)
    g = g.filter(ImageFilter.GaussianBlur(tam * 0.012))
    return g


def monograma():
    """Letra N grande com gradiente cromado + contorno claro."""
    tam = int(BASE * 0.60)
    m = Image.new("L", (tam, tam), 0)
    d = ImageDraw.Draw(m)
    try:
        f = ImageFont.truetype(FONTE, int(tam * 0.98))
    except Exception:
        f = ImageFont.truetype("DejaVuSans-Bold.ttf", int(tam * 0.98))
    d.text((tam / 2, tam / 2), "N", font=f, fill=255, anchor="mm")

    # engrossa o traço para aguentar 48px
    m = m.filter(ImageFilter.MaxFilter(5))

    # preenche com o gradiente cromado, recortado pela letra
    cromo = gradiente_cromo(tam).convert("RGB")
    letra = Image.new("RGBA", (tam, tam), (0, 0, 0, 0))
    letra.paste(cromo, (0, 0), m)

    # contorno branco fino para separar do fundo
    borda = m.filter(ImageFilter.MaxFilter(3))
    cont = Image.new("RGBA", (tam, tam), (0, 0, 0, 0))
    cont.paste(Image.new("RGB", (tam, tam), (245, 247, 250)), (0, 0), borda)
    cont.paste(letra, (0, 0), letra)

    # brilho interno suave
    halo = cont.filter(ImageFilter.GaussianBlur(tam * 0.10))
    return halo, cont


def aro(img):
    """Anel metálico fino, ecoando o aro dos cards/botões do app."""
    d = ImageDraw.Draw(img, "RGBA")
    c = BASE / 2
    R = BASE * 0.455
    e = max(1, int(BASE * 0.013))
    d.ellipse((c - R, c - R, c + R, c + R), outline=(228, 231, 236, 205), width=e)
    d.ellipse((c - R, c - R, c + R, c + R), outline=(255, 255, 255, 90),
              width=max(1, e // 2))
    return img


def montar():
    base = malha()
    halo, letra = monograma()
    # o halo é o mesmo cromo desfocado: cola primeiro para criar o brilho
    hx = (BASE - halo.width) // 2
    hy = (BASE - halo.height) // 2
    base.alpha_composite(halo, (hx, hy))
    base.alpha_composite(letra, (hx, hy))
    base = aro(base)

    # vinheta leve nas bordas
    vin = Image.new("L", (BASE, BASE), 0)
    ImageDraw.Draw(vin).ellipse((-BASE * 0.20, -BASE * 0.20,
                                 BASE * 1.20, BASE * 1.20), fill=255)
    vin = vin.filter(ImageFilter.GaussianBlur(BASE * 0.13))
    return Image.composite(base, Image.new("RGBA", (BASE, BASE), (0, 0, 0, 255)), vin)


def main():
    base = montar()
    for nome, tam in TAMANHOS.items():
        base.resize((tam, tam), Image.LANCZOS).save(SAIDA.format(nome), "PNG",
                                                    optimize=True)
        print(f"  {nome}: {tam}x{tam}")
    base.resize((512, 512), Image.LANCZOS).save("/tmp/icone-nexus-512.png")
    # folha de contato em tamanhos reais, para conferir a leitura
    folha = Image.new("RGB", (260, 80), (24, 24, 28))
    x = 8
    for tam in (48, 72, 96, 144, 192):
        ic = base.resize((tam, tam), Image.LANCZOS)
        folha.paste(ic, (x, 80 - tam - 4), ic)
        x += tam + 6
    folha.save("/tmp/icone-nexus-tamanhos.png")
    print("prévia: /tmp/icone-nexus-512.png e /tmp/icone-nexus-tamanhos.png")


if __name__ == "__main__":
    main()
