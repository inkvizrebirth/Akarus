"""
Генератор мокапов интерфейса для документации (docs/preview-*.png).

Скрипт НЕ нужен для сборки мода — он повторяет ту же геометрию и те же цвета,
что и com.akarus.client.gui.ClickGuiScreen / HudRenderer, и рисует их средствами
Pillow. Удобно посмотреть на внешний вид, не запуская Minecraft.

Запуск:  python3 tools/preview_render.py
"""

import colorsys
import math
import os

from PIL import Image, ImageDraw, ImageFont

FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
SCALE = 2          # рисуем в 2x, чтобы картинка была чёткой
LINE_HEIGHT = 9    # высота строки шрифта Minecraft

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs")


# ---------- цвета ----------

def rgb(a, r, g, b):
    """ARGB-константа из Java-кода → кортеж PIL (R, G, B, A)."""
    return (r, g, b, a)


def argb(value):
    return rgb((value >> 24) & 0xFF, (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF)


def with_alpha(value, alpha01):
    r, g, b, _ = argb(value)
    return (r, g, b, int(255 * alpha01))


def mix(first, second, t):
    return tuple(int(first[i] + (second[i] - first[i]) * t) for i in range(4))


def hsb(hue, saturation, brightness, alpha=255):
    r, g, b = colorsys.hsv_to_rgb(hue % 1.0, saturation, brightness)
    return (int(r * 255), int(g * 255), int(b * 255), alpha)


# ---------- мини-«рендер» ----------

class Canvas:
    def __init__(self, width, height, background):
        self.w = width * SCALE
        self.h = height * SCALE
        self.img = Image.new("RGBA", (self.w, self.h), background)
        self._fonts = {}

    def font(self, size):
        key = size * SCALE
        if key not in self._fonts:
            self._fonts[key] = ImageFont.truetype(FONT_PATH, key)
        return self._fonts[key]

    def _composite(self, box, draw_fn):
        x0 = max(0, int(box[0] * SCALE))
        y0 = max(0, int(box[1] * SCALE))
        x1 = min(self.w, int(math.ceil(box[2] * SCALE)))
        y1 = min(self.h, int(math.ceil(box[3] * SCALE)))
        if x1 <= x0 or y1 <= y0:
            return
        layer = Image.new("RGBA", (x1 - x0, y1 - y0), (0, 0, 0, 0))
        draw_fn(ImageDraw.Draw(layer), -x0, -y0)
        self.img.paste(Image.alpha_composite(self.img.crop((x0, y0, x1, y1)), layer), (x0, y0))

    def rect(self, x, y, w, h, color):
        self._composite((x, y, x + w, y + h), lambda d, ox, oy: d.rectangle(
            [x * SCALE + ox, y * SCALE + oy, (x + w) * SCALE + ox, (y + h) * SCALE + oy], fill=color))

    def rrect(self, x, y, w, h, r, color):
        self._composite((x, y, x + w, y + h), lambda d, ox, oy: d.rounded_rectangle(
            [x * SCALE + ox, y * SCALE + oy, (x + w) * SCALE + ox, (y + h) * SCALE + oy],
            radius=max(0, r) * SCALE, fill=color))

    def vgradient(self, x, y, w, h, top_color, bottom_color):
        for i in range(int(h)):
            self.rect(x, y + i, w, 1, mix(top_color, bottom_color, i / max(1, h - 1)))

    def text(self, x, y, value, color, size=LINE_HEIGHT):
        font = self.font(size)
        self._composite((x, y, x + 420, y + size + 3),
                        lambda d, ox, oy: d.text((x * SCALE + ox, y * SCALE + oy), value, font=font, fill=color))

    def text_width(self, value, size=LINE_HEIGHT):
        return int(self.font(size).getlength(value))

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.img.save(path)


# ---------- константы (совпадают с Java-кодом) ----------

PANEL_BORDER = 0xFF2B2B36
PANEL_TOP = 0xF61C1C26
PANEL_BOTTOM = 0xF612121A
LIST_BACKGROUND = 0x4A0E0E15
ROW_BACKGROUND = 0xD815151D
ROW_BORDER = 0x18FFFFFF
TEXT_PRIMARY = 0xFFF4F4F9
TEXT_SECONDARY = 0xFFB8B8C6
TEXT_DIM = 0xFF75758A

GUI_WIDTH, GUI_HEIGHT = 480, 300
HEADER_HEIGHT = 34
CATEGORY_WIDTH = 124
CATEGORY_ROW_HEIGHT = 22
CATEGORY_GAP = 4
MODULE_ROW_HEIGHT = 34
SETTING_ROW_HEIGHT = 16
PADDING = 7
FOOTER_HEIGHT = 14
PANEL_RADIUS = 8

CATEGORIES = [
    ("HUD", 0xFF5CE1E6, True),
    ("Рендер", 0xFF8A6CFF, False),
    ("Движение", 0xFFFFB86C, False),
    ("Бой", 0xFFFF5C7A, False),
    ("Прочее", 0xFF8DE06C, False),
]

HUD_SETTINGS = [
    ("FPS", True),
    ("Координаты", True),
    ("Направление", False),
    ("Пинг", True),
    ("Водяной знак", True),
    ("Список модулей", True),
]


def draw_toggle(canvas, x, y, w, h, progress, accent):
    canvas.rrect(x, y, w, h, h // 2, mix(rgb(0xFF, 0x3A, 0x3A, 0x46), argb(accent), progress))
    knob = h - 4
    canvas.rrect(x + 2 + (w - knob - 4) * progress, y + 2, knob, knob, knob // 2, rgb(0xFF, 0xF2, 0xF2, 0xF7))


def draw_module_row(canvas, x, y, w, name, description, enabled, accent, toggle, expanded):
    background = mix(argb(ROW_BACKGROUND), with_alpha(accent, 0.55), toggle * 0.55)
    border = mix(argb(ROW_BORDER), argb(accent), toggle * 0.6)
    canvas.rrect(x, y, w, MODULE_ROW_HEIGHT, 6, border)
    canvas.rrect(x + 1, y + 1, w - 2, MODULE_ROW_HEIGHT - 2, 5, background)
    canvas.text(x + 9, y + 4, name, argb(TEXT_PRIMARY if enabled else TEXT_SECONDARY))
    canvas.text(x + 9, y + 17, description, argb(TEXT_DIM))
    draw_toggle(canvas, x + w - 30 - 9, y + (MODULE_ROW_HEIGHT - 12) // 2, 30, 12, toggle, accent)
    if expanded:
        canvas.text(x + w - 30 - 22, y + 4, "-", argb(TEXT_DIM))


def draw_setting_row(canvas, x, y, w, name, enabled, accent):
    canvas.rrect(x, y, w, SETTING_ROW_HEIGHT - 2, 4, argb(0x12FFFFFF))
    canvas.rrect(x + 1, y + 1, w - 2, SETTING_ROW_HEIGHT - 4, 3, argb(0x6E111119))
    canvas.text(x + 9, y + 3, name, argb(TEXT_SECONDARY if enabled else TEXT_DIM))
    draw_toggle(canvas, x + w - 24 - 8, y + 2, 24, 10, 1.0 if enabled else 0.0, accent)


def render_clickgui(path):
    canvas = Canvas(640, 400, rgb(0xFF, 0x12, 0x12, 0x1A))

    # «Мир» позади окна: небо, горизонт, земля
    canvas.vgradient(0, 0, 640, 260, rgb(0xFF, 0x2A, 0x3A, 0x55), rgb(0xFF, 0x18, 0x22, 0x33))
    canvas.vgradient(0, 260, 640, 140, rgb(0xFF, 0x24, 0x1C, 0x18), rgb(0xFF, 0x12, 0x10, 0x14))
    for i in range(0, 640, 40):
        canvas.rect(i, 250 + (i % 3) * 6, 39, 60, rgb(0x28, 0x3C, 0x30, 0x18))

    # Затемнение (в самом моде поверх этого ещё работает blurBeforeThisStratum)
    canvas.rect(0, 0, 640, 400, rgb(0x73, 0x00, 0x00, 0x00))

    x = (640 - GUI_WIDTH) // 2
    y = (400 - GUI_HEIGHT) // 2
    accent = 0xFF5CE1E6

    # Тень
    canvas.rrect(x + 3, y + 5, GUI_WIDTH, GUI_HEIGHT, PANEL_RADIUS, rgb(0x40, 0x00, 0x00, 0x00))
    canvas.rrect(x + 2, y + 3, GUI_WIDTH, GUI_HEIGHT, PANEL_RADIUS, rgb(0x28, 0x00, 0x00, 0x00))

    # Панель: рамка + двухтоновый фон
    canvas.rrect(x, y, GUI_WIDTH, GUI_HEIGHT, PANEL_RADIUS, argb(PANEL_BORDER))
    canvas.rrect(x + 1, y + 1, GUI_WIDTH - 2, GUI_HEIGHT - 2, PANEL_RADIUS - 1, argb(PANEL_TOP))
    canvas.vgradient(x + 1, y + (GUI_HEIGHT - 2) // 2, GUI_WIDTH - 2, (GUI_HEIGHT - 2) // 2,
                     argb(PANEL_TOP), argb(PANEL_BOTTOM))

    # Шапка
    canvas.rrect(x + 1, y + 1, GUI_WIDTH - 2, HEADER_HEIGHT // 2 + 4, PANEL_RADIUS - 1,
                 mix(argb(PANEL_TOP), argb(accent), 0.18))
    canvas.rrect(x + 1, y + 1 + HEADER_HEIGHT // 2 - 4, GUI_WIDTH - 2, HEADER_HEIGHT // 2, 0,
                 mix(argb(PANEL_TOP), argb(accent), 0.18))
    canvas.vgradient(x + 1, y + HEADER_HEIGHT - 1, GUI_WIDTH - 2, 2, with_alpha(accent, 0.12), argb(accent))

    canvas.text(x + PADDING, y + 12, "Akarus", argb(TEXT_PRIMARY))
    canvas.text(x + PADDING + canvas.text_width("Akarus") + 5, y + 13, "v0.1.0", argb(TEXT_DIM))
    hint = "ESC — закрыть"
    canvas.text(x + GUI_WIDTH - PADDING - canvas.text_width(hint) - 3, y + 12, hint, argb(TEXT_DIM))

    # Категории
    row_y = y + HEADER_HEIGHT + PADDING
    for name, color, is_selected in CATEGORIES:
        if is_selected:
            canvas.rrect(x + PADDING, row_y, CATEGORY_WIDTH, CATEGORY_ROW_HEIGHT, 6,
                         mix(with_alpha(color, 0.55), argb(color), 0.35))
            canvas.rect(x + PADDING, row_y + 4, 2, CATEGORY_ROW_HEIGHT - 8, argb(color))
        canvas.text(x + PADDING + 11, row_y + 7, name,
                    argb(TEXT_PRIMARY if is_selected else TEXT_SECONDARY))
        row_y += CATEGORY_ROW_HEIGHT + CATEGORY_GAP

    # Список модулей
    list_x = x + PADDING + CATEGORY_WIDTH + PADDING
    list_y = y + HEADER_HEIGHT + PADDING
    list_w = GUI_WIDTH - CATEGORY_WIDTH - PADDING * 3
    list_h = GUI_HEIGHT - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT
    canvas.rrect(list_x, list_y, list_w, list_h, 6, argb(LIST_BACKGROUND))

    draw_module_row(canvas, list_x, list_y, list_w, "HUD-инфо",
                    "Показывает FPS, координаты, пинг и активные модули", True, accent, 1.0, True)

    settings_y = list_y + MODULE_ROW_HEIGHT
    for setting_name, setting_enabled in HUD_SETTINGS:
        draw_setting_row(canvas, list_x + 10, settings_y, list_w - 20, setting_name, setting_enabled, accent)
        settings_y += SETTING_ROW_HEIGHT

    # Полоса прокрутки
    canvas.rrect(list_x + list_w - 5, list_y + 3, 3, list_h - 6, 1, argb(0x22FFFFFF))
    canvas.rrect(list_x + list_w - 5, list_y + 3, 3, 90, 1, with_alpha(accent, 0.85))

    # Подсказка внизу
    canvas.text(x + PADDING, y + GUI_HEIGHT - PADDING - LINE_HEIGHT + 1,
                "ЛКМ — включить модуль   •   ПКМ — настройки   •   колесо — прокрутка", argb(TEXT_DIM))

    canvas.save(path)


def render_hud(path):
    canvas = Canvas(560, 220, rgb(0xFF, 0x00, 0x00, 0x00))

    # Условный «мир»: небо, горизонт, земля, прицел
    canvas.vgradient(0, 0, 560, 120, rgb(0xFF, 0x6F, 0xA8, 0xE0), rgb(0xFF, 0xB6, 0xD8, 0xF0))
    canvas.vgradient(0, 120, 560, 100, rgb(0xFF, 0x3B, 0x6B, 0x2E), rgb(0xFF, 0x22, 0x3F, 0x1C))
    for i in range(0, 560, 32):
        canvas.rect(i, 118 + (i % 4) * 5, 31, 40, rgb(0x2D, 0x5A, 0x3C, 0x30))
    canvas.rect(276, 106, 8, 8, rgb(0xC8, 0xFF, 0xFF, 0xFF))

    x, y = 6, 6

    # Водяной знак: радуга по символам
    cursor = x
    for index, symbol in enumerate("Akarus 0.1.0"):
        canvas.text(cursor, y, symbol, hsb(index * 0.035, 0.75, 1.0))
        cursor += canvas.text_width(symbol)
    y += LINE_HEIGHT + 5

    lines = ["FPS: 144", "XYZ: 128 64 -255", "Направление: Север (-Z)", "Пинг: 42 мс"]
    width = max(canvas.text_width(line) for line in lines) + 15
    height = len(lines) * (LINE_HEIGHT + 2) - 2 + 12

    canvas.rrect(x, y, width, height, 4, argb(0x33FFFFFF))
    canvas.rrect(x + 1, y + 1, width - 2, height - 2, 3, argb(0xB40D0D12))
    canvas.vgradient(x + 1, y + 3, 2, height - 6, hsb(0.0, 0.75, 1.0), hsb(0.18, 0.75, 1.0))

    text_y = y + 6
    for line in lines:
        canvas.text(x + 9, text_y, line, argb(0xFFEDEDF5))
        text_y += LINE_HEIGHT + 2
    y += height + 5

    # Список активных модулей
    for index, module_name in enumerate(["HUD-инфо"]):
        canvas.text(x, y, module_name, hsb(index * 0.08, 0.75, 1.0))
        y += LINE_HEIGHT + 2

    canvas.save(path)


if __name__ == "__main__":
    render_clickgui(os.path.join(OUT_DIR, "preview-clickgui.png"))
    render_hud(os.path.join(OUT_DIR, "preview-hud.png"))
    print("Мокапы сохранены:", os.path.abspath(OUT_DIR))
