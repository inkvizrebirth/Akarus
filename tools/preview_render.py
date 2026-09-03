"""
Генератор мокапов интерфейса для документации (docs/preview-*.png).

Скрипт НЕ нужен для сборки мода — он повторяет ту же геометрию и те же цвета,
что и com.akarus.client.gui.ClickGuiScreen / HudRenderer, и рисует их средствами
Pillow (блюр делается настоящим GaussianBlur, чтобы показать эффект).

Запуск:  python3 tools/preview_render.py
"""

import colorsys
import math
import os

from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
SCALE = 2          # рисуем в 2x, чтобы картинка была чёткой
LINE_HEIGHT = 9    # высота строки шрифта Minecraft

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs")


# ---------- цвета ----------

def argb(value):
    """ARGB-константа из Java-кода → кортеж PIL (R, G, B, A)."""
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF, (value >> 24) & 0xFF)


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

    def circle(self, cx, cy, radius, color):
        self._composite((cx - radius - 1, cy - radius - 1, cx + radius + 1, cy + radius + 1),
                        lambda d, ox, oy: d.ellipse(
                            [(cx - radius) * SCALE + ox, (cy - radius) * SCALE + oy,
                             (cx + radius) * SCALE + ox, (cy + radius) * SCALE + oy], fill=color))

    def vgradient(self, x, y, w, h, top_color, bottom_color):
        for i in range(int(h)):
            self.rect(x, y + i, w, 1, mix(top_color, bottom_color, i / max(1, h - 1)))

    def text(self, x, y, value, color, size=LINE_HEIGHT):
        font = self.font(size)
        self._composite((x, y, x + 420, y + size + 3),
                        lambda d, ox, oy: d.text((x * SCALE + ox, y * SCALE + oy), value, font=font, fill=color))

    def text_width(self, value, size=LINE_HEIGHT):
        return int(self.font(size).getlength(value))

    def blur(self, radius):
        """Настоящее размытие — так в мокапе выглядит blurBeforeThisStratum()."""
        self.img = self.img.filter(ImageFilter.GaussianBlur(radius * SCALE / 2))

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.img.save(path)


# ---------- константы (совпадают с Java-кодом) ----------

BACKGROUND_DIM = 0xA6000000
PANEL_OUTLINE = 0xFF1C1C20
PANEL_TOP = 0xF6151518
PANEL_BOTTOM = 0xF809090B
LIST_BACKGROUND = 0x59000000
ROW_BACKGROUND = 0xB8101013
ROW_BORDER = 0x12FFFFFF
SHEEN = 0x0CFFFFFF
TEXT_PRIMARY = 0xFFF6F6F8
TEXT_SECONDARY = 0xFFA6A6B2
TEXT_DIM = 0xFF6B6B78

GUI_WIDTH, GUI_HEIGHT = 480, 300
HEADER_HEIGHT = 34
CATEGORY_WIDTH = 124
CATEGORY_ROW_HEIGHT = 22
CATEGORY_GAP = 4
MODULE_ROW_HEIGHT = 34
SETTING_ROW_HEIGHT = 16
SLIDER_ROW_HEIGHT = 26
TEXT_ROW_HEIGHT = 20
PADDING = 7
FOOTER_HEIGHT = 14
PANEL_RADIUS = 10
SHADOW_LAYERS = 5

CATEGORIES = [
    ("HUD", 0xFF5CE1E6, False),
    ("Рендер", 0xFF8A6CFF, False),
    ("Движение", 0xFFFFB86C, False),
    ("Бой", 0xFFFF5C7A, False),
    ("Прочее", 0xFF8DE06C, True),
]

# Настройки AutoMine: текстовое поле, слайдер и переключатель
AUTOMINE_SETTINGS = [
    ("text", "Блок", "diamond_ore"),
    ("slider", "Сколько", 0),
    ("toggle", "Командами чата", False),
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
    canvas.rrect(x, y, w, h, h // 2, mix(argb(0xFF3A3A46), argb(accent), progress))
    knob = h - 4
    canvas.rrect(x + 2 + (w - knob - 4) * progress, y + 2, knob, knob, knob // 2, argb(0xFFF2F2F7))


def draw_soft_shadow(canvas, x, y, w, h, radius, layers, accent=0xFF000000):
    for i in range(layers, 0, -1):
        t = i / layers
        grow = int(t * 10)
        alpha = 7 + int(26 * (1 - t))
        canvas.rrect(x - grow, y - grow // 2 + 2, w + grow * 2, h + grow + 2,
                     radius + grow // 2, with_alpha(accent, alpha / 255.0))


def draw_ripple(canvas, cx, cy, radius, accent, fade):
    """Волна по клику: две окружности разного размера."""
    canvas.circle(cx, cy, radius, with_alpha(accent, 0.22 * fade))
    canvas.circle(cx, cy, radius * 0.6, with_alpha(accent, 0.16 * fade))


def draw_module_row(canvas, x, y, w, name, description, enabled, accent, toggle, expanded):
    background = mix(argb(ROW_BACKGROUND), with_alpha(accent, 0.45), toggle * 0.45)
    border = mix(argb(ROW_BORDER), argb(accent), toggle * 0.55)
    canvas.rrect(x, y, w, MODULE_ROW_HEIGHT, 6, border)
    canvas.rrect(x + 1, y + 1, w - 2, MODULE_ROW_HEIGHT - 2, 5, background)
    canvas.text(x + 9, y + 4, name, argb(TEXT_PRIMARY if enabled else TEXT_SECONDARY))
    canvas.text(x + 9, y + 17, description, argb(TEXT_DIM))
    draw_toggle(canvas, x + w - 30 - 9, y + (MODULE_ROW_HEIGHT - 12) // 2, 30, 12, toggle, accent)
    if expanded:
        canvas.text(x + w - 30 - 22, y + 4, "-", argb(TEXT_DIM))


def draw_setting_row(canvas, x, y, w, name, enabled, accent):
    canvas.rrect(x, y, w, SETTING_ROW_HEIGHT - 2, 4, argb(0x10FFFFFF))
    canvas.rrect(x + 1, y + 1, w - 2, SETTING_ROW_HEIGHT - 4, 3, mix(argb(0x80000000), argb(0x14FFFFFF), 0.6))
    canvas.text(x + 9, y + 3, name, argb(TEXT_SECONDARY if enabled else TEXT_DIM))
    draw_toggle(canvas, x + w - 24 - 8, y + 2, 24, 10, 1.0 if enabled else 0.0, accent)


def draw_text_row(canvas, x, y, w, name, value, accent):
    """Текстовое поле настройки (фокус: рамка акцентного цвета и курсор)."""
    canvas.rrect(x, y, w, TEXT_ROW_HEIGHT - 2, 4, argb(accent))
    canvas.rrect(x + 1, y + 1, w - 2, TEXT_ROW_HEIGHT - 4, 3, mix(argb(0x80000000), argb(0x14FFFFFF), 0.6))
    text_y = y + (TEXT_ROW_HEIGHT - 2 - LINE_HEIGHT) // 2 + 1
    canvas.text(x + 9, text_y, value, argb(TEXT_PRIMARY))
    label = name
    canvas.text(x + w - 9 - canvas.text_width(label), text_y, label, argb(TEXT_DIM))
    canvas.rect(x + 9 + canvas.text_width(value) + 1, text_y - 1, 1, LINE_HEIGHT - 1, argb(accent))


def draw_slider_row(canvas, x, y, w, name, value, value_max, accent):
    """Слайдер числовой настройки."""
    canvas.rrect(x, y, w, SLIDER_ROW_HEIGHT - 2, 4, argb(0x10FFFFFF))
    canvas.rrect(x + 1, y + 1, w - 2, SLIDER_ROW_HEIGHT - 4, 3, mix(argb(0x80000000), argb(0x14FFFFFF), 0.6))
    canvas.text(x + 9, y + 4, name, argb(TEXT_SECONDARY))
    text = str(value)
    canvas.text(x + w - 9 - canvas.text_width(text), y + 4, text, argb(TEXT_PRIMARY))

    track_x = x + 9
    track_y = y + SLIDER_ROW_HEIGHT - 10
    track_w = w - 18
    canvas.rrect(track_x, track_y, track_w, 3, 1, argb(0x26FFFFFF))
    canvas.rrect(track_x, track_y, max(2, track_w // 4), 3, 1, argb(accent))
    canvas.rrect(track_x + track_w // 4 - 3, track_y - 2, 6, 7, 3, argb(0xFFF2F2F7))


def render_clickgui(path):
    canvas = Canvas(640, 400, argb(0xFF12121A))

    # «Мир» позади окна: небо, горизонт, земля
    canvas.vgradient(0, 0, 640, 260, argb(0xFF2A3A55), argb(0xFF182233))
    canvas.vgradient(0, 260, 640, 140, argb(0xFF241C18), argb(0xFF121014))
    for i in range(0, 640, 40):
        canvas.rect(i, 250 + (i % 3) * 6, 39, 60, argb(0x283C3018))

    # Размытие (в моде это blurBeforeThisStratum) + затемнение
    canvas.blur(9)
    canvas.rect(0, 0, 640, 400, argb(BACKGROUND_DIM))

    x = (640 - GUI_WIDTH) // 2
    y = (400 - GUI_HEIGHT) // 2
    accent = 0xFF8DE06C

    # Мягкая тень
    draw_soft_shadow(canvas, x, y, GUI_WIDTH, GUI_HEIGHT, PANEL_RADIUS, SHADOW_LAYERS)

    # Панель: рамка + двухтоновый фон
    canvas.rrect(x, y, GUI_WIDTH, GUI_HEIGHT, PANEL_RADIUS, argb(PANEL_OUTLINE))
    canvas.rrect(x + 1, y + 1, GUI_WIDTH - 2, GUI_HEIGHT - 2, PANEL_RADIUS - 1, argb(PANEL_TOP))
    canvas.vgradient(x + 1, y + (GUI_HEIGHT - 2) // 2, GUI_WIDTH - 2, (GUI_HEIGHT - 2) // 2,
                     argb(PANEL_TOP), argb(PANEL_BOTTOM))
    canvas.rect(x + PANEL_RADIUS, y + 1, GUI_WIDTH - PANEL_RADIUS * 2, 1, argb(SHEEN))

    # Шапка
    canvas.rrect(x + 1, y + 1, GUI_WIDTH - 2, HEADER_HEIGHT // 2 + 4, PANEL_RADIUS - 1,
                 mix(argb(PANEL_TOP), argb(accent), 0.16))
    canvas.rrect(x + 1, y + HEADER_HEIGHT // 2 - 3, GUI_WIDTH - 2, HEADER_HEIGHT // 2, 0,
                 mix(argb(PANEL_TOP), argb(accent), 0.16))
    canvas.vgradient(x + 1, y + HEADER_HEIGHT - 2, GUI_WIDTH - 2, 2, with_alpha(accent, 0.10), with_alpha(accent, 0.85))

    canvas.text(x + PADDING, y + 12, "Akarus", argb(TEXT_PRIMARY))
    canvas.text(x + PADDING + canvas.text_width("Akarus") + 5, y + 13, "v0.1.0", argb(TEXT_DIM))
    hint = "ESC — закрыть"
    canvas.text(x + GUI_WIDTH - PADDING - canvas.text_width(hint) - 3, y + 12, hint, argb(TEXT_DIM))

    # Категории
    row_y = y + HEADER_HEIGHT + PADDING
    for name, color, is_selected in CATEGORIES:
        background = mix(with_alpha(0xFF000000, 0.55), argb(color), 0.26 if is_selected else 0.0)
        canvas.rrect(x + PADDING, row_y, CATEGORY_WIDTH, CATEGORY_ROW_HEIGHT, 6, background)
        if is_selected:
            canvas.rect(x + PADDING, row_y + 5, 2, CATEGORY_ROW_HEIGHT - 10, argb(color))
        canvas.text(x + PADDING + 11, row_y + 7, name,
                    argb(TEXT_PRIMARY if is_selected else TEXT_SECONDARY))
        row_y += CATEGORY_ROW_HEIGHT + CATEGORY_GAP

    # Список модулей
    list_x = x + PADDING + CATEGORY_WIDTH + PADDING
    list_y = y + HEADER_HEIGHT + PADDING
    list_w = GUI_WIDTH - CATEGORY_WIDTH - PADDING * 3
    list_h = GUI_HEIGHT - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT
    canvas.rrect(list_x, list_y, list_w, list_h, 6, argb(LIST_BACKGROUND))

    draw_module_row(canvas, list_x, list_y, list_w, "AutoMine",
                    "Автоматическая добыча блоков через Baritone", True, accent, 1.0, True)

    # Волна по клику — как будто только что нажали на строку модуля
    draw_ripple(canvas, list_x + 120, list_y + 17, 46, accent, 0.75)

    settings_y = list_y + MODULE_ROW_HEIGHT
    for kind, name, value in AUTOMINE_SETTINGS:
        if kind == "text":
            draw_text_row(canvas, list_x + 10, settings_y, list_w - 20, name, value, accent)
            settings_y += TEXT_ROW_HEIGHT
        elif kind == "slider":
            draw_slider_row(canvas, list_x + 10, settings_y, list_w - 20, name, value, 512, accent)
            settings_y += SLIDER_ROW_HEIGHT
        else:
            draw_setting_row(canvas, list_x + 10, settings_y, list_w - 20, name, value, accent)
            settings_y += SETTING_ROW_HEIGHT

    # Полоса прокрутки
    canvas.rrect(list_x + list_w - 5, list_y + 3, 3, list_h - 6, 1, argb(0x1AFFFFFF))
    canvas.rrect(list_x + list_w - 5, list_y + 3, 3, 90, 1, with_alpha(accent, 0.9))

    # Подсказка внизу
    canvas.text(x + PADDING, y + GUI_HEIGHT - PADDING - LINE_HEIGHT + 1,
                "ЛКМ — включить модуль   •   ПКМ — настройки   •   колесо — прокрутка", argb(TEXT_DIM))

    canvas.save(path)


def render_hud(path):
    canvas = Canvas(560, 220, argb(0xFF000000))

    # Условный «мир»: небо, горизонт, земля, прицел
    canvas.vgradient(0, 0, 560, 120, argb(0xFF6FA8E0), argb(0xFFB6D8F0))
    canvas.vgradient(0, 120, 560, 100, argb(0xFF3B6B2E), argb(0xFF223F1C))
    for i in range(0, 560, 32):
        canvas.rect(i, 118 + (i % 4) * 5, 31, 40, argb(0x2D5A3C30))
    canvas.rect(276, 106, 8, 8, argb(0xC8FFFFFF))

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

    canvas.rrect(x, y, width, height, 4, argb(0x2AFFFFFF))
    canvas.rrect(x + 1, y + 1, width - 2, height - 2, 3, argb(0xB80A0A0D))
    canvas.vgradient(x + 1, y + 3, 2, height - 6, hsb(0.0, 0.75, 1.0), hsb(0.18, 0.75, 1.0))

    text_y = y + 6
    for line in lines:
        canvas.text(x + 9, text_y, line, argb(0xFFEDEDF5))
        text_y += LINE_HEIGHT + 2
    y += height + 5

    # Список активных модулей
    for index, module_name in enumerate(["AutoMine", "FreeCam"]):
        canvas.text(x, y, module_name, hsb(index * 0.08, 0.75, 1.0))
        y += LINE_HEIGHT + 2

    canvas.save(path)


if __name__ == "__main__":
    render_clickgui(os.path.join(OUT_DIR, "preview-clickgui.png"))
    render_hud(os.path.join(OUT_DIR, "preview-hud.png"))
    print("Мокапы сохранены:", os.path.abspath(OUT_DIR))
