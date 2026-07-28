#!/usr/bin/env python3
"""Generate README and GitHub social-preview visuals from real workbench screenshots."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "assets"
FONT = Path("/System/Library/Fonts/ヒラギノ角ゴシック W4.ttc")
SCREENSHOTS = [
    ASSETS / "data-copilot-result.png",
    ASSETS / "knowledge-copilot-result.png",
    ASSETS / "support-copilot-result.png",
    ASSETS / "report-copilot-result.png",
    ASSETS / "resume-copilot-result.png",
    ASSETS / "admin-enterprise-status.png",
]
LANCZOS = getattr(Image, "Resampling", Image).LANCZOS


def font(size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT), size)


def round_rect(draw: ImageDraw.ImageDraw, box, radius: int, fill) -> None:
    left, top, right, bottom = box
    draw.rectangle((left + radius, top, right - radius, bottom), fill=fill)
    draw.rectangle((left, top + radius, right, bottom - radius), fill=fill)
    draw.ellipse((left, top, left + radius * 2, top + radius * 2), fill=fill)
    draw.ellipse((right - radius * 2, top, right, top + radius * 2), fill=fill)
    draw.ellipse((left, bottom - radius * 2, left + radius * 2, bottom), fill=fill)
    draw.ellipse((right - radius * 2, bottom - radius * 2, right, bottom), fill=fill)


def rounded_crop(image: Image.Image, size: tuple[int, int], radius: int) -> Image.Image:
    fitted = image.copy()
    fitted.thumbnail(size, LANCZOS)
    canvas = Image.new("RGBA", size, (255, 255, 255, 0))
    offset = ((size[0] - fitted.width) // 2, (size[1] - fitted.height) // 2)
    canvas.alpha_composite(fitted.convert("RGBA"), offset)
    mask = Image.new("L", size, 0)
    round_rect(ImageDraw.Draw(mask), (0, 0, size[0], size[1]), radius, 255)
    canvas.putalpha(mask)
    return canvas


def create_social_preview() -> None:
    width, height = 1280, 640
    image = Image.new("RGB", (width, height))
    pixels = image.load()
    for y in range(height):
        for x in range(width):
            t = (x + y * 0.45) / (width + height * 0.45)
            pixels[x, y] = (
                int(8 + 16 * t),
                int(18 + 27 * t),
                int(42 + 40 * t),
            )

    glow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse((420, -260, 1150, 470), fill=(63, 110, 255, 75))
    glow_draw.ellipse((-180, 390, 480, 880), fill=(31, 199, 177, 45))
    image = Image.alpha_composite(image.convert("RGBA"), glow.filter(ImageFilter.GaussianBlur(80)))
    draw = ImageDraw.Draw(image)

    logo = Image.open(
        ROOT / "app/business-copilot-app/src/main/resources/static/images/qcoding-logo.png"
    ).convert("RGBA")
    logo.thumbnail((68, 68), LANCZOS)
    image.alpha_composite(logo, (56, 48))
    draw.text((140, 64), "QCoding AI", font=font(29), fill=(240, 245, 255))

    draw.text((56, 153), "Spring AI", font=font(56), fill=(255, 255, 255))
    draw.text((56, 218), "Business Copilot", font=font(56), fill=(255, 255, 255))
    draw.text(
        (58, 304),
        "Five runnable workflows. Built for trust.",
        font=font(22),
        fill=(178, 197, 228),
    )

    pills = ["Text-to-SQL", "Cited RAG", "Support", "Reports", "HR", "Enterprise"]
    x, y = 58, 370
    for label in pills:
        label_box = draw.textbbox((0, 0), label, font=font(16))
        label_width = label_box[2] - label_box[0]
        pill_width = label_width + 30
        if x + pill_width > 535:
            x, y = 58, y + 48
        round_rect(draw, (x, y, x + pill_width, y + 36), 18, (42, 72, 127, 255))
        draw.text((x + 15, y + 8), label, font=font(16), fill=(224, 233, 250))
        x += pill_width + 10

    trust = "Guardrails  ·  Evidence  ·  Human confirmation  ·  Audit"
    draw.text((58, 516), trust, font=font(18), fill=(112, 229, 196))
    draw.text(
        (58, 558),
        "Java 21  ·  Spring Boot 4.1  ·  Spring AI 2.0  ·  PostgreSQL",
        font=font(17),
        fill=(150, 169, 203),
    )

    card_size = (640, 360)
    shadow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    shadow_draw = ImageDraw.Draw(shadow)
    round_rect(shadow_draw, (615, 70, 1235, 430), 28, (0, 0, 0, 120))
    image = Image.alpha_composite(image, shadow.filter(ImageFilter.GaussianBlur(22)))
    data_card = rounded_crop(Image.open(SCREENSHOTS[0]), (600, 338), 22)
    image.alpha_composite(data_card, (625, 58))

    small_size = (340, 191)
    second_shadow = Image.new("RGBA", image.size, (0, 0, 0, 0))
    second_draw = ImageDraw.Draw(second_shadow)
    round_rect(second_draw, (870, 398, 1236, 611), 22, (0, 0, 0, 125))
    image = Image.alpha_composite(image, second_shadow.filter(ImageFilter.GaussianBlur(18)))
    enterprise_card = rounded_crop(Image.open(SCREENSHOTS[-1]), small_size, 18)
    image.alpha_composite(enterprise_card, (884, 404))

    image.convert("RGB").save(ASSETS / "social-preview.png", optimize=True)


def create_demo_gif() -> None:
    frames = []
    for path in SCREENSHOTS:
        image = Image.open(path).convert("RGB").resize((960, 540), LANCZOS)
        frames.append(ImageEnhance.Contrast(image).enhance(1.02))
    frames[0].save(
        ASSETS / "workbench-demo.gif",
        save_all=True,
        append_images=frames[1:],
        duration=2200,
        loop=0,
        optimize=True,
        disposal=2,
    )


if __name__ == "__main__":
    create_social_preview()
    create_demo_gif()
