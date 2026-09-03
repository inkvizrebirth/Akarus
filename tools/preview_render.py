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
    ("Движение", 0xFFFFB86C, True),
    ("Бой", 0xFFFF5C7A, False),
    ("Прочее", 0xFF8DE06C, False),
]

# Модули вкладки «Движение»: имя, описание, включён, бинд, раскрыт
MOVEMENT_MODULES = [
    ("FreeCam", "Полёт сквозь блоки: игрок двигается по-настоящему", True, "N", True),
    ("AutoWalk", "Летишь фрикамом, ПКМ — Baritone идёт на координаты", True, "G", False),
]

# Настройки FreeCam: слайдер и переключатель
FREECAM_SETTINGS = [
    ("slider", "Скорость", 6, 1, 20),
    ("toggle", "Ускорение на спринт", True, 0, 0),
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


def draw_module_row(canvas, x, y, w, name, description, enabled, accent, toggle, expanded, bind=None):
    background = mix(argb(ROW_BACKGROUND), with_alpha(accent, 0.45), toggle * 0.45)
    border = mix(argb(ROW_BORDER), argb(accent), toggle * 0.55)
    canvas.rrect(x, y, w, MODULE_ROW_HEIGHT, 6, border)
    canvas.rrect(x + 1, y + 1, w - 2, MODULE_ROW_HEIGHT - 2, 5, background)
    canvas.text(x + 9, y + 4, name, argb(TEXT_PRIMARY if enabled else TEXT_SECONDARY))
    if bind:
        # Бинд модуля — серым рядом с названием
        canvas.text(x + 9 + canvas.text_width(name) + 6, y + 5, bind, argb(TEXT_DIM))
    canvas.text(x + 9, y + 17, description, argb(TEXT_DIM))
    draw_toggle(canvas, x + w - 30 - 9, y + (MODULE_ROW_HEIGHT - 12) // 2, 30, 12, toggle, accent)
    if expanded:
        canvas.text(x + w - 30 - 22, y + 4, "-", argb(TEXT_DIM))


def draw_setting_row(canvas, x, y, w, name, enabled, accent):
    canvas.rrect(x, y, w, SETTING_ROW_HEIGHT - 2, 4, argb(0x10FFFFFF))
    canvas.rrect(x + 1, y + 1, w - 2, SETTING_ROW_HEIGHT - 4, 3, mix(argb(0x80000000), argb(0x14FFFFFF), 0.6))
    canvas.text(x + 9, y + 3, name, argb(TEXT_SECONDARY if enabled else TEXT_DIM))
    draw_toggle(canvas, x + w - 24 - 8, y + 2, 24, 10, 1.0 if enabled else 0.0, accent)


def draw_color_row(canvas, x, y, w, name, color, code, accent, focused=False):
    """Строка цвета: подпись, плашка с цветом и HEX-код (в фокусе — рамка и курсор)."""
    canvas.rrect(x, y, w, TEXT_ROW_HEIGHT - 2, 4, argb(accent if focused else 0x10FFFFFF))
    canvas.rrect(x + 1, y + 1, w - 2, TEXT_ROW_HEIGHT - 4, 3, mix(argb(0x80000000), argb(0x14FFFFFF), 0.6))
    text_y = y + (TEXT_ROW_HEIGHT - 2 - LINE_HEIGHT) // 2 + 1
    canvas.text(x + 9, text_y, name, argb(TEXT_SECONDARY))

    swatch_w, swatch_h = 26, TEXT_ROW_HEIGHT - 12
    code_x = x + w - 9 - canvas.text_width(code)
    swatch_x = code_x - 6 - swatch_w
    swatch_y = y + (TEXT_ROW_HEIGHT - 2 - swatch_h) // 2
    canvas.rrect(swatch_x, swatch_y, swatch_w, swatch_h, 3, argb(0xFF000000))
    canvas.rrect(swatch_x + 1, swatch_y + 1, swatch_w - 2, swatch_h - 2, 2, argb(color))
    canvas.text(code_x, text_y, code, argb(TEXT_PRIMARY if focused else TEXT_DIM))
    if focused:
        canvas.rect(code_x + canvas.text_width(code) + 1, text_y - 1, 1, LINE_HEIGHT - 1, argb(accent))


def draw_button_row(canvas, x, y, w, name, label, accent):
    """Строка-кнопка: слева подпись, справа — сама кнопка."""
    canvas.rrect(x, y, w, TEXT_ROW_HEIGHT - 2, 4, argb(0x10FFFFFF))
    canvas.rrect(x + 1, y + 1, w - 2, TEXT_ROW_HEIGHT - 4, 3, mix(argb(0x80000000), argb(0x14FFFFFF), 0.6))
    text_y = y + (TEXT_ROW_HEIGHT - 2 - LINE_HEIGHT) // 2 + 1
    canvas.text(x + 9, text_y, name, argb(TEXT_DIM))

    bw, bh = 54, TEXT_ROW_HEIGHT - 10
    bx, by = x + w - 8 - bw, y + (TEXT_ROW_HEIGHT - 2 - bh) // 2
    canvas.rrect(bx, by, bw, bh, 4, mix(argb(0xFF26262E), argb(accent), 0.40))
    canvas.text(bx + (bw - canvas.text_width(label)) // 2, by + (bh - LINE_HEIGHT) // 2 + 1,
                label, argb(TEXT_PRIMARY))


def draw_text_row(canvas, x, y, w, name, value, accent):
    """Текстовое поле настройки (фокус: рамка акцентного цвета и курсор)."""
    canvas.rrect(x, y, w, TEXT_ROW_HEIGHT - 2, 4, argb(accent))
    canvas.rrect(x + 1, y + 1, w - 2, TEXT_ROW_HEIGHT - 4, 3, mix(argb(0x80000000), argb(0x14FFFFFF), 0.6))
    text_y = y + (TEXT_ROW_HEIGHT - 2 - LINE_HEIGHT) // 2 + 1
    canvas.text(x + 9, text_y, value, argb(TEXT_PRIMARY))
    label = name
    canvas.text(x + w - 9 - canvas.text_width(label), text_y, label, argb(TEXT_DIM))
    canvas.rect(x + 9 + canvas.text_width(value) + 1, text_y - 1, 1, LINE_HEIGHT - 1, argb(accent))


def draw_slider_row(canvas, x, y, w, name, value, value_max, accent, value_min=0):
    """Слайдер числовой настройки."""
    canvas.rrect(x, y, w, SLIDER_ROW_HEIGHT - 2, 4, argb(0x10FFFFFF))
    canvas.rrect(x + 1, y + 1, w - 2, SLIDER_ROW_HEIGHT - 4, 3, mix(argb(0x80000000), argb(0x14FFFFFF), 0.6))
    canvas.text(x + 9, y + 4, name, argb(TEXT_SECONDARY))
    text = str(value)
    canvas.text(x + w - 9 - canvas.text_width(text), y + 4, text, argb(TEXT_PRIMARY))

    track_x = x + 9
    track_y = y + SLIDER_ROW_HEIGHT - 10
    track_w = w - 18
    progress = 0.0 if value_max <= value_min else (value - value_min) / (value_max - value_min)
    canvas.rrect(track_x, track_y, track_w, 3, 1, argb(0x26FFFFFF))
    canvas.rrect(track_x, track_y, max(2, int(track_w * progress)), 3, 1, argb(accent))
    canvas.rrect(track_x + int((track_w - 6) * progress), track_y - 2, 6, 7, 3, argb(0xFFF2F2F7))


def draw_settings(canvas, x, row_y, w, accent, settings):
    """Рисует раскрытые настройки модуля. Каждая настройка — кортеж, первым идёт вид."""
    for entry in settings:
        kind = entry[0]
        if kind == "slider":
            _, name, value, value_min, value_max = entry
            draw_slider_row(canvas, x, row_y, w, name, value, value_max, accent, value_min)
            row_y += SLIDER_ROW_HEIGHT
        elif kind == "text":
            _, name, value = entry
            draw_text_row(canvas, x, row_y, w, name, value, accent)
            row_y += TEXT_ROW_HEIGHT
        elif kind == "color":
            _, name, color, code, focused = entry
            draw_color_row(canvas, x, row_y, w, name, color, code, accent, focused)
            row_y += TEXT_ROW_HEIGHT
        elif kind == "button":
            _, name, label = entry
            draw_button_row(canvas, x, row_y, w, name, label, accent)
            row_y += TEXT_ROW_HEIGHT
        else:
            _, name, enabled = entry[:3]
            draw_setting_row(canvas, x, row_y, w, name, enabled, accent)
            row_y += SETTING_ROW_HEIGHT
    return row_y


def render_clickgui(path, selected="Движение", accent=0xFF8DE06C, modules=MOVEMENT_MODULES,
                    settings=FREECAM_SETTINGS, version="0.4.0", ripple=True, scroll_bar=90):
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
    canvas.text(x + PADDING + canvas.text_width("Akarus") + 5, y + 13, "v" + version, argb(TEXT_DIM))
    hint = "ESC — закрыть"
    canvas.text(x + GUI_WIDTH - PADDING - canvas.text_width(hint) - 3, y + 12, hint, argb(TEXT_DIM))

    # Категории
    row_y = y + HEADER_HEIGHT + PADDING
    for name, color, _ in CATEGORIES:
        is_selected = name == selected
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

    row_y = list_y
    for name, description, enabled, bind, expanded in modules:
        draw_module_row(canvas, list_x, row_y, list_w, name, description, enabled, accent,
                        1.0 if enabled else 0.0, expanded, bind)
        row_y += MODULE_ROW_HEIGHT

        if expanded:
            row_y = draw_settings(canvas, list_x + 10, row_y, list_w - 20, accent, settings)

    # Волна по клику — как будто только что нажали на строку модуля
    if ripple:
        draw_ripple(canvas, list_x + 120, list_y + 17, 46, accent, 0.75)

    # Полоса прокрутки
    canvas.rrect(list_x + list_w - 5, list_y + 3, 3, list_h - 6, 1, argb(0x1AFFFFFF))
    canvas.rrect(list_x + list_w - 5, list_y + 3, 3, scroll_bar, 1, with_alpha(accent, 0.9))

    # Подсказка внизу
    canvas.text(x + PADDING, y + GUI_HEIGHT - PADDING - LINE_HEIGHT + 1,
                "ЛКМ — вкл/выкл   •   ПКМ — настройки   •   СКМ — бинд   •   колесо — прокрутка", argb(TEXT_DIM))

    canvas.save(path)


def render_render_tab(path):
    """Вкладка «Рендер»: модуль «Обводка рук» с цветами, градиентом и кнопкой редактора."""
    modules = [
        ("Обводка рук", "Цветной контур вокруг руки и предмета", True, "J", True),
        ("ViewModel", "Масштаб, сдвиг и поворот рук от первого лица", True, "V", False),
    ]
    settings = [
        ("toggle", "Обводить руку", True),
        ("toggle", "Обводить предмет", True),
        ("slider", "Толщина", 3, 1, 8),
        ("color", "Цвет", 0xFF8A6CFF, "#8A6CFF", True),
        ("color", "Второй цвет", 0xFF5CE1E6, "#5CE1E6", False),
        ("toggle", "Градиент", False),
        ("toggle", "Радуга", False),
        ("button", "Раскладка рук", "Настроить"),
    ]
    render_clickgui(path, selected="Рендер", accent=0xFF8A6CFF, modules=modules,
                    settings=settings, scroll_bar=64)


def render_hands(path):
    """Редактор раскладки рук: мир, рука с обводкой и панель параметров справа."""
    width, height = 560, 320
    canvas = Canvas(width, height, argb(0xFF101018))

    # Мир от первого лица
    canvas.vgradient(0, 0, width, 190, argb(0xFF74A9DE), argb(0xFFC3DDF2))
    canvas.vgradient(0, 190, width, height - 190, argb(0xFF4C7A38), argb(0xFF22401C))
    for i in range(0, width, 32):
        canvas.rect(i, 188 + (i % 4) * 5, 31, 40, argb(0x2D5A3C30))
    canvas.rect(width // 2 - 4, 176, 8, 8, argb(0xC8FFFFFF))

    accent = 0xFF8A6CFF
    outline = argb(0xFF8A6CFF)

    # Рука от первого лица: условный силуэт из двух прямоугольников
    hand_x, hand_y, hand_w, hand_h = 300, 214, 62, 120
    item_x, item_y, item_w, item_h = 250, 150, 22, 96

    def hand_shape(grow):
        canvas.rrect(hand_x - grow, hand_y - grow, hand_w + grow * 2, hand_h + grow * 2,
                     10 + grow, with_alpha(accent, 0.85))
        canvas.rrect(item_x - grow, item_y - grow, item_w + grow * 2, item_h + grow * 2,
                     5 + grow, with_alpha(accent, 0.85))

    # Контур: тот же силуэт, но чуть больше (так это и делает HandOutlineRenderer)
    for grow in (4, 3, 2, 1):
        hand_shape(grow)

    # Предмет в руке
    canvas.rrect(item_x, item_y, item_w, item_h, 5, argb(0xFFB9B9C4))
    canvas.rrect(item_x + 3, item_y + 3, item_w - 6, item_h - 40, 3, argb(0xFF6E6E7A))
    canvas.rrect(item_x - 6, item_y + item_h - 26, item_w + 12, 9, 3, argb(0xFF8A5A2B))

    # Сама рука
    canvas.rrect(hand_x, hand_y, hand_w, hand_h, 10, argb(0xFFD9A87C))
    canvas.rrect(hand_x + 6, hand_y + 8, hand_w - 12, hand_h - 16, 8, argb(0xFFE5BC95))
    canvas.rrect(hand_x, hand_y + hand_h - 26, hand_w, 26, 10, argb(0xFFC89468))

    # Панель параметров справа
    panel_w, panel_h = 162, 22 + 15 + PADDING + 7 * 17 + PADDING
    panel_x, panel_y = width - panel_w - 10, (height - panel_h) // 2

    draw_soft_shadow(canvas, panel_x, panel_y, panel_w, panel_h, 8, 4)
    canvas.rrect(panel_x, panel_y, panel_w, panel_h, 8, argb(PANEL_OUTLINE))
    canvas.rrect(panel_x + 1, panel_y + 1, panel_w - 2, panel_h - 2, 7, argb(0xF0101014))
    canvas.text(panel_x + PADDING, panel_y + 7, "Раскладка рук", argb(TEXT_PRIMARY))

    buttons = ("Сохранить", "Сбросить")
    for index, label in enumerate(buttons):
        bx = panel_x + PADDING + index * (71 + 4)
        canvas.rrect(bx, panel_y + 22, 71, 15, 4, argb(0x14FFFFFF))
        canvas.rrect(bx + 1, panel_y + 23, 69, 13, 3, mix(argb(0x8A000000), argb(0x1AFFFFFF), 0.4))
        canvas.text(bx + (71 - canvas.text_width(label)) // 2, panel_y + 26, label, argb(TEXT_SECONDARY))

    parameters = [("Scale", "1.08"), ("X", "0.12"), ("Y", "-0.04"), ("Z", "0.00"),
                  ("Поворот X", "-6.00"), ("Поворот Y", "12.00"), ("Поворот Z", "0.00")]
    row_y = panel_y + 22 + 15 + PADDING
    for index, (name, value) in enumerate(parameters):
        is_selected = index == 0
        border = accent if is_selected else 0x14FFFFFF
        fill = with_alpha(accent, 0.16) if is_selected else argb(0x8A000000)
        canvas.rrect(panel_x + 4, row_y, panel_w - 8, 15, 4, argb(border))
        canvas.rrect(panel_x + 5, row_y + 1, panel_w - 10, 13, 3, fill)
        if is_selected:
            canvas.rect(panel_x + 4, row_y + 4, 2, 7, argb(accent))
        canvas.text(panel_x + 12, row_y + 4, name, argb(TEXT_PRIMARY if is_selected else TEXT_SECONDARY))
        canvas.text(panel_x + panel_w - 10 - canvas.text_width(value), row_y + 4, value,
                    argb(TEXT_PRIMARY if is_selected else TEXT_DIM))
        row_y += 17

    canvas.text(10, height - 14,
                "ЛКМ/ПКМ — тащить руку   •   колесо — размер или значение   •   ESC — сохранить и выйти",
                argb(TEXT_DIM))
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
    for index, symbol in enumerate("Akarus 0.4.0"):
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
    for index, module_name in enumerate(["AutoWalk", "FreeCam"]):
        canvas.text(x, y, module_name, hsb(index * 0.08, 0.75, 1.0))
        y += LINE_HEIGHT + 2

    # Панель AutoWalk внизу по центру: координаты фрикама
    accent = 0xFFFFB86C
    title = "FreeCam: 128 64 -255"
    hint = "ПКМ — Baritone пойдёт сюда"
    panel_w = max(canvas.text_width(title), canvas.text_width(hint)) + 15
    panel_h = LINE_HEIGHT * 2 + 2 + 12
    panel_x = (560 - panel_w) // 2
    panel_y = 220 - panel_h - 24

    canvas.rrect(panel_x, panel_y, panel_w, panel_h, 5, argb(0x2AFFFFFF))
    canvas.rrect(panel_x + 1, panel_y + 1, panel_w - 2, panel_h - 2, 4, argb(0xB80A0A0D))
    canvas.rect(panel_x + 7, panel_y, panel_w - 14, 1, with_alpha(accent, 0.9))
    canvas.text(panel_x + 9, panel_y + 7, title, argb(0xFFEDEDF5))
    canvas.text(panel_x + 9, panel_y + 7 + LINE_HEIGHT + 2, hint, argb(0xFFA6A6B2))
    # «Дышащая» точка справа
    canvas.rect(panel_x + panel_w - 12, panel_y + 10, 4, 4, with_alpha(accent, 0.9))

    canvas.save(path)


if __name__ == "__main__":
    render_clickgui(os.path.join(OUT_DIR, "preview-clickgui.png"))
    render_hud(os.path.join(OUT_DIR, "preview-hud.png"))
    render_render_tab(os.path.join(OUT_DIR, "preview-render.png"))
    render_hands(os.path.join(OUT_DIR, "preview-hands.png"))
    print("Мокапы сохранены:", os.path.abspath(OUT_DIR))
