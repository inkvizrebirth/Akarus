"""
Генератор мокапов интерфейса для документации (docs/preview-*.png).

Скрипт НЕ нужен для сборки мода — он повторяет ту же геометрию и те же цвета,
что и com.dreamcast.client.gui.* (клиент Dreamcast DLC), и рисует их средствами
Pillow (блюр делается настоящим GaussianBlur, чтобы показать эффект).

Запуск:  python3 tools/preview_render.py
"""

import colorsys
import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

FONT_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                         "src", "main", "resources", "assets", "dreamcast", "font", "manrope-medium.ttf")
ICON_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                        "src", "main", "resources", "assets", "dreamcast", "textures", "gui", "icons")
SCALE = 2          # рисуем в 2x, чтобы картинка была чёткой
LINE_HEIGHT = 9    # высота строки шрифта Minecraft

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs")


# ---------- цвета ----------

def argb(value):
    """ARGB-константа из Java-кода → кортеж PIL (R, G, B, A)."""
    return ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF, (value >> 24) & 0xFF)


def with_alpha(value, alpha01):
    if isinstance(value, tuple):
        r, g, b = value[0], value[1], value[2]
    else:
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

    def line(self, x0, y0, x1, y1, width, color):
        self._composite((min(x0, x1) - width, min(y0, y1) - width,
                         max(x0, x1) + width + 1, max(y0, y1) + width + 1),
                        lambda d, ox, oy: d.line(
            [x0 * SCALE + ox, y0 * SCALE + oy, x1 * SCALE + ox, y1 * SCALE + oy],
            fill=color, width=max(1, int(width * SCALE))))

    def vgradient(self, x, y, w, h, top_color, bottom_color):
        for i in range(int(h)):
            self.rect(x, y + i, w, 1, mix(top_color, bottom_color, i / max(1, h - 1)))

    def text(self, x, y, value, color, size=LINE_HEIGHT):
        font = self.font(size)
        self._composite((x, y, x + 460, y + size + 3),
                        lambda d, ox, oy: d.text((x * SCALE + ox, y * SCALE + oy), value, font=font, fill=color))

    def text_width(self, value, size=LINE_HEIGHT):
        # логические пиксели (не SCALE-физические): координаты в canvas.* умножаются на SCALE
        return max(1, round(self.font(size).getlength(value) / SCALE))

    def blur(self, radius):
        """Настоящее размытие — так в мокапе выглядит blurBeforeThisStratum()."""
        self.img = self.img.filter(ImageFilter.GaussianBlur(radius * SCALE / 2))

    def paste(self, image, x=0, y=0):
        self.img.alpha_composite(image, (int(x * SCALE), int(y * SCALE)))

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        self.img.save(path)


# ---------- константы (совпадают с Java-кодом) ----------

BACKGROUND_DIM = 0xA6000000
PANEL_OUTLINE = 0xFF232329
PANEL_TOP = 0xF616161A
PANEL_BOTTOM = 0xF809090C
LIST_BACKGROUND = 0x59000000
ROW_BACKGROUND = 0xB8101013
ROW_BORDER = 0x12FFFFFF
SHEEN = 0x0CFFFFFF
TEXT_PRIMARY = 0xFFF6F6F8
TEXT_SECONDARY = 0xFFA6A6B2
TEXT_DIM = 0xFF6B6B78

ACCENT_VIOLET = 0xFF7C6CFF
ACCENT_CYAN = 0xFF45E3FF

GUI_WIDTH, GUI_HEIGHT = 500, 300
HEADER_HEIGHT = 36
CATEGORY_WIDTH = 128
CATEGORY_ROW_HEIGHT = 24
CATEGORY_GAP = 4
MODULE_ROW_HEIGHT = 36
SETTING_ROW_HEIGHT = 16
SLIDER_ROW_HEIGHT = 26
TEXT_ROW_HEIGHT = 20
PADDING = 8
FOOTER_HEIGHT = 15
PANEL_RADIUS = 12
SHADOW_LAYERS = 5

LOGO = "DREAMCAST"
VERSION = "0.8.0"

CATEGORIES = [
    ("HUD", "◎", 0xFF45E3FF, False),
    ("Рендер", "◆", 0xFF7C6CFF, True),
    ("Движение", "»", 0xFF8DE06C, False),
    ("Бой", "✖", 0xFFFF5C7A, False),
    ("Прочее", "≡", 0xFFFFC66C, False),
]

# Модули вкладки «Рендер»: id, имя, описание, включён, бинд, раскрыт
RENDER_MODULES = [
    ("trails", "Trails", "Светящийся след из партиклов за игроком", True, "—", True),
    ("esp", "ESP", "Подсветка сущностей: свечение и боксы", True, "—", False),
    ("hand_shader", "Обводка рук", "Цветной контур вокруг руки и предмета", True, "V", False),
    ("view_model", "ViewModel", "Масштаб, сдвиг и поворот рук", False, "—", False),
]

TRAILS_SETTINGS = [
    ("mode", "Стиль", ["Линия", "Партиклы", "Вместе"], 2),
    ("slider", "Толщина линии", 3, 1, 12),
    ("slider", "Длина, блоков", 10, 2, 32),
    ("color", "Цвет", 0xFF45E3FF, "#45E3FF", False),
    ("color", "Второй цвет", 0xFF7C6CFF, "#7C6CFF", False),
    ("toggle", "Градиент", True),
    ("toggle", "Радуга", False),
    ("slider", "Партиклов/шаг", 2, 1, 6),
]


_ICON_CACHE = {}


def draw_icon(canvas, name, x, y, size, color):
    """Белая иконка из ресурсов, тонированная цветом (как blit с color в игре)."""
    key = (name, color, size)
    if key not in _ICON_CACHE:
        path = os.path.join(ICON_DIR, name + ".png")
        icon = Image.open(path).convert("RGBA").resize((size * SCALE, size * SCALE), Image.LANCZOS)
        r, g, b = color[0], color[1], color[2]
        tinted = Image.new("RGBA", icon.size, (r, g, b, 0))
        alpha = icon.split()[3].point(lambda a: a * color[3] // 255)
        tinted.putalpha(alpha)
        _ICON_CACHE[key] = tinted
    canvas.img.alpha_composite(_ICON_CACHE[key], (int(x * SCALE), int(y * SCALE)))


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


def draw_module_row(canvas, x, y, w, module_id, name, description, enabled, accent, toggle, expanded,
                    bind=None, permanent=False):
    background = mix(argb(ROW_BACKGROUND), with_alpha(accent, 0.45), toggle * 0.45)
    border = mix(argb(ROW_BORDER), argb(accent), toggle * 0.55)
    canvas.rrect(x, y, w, MODULE_ROW_HEIGHT, 6, border)
    canvas.rrect(x + 1, y + 1, w - 2, MODULE_ROW_HEIGHT - 2, 5, background)
    if toggle > 0.02 or permanent:
        canvas.rrect(x + 2, y + 6, 2, MODULE_ROW_HEIGHT - 12, 1, with_alpha(accent, 0.9 * max(toggle, 0.7)))
    # Иконка модуля: тонирована темой у включённых
    icon_color = argb(accent) if (enabled or permanent) else argb(TEXT_DIM)
    draw_icon(canvas, module_id, x + 9, y + 5, 13, icon_color)
    canvas.text(x + 26, y + 6, name, argb(TEXT_PRIMARY if enabled or permanent else TEXT_SECONDARY))
    if bind:
        canvas.text(x + 26 + canvas.text_width(name) + 6, y + 7, bind, argb(TEXT_DIM))
    canvas.text(x + 26, y + 21, description, argb(TEXT_DIM))
    if permanent:
        dot_x = x + w - 30 - 9 + 12
        canvas.rrect(dot_x, y + (MODULE_ROW_HEIGHT - 6) // 2, 6, 6, 3, with_alpha(accent, 0.9))
    else:
        draw_toggle(canvas, x + w - 30 - 9, y + (MODULE_ROW_HEIGHT - 12) // 2, 30, 12, toggle, accent)
    if expanded is not None:
        draw_icon(canvas, "arrow_up" if expanded else "arrow_down", x + w - 30 - 22, y + 6, 9, argb(TEXT_DIM))


def draw_setting_row(canvas, x, y, w, name, enabled, accent):
    canvas.rrect(x, y, w, SETTING_ROW_HEIGHT - 2, 4, argb(0x10FFFFFF))
    canvas.rrect(x + 1, y + 1, w - 2, SETTING_ROW_HEIGHT - 4, 3, mix(argb(0x80000000), argb(0x14FFFFFF), 0.6))
    canvas.text(x + 9, y + 3, name, argb(TEXT_SECONDARY if enabled else TEXT_DIM))
    draw_toggle(canvas, x + w - 24 - 8, y + 2, 24, 10, 1.0 if enabled else 0.0, accent)


def draw_mode_row(canvas, x, y, w, name, options, selected, accent):
    """Строка выбора варианта — сегменты."""
    canvas.rrect(x, y, w, TEXT_ROW_HEIGHT - 2, 4, argb(0x10FFFFFF))
    canvas.rrect(x + 1, y + 1, w - 2, TEXT_ROW_HEIGHT - 4, 3, mix(argb(0x80000000), argb(0x14FFFFFF), 0.6))
    text_y = y + (TEXT_ROW_HEIGHT - 2 - LINE_HEIGHT) // 2 + 1
    canvas.text(x + 9, text_y, name, argb(TEXT_SECONDARY))
    total = 0
    widths = []
    for label in options:
        widths.append(canvas.text_width(label) + 12)
        total += widths[-1]
    total += 3 * (len(options) - 1)
    sx = x + w - 9 - total
    for label, width in zip(options, widths):
        is_selected = label == selected
        canvas.rrect(sx, y + 3, width, TEXT_ROW_HEIGHT - 8, 3,
                     argb(accent) if is_selected else argb(0x14FFFFFF))
        canvas.text(sx + (width - canvas.text_width(label)) // 2, text_y, label,
                    argb(0xFF0D0D10) if is_selected else argb(TEXT_SECONDARY))
        sx += width + 3


def draw_color_row(canvas, x, y, w, name, color, code, accent, focused=False):
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


def draw_slider_row(canvas, x, y, w, name, value, value_max, accent, value_min=0):
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
    """Раскрытые настройки модуля. Каждая настройка — кортеж, первым идёт вид."""
    for entry in settings:
        kind = entry[0]
        if kind == "slider":
            _, name, value, value_min, value_max = entry
            draw_slider_row(canvas, x, row_y, w, name, value, value_max, accent, value_min)
            row_y += SLIDER_ROW_HEIGHT
        elif kind == "color":
            _, name, color, code, focused = entry
            draw_color_row(canvas, x, row_y, w, name, color, code, accent, focused)
            row_y += TEXT_ROW_HEIGHT
        elif kind == "mode":
            _, name, options, selected_index = entry
            draw_mode_row(canvas, x, row_y, w, name, options, options[selected_index], accent)
            row_y += TEXT_ROW_HEIGHT
        else:
            _, name, enabled = entry[:3]
            draw_setting_row(canvas, x, row_y, w, name, enabled, accent)
            row_y += SETTING_ROW_HEIGHT
    return row_y


# ---------- ClickGUI ----------

def render_clickgui(path, selected="Рендер", ripple=True, search="", search_focused=False):
    canvas = Canvas(640, 400, argb(0xFF12121A))

    # «Мир» позади окна: небо, горизонт, земля
    canvas.vgradient(0, 0, 640, 260, argb(0xFF2A3A55), argb(0xFF182233))
    canvas.vgradient(0, 260, 640, 140, argb(0xFF241C18), argb(0xFF121014))
    for i in range(0, 640, 40):
        canvas.rect(i, 250 + (i % 3) * 6, 39, 60, argb(0x283C3018))

    canvas.blur(9)
    canvas.rect(0, 0, 640, 400, argb(BACKGROUND_DIM))

    accent = dict((c[0], c[2]) for c in CATEGORIES)[selected]
    x = (640 - GUI_WIDTH) // 2
    y = (400 - GUI_HEIGHT) // 2

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
    # «Текущая» градиентная линия: фиолетовый → циан, с «волной» яркости
    second = mix(argb(accent), argb(ACCENT_CYAN), 0.55)
    for i in range(GUI_WIDTH - 2):
        t = i / (GUI_WIDTH - 3)
        wave = 0.5 + 0.5 * math.sin((t * 2.2 - 0.3) * math.pi)
        canvas.rect(x + 1 + i, y + HEADER_HEIGHT - 2, 1, 1,
                    with_alpha(mix(argb(accent), second, t), 0.35 + 0.6 * wave))

    # Логотип DREAMCAST с разрядкой + версия, без бейджей
    logo_x = x + PADDING + 1
    title_y = y + (HEADER_HEIGHT - LINE_HEIGHT) // 2
    cursor = logo_x
    for symbol in LOGO:
        canvas.text(cursor, title_y, symbol, argb(TEXT_PRIMARY))
        cursor += canvas.text_width(symbol) + 3
    canvas.text(cursor + 8, title_y + 1, "v" + VERSION, argb(TEXT_DIM))

    # Поле поиска в шапке
    search_w = 112
    search_x = x + GUI_WIDTH - PADDING - search_w
    search_y = y + (HEADER_HEIGHT - 14) // 2
    canvas.rrect(search_x, search_y, search_w, 14, 7,
                 argb(accent) if search_focused else argb(0x22FFFFFF))
    canvas.rrect(search_x + 1, search_y + 1, search_w - 2, 12, 6,
                 mix(argb(0x66000000), argb(0x14FFFFFF), 0.6 if search_focused else 0.2))
    if search:
        canvas.text(search_x + 8, search_y + 3, search + "|", argb(TEXT_PRIMARY))
    else:
        canvas.text(search_x + 8, search_y + 3, "поиск…", argb(TEXT_DIM))

    # Категории: глиф, имя, счётчик
    row_y = y + HEADER_HEIGHT + PADDING
    counts = {"HUD": "1/3", "Рендер": "3/4", "Движение": "2/5", "Бой": "0/1", "Прочее": "3/4"}
    for name, glyph, color, _ in CATEGORIES:
        is_selected = name == selected
        background = mix(with_alpha(0xFF000000, 0.55), argb(color), 0.26 if is_selected else 0.0)
        canvas.rrect(x + PADDING, row_y, CATEGORY_WIDTH, CATEGORY_ROW_HEIGHT, 6, background)
        if is_selected:
            canvas.rect(x + PADDING, row_y + 5, 2, CATEGORY_ROW_HEIGHT - 10, argb(color))
        canvas.text(x + PADDING + 9, row_y + (CATEGORY_ROW_HEIGHT - LINE_HEIGHT) // 2 + 1, glyph,
                    argb(color) if is_selected else argb(TEXT_DIM))
        canvas.text(x + PADDING + 19, row_y + (CATEGORY_ROW_HEIGHT - LINE_HEIGHT) // 2 + 1, name,
                    argb(TEXT_PRIMARY if is_selected else TEXT_SECONDARY))
        badge = counts.get(name, "0")
        bx = x + PADDING + CATEGORY_WIDTH - 9 - canvas.text_width(badge)
        by = row_y + (CATEGORY_ROW_HEIGHT - LINE_HEIGHT) // 2 + 1
        active = "/" in badge
        if active:
            canvas.rrect(bx - 4, by - 2, canvas.text_width(badge) + 8, LINE_HEIGHT + 3, 4,
                         with_alpha(color, 0.22))
        canvas.text(bx, by, badge, argb(color) if active else argb(TEXT_DIM))
        row_y += CATEGORY_ROW_HEIGHT + CATEGORY_GAP

    # Список модулей
    list_x = x + PADDING + CATEGORY_WIDTH + PADDING
    list_y = y + HEADER_HEIGHT + PADDING
    list_w = GUI_WIDTH - CATEGORY_WIDTH - PADDING * 3
    list_h = GUI_HEIGHT - HEADER_HEIGHT - PADDING * 2 - FOOTER_HEIGHT
    canvas.rrect(list_x, list_y, list_w, list_h, 6, argb(LIST_BACKGROUND))

    row_y = list_y
    for module_id, name, description, enabled, bind, expanded in RENDER_MODULES:
        draw_module_row(canvas, list_x, row_y, list_w, module_id, name, description, enabled, accent,
                        1.0 if enabled else 0.0, expanded, None if bind == "—" else bind)
        row_y += MODULE_ROW_HEIGHT
        if expanded:
            row_y = draw_settings(canvas, list_x + 10, row_y, list_w - 20, accent, TRAILS_SETTINGS)

    if ripple:
        draw_ripple(canvas, list_x + 120, list_y + 17, 46, accent, 0.75)

    canvas.rrect(list_x + list_w - 5, list_y + 3, 3, list_h - 6, 1, argb(0x1AFFFFFF))
    canvas.rrect(list_x + list_w - 5, list_y + 3, 3, 84, 1, with_alpha(accent, 0.9))

    # Подсказки + счётчик активных
    canvas.text(x + PADDING, y + GUI_HEIGHT - PADDING - LINE_HEIGHT + 1,
                "ЛКМ вкл · ПКМ настройки · СКМ бинд · колесо ±1", argb(TEXT_DIM))
    status = "7 / 17 активно"
    canvas.text(x + GUI_WIDTH - PADDING - canvas.text_width(status),
                y + GUI_HEIGHT - PADDING - LINE_HEIGHT + 1, status, with_alpha(accent, 0.95))

    canvas.save(path)


# ---------- Главное меню ----------

def render_menu(path):
    """Главное меню: шейдерный фон + дрейф, логотип Dreamcast, колонка кнопок."""
    bg_path = os.path.join(OUT_DIR, "mainmenu-background-source.png")
    W, H = 640, 360
    canvas = Canvas(W, H, argb(0xFF0A0A0C))
    if os.path.exists(bg_path):
        bg = Image.open(bg_path).convert("RGBA")
        bg = bg.resize((W * SCALE, H * SCALE))
        canvas.img.paste(bg, (0, 0))
    overlay = Image.new("RGBA", (canvas.w, canvas.h), (0, 0, 0, 0))
    od = ImageDraw.Draw(overlay)
    od.rectangle([0, 0, canvas.w, canvas.h], fill=(5, 5, 6, 172))
    canvas.img.alpha_composite(overlay)

    logo = LOGO
    logo_w = tracked_width(canvas, logo, 6)
    logo_y = H // 4 - 22
    logo_x = W // 2 - logo_w // 2
    draw_tracked(canvas, logo_x, logo_y, logo, argb(0xFFF4F4FA), 6)

    # «Дышащая» линия под логотипом: градиент фиолетовый → циан
    bar_w = int(logo_w * 0.85)
    for i in range(bar_w):
        t = i / max(1, bar_w - 1)
        canvas.rect(logo_x + i, logo_y + LINE_HEIGHT + 4, 1, 1,
                    with_alpha(mix(argb(ACCENT_VIOLET), argb(ACCENT_CYAN), t), 0.8))
    tag = "клиент для Minecraft 26.2 · Fabric"
    canvas.text(W // 2 - canvas.text_width(tag) // 2, logo_y + LINE_HEIGHT + 10, tag, argb(0xFF9E9EAE))

    buttons = [
        ("Одиночная игра", "миры и сохранения"),
        ("Сетевая игра", "серверы и версии"),
        ("Настройки", "игра · видео · клавиши"),
        ("ClickGUI", "модули"),
        ("Telegram", "@inkviz01"),
        ("Выход", ""),
    ]
    bw, bh, gap = 192, 22, 6
    total = len(buttons) * bh + (len(buttons) - 1) * gap
    bx = W // 2 - bw // 2
    by = H - total - 34
    hover_i = 1
    for i, (label, hint) in enumerate(buttons):
        accent = mix(argb(ACCENT_VIOLET), argb(ACCENT_CYAN), i / 5.0)
        hov = 0.75 if i == hover_i else 0.0
        canvas.rrect(bx, by, bw, bh, 8, argb(0x2AFFFFFF) if hov == 0 else argb(0x66FFFFFF))
        canvas.rrect(bx, by, bw, bh, 8, argb(0xF4121215) if hov == 0 else argb(0xF61C1C22))
        if hov:
            canvas.rect(bx + 8, by, bw - 16, 1, with_alpha(accent, 0.9))
            canvas.rect(bx, by + 3, 2, bh - 6, accent)
        canvas.text(bx + 12, by + (bh - LINE_HEIGHT) // 2, label, argb(0xFFFFFFFF if hov else 0xFFE8E8F0))
        if hint:
            canvas.text(bx + bw - 12 - canvas.text_width(hint), by + (bh - LINE_HEIGHT) // 2,
                        hint, with_alpha(0xFFA6A6B2, 0.9))
        by += bh + gap
    canvas.text(6, H - 10, "Dreamcast " + VERSION + "   ·   Minecraft 26.2", argb(0xFF80808C))
    build = "build 26.2"
    canvas.text(W - 6 - canvas.text_width(build), H - 10, build, argb(0xFF80808C))
    canvas.save(path)


def tracked_width(canvas, text, tracking):
    width = 0
    for symbol in text:
        width += canvas.text_width(symbol) + tracking
    return width - tracking


def draw_tracked(canvas, x, y, text, color, tracking):
    cursor = x
    for symbol in text:
        canvas.text(cursor, y, symbol, color)
        cursor += canvas.text_width(symbol) + tracking


# ---------- Экран миров ----------

def render_worlds(path):
    """DreamcastWorldsScreen: список миров с иконками и чипами действий."""
    W, H = 640, 400
    canvas = Canvas(W, H, argb(0xFF0B0B0D))
    canvas.vgradient(0, 0, W, H, argb(0xFF131317), argb(0xFF070709))
    accent = 0xFF7C6CFF

    canvas.text(W // 2 - canvas.text_width("Одиночная игра") // 2, 18, "Одиночная игра", argb(0xFFF4F4FA))
    sub = "5 мир(ов) · двойной клик — играть"
    canvas.text(W // 2 - canvas.text_width(sub) // 2, 30, sub, argb(0xFF9E9EAE))

    pw = 420
    x = W // 2 - pw // 2
    y0 = 52
    rh, gap = 40, 4
    worlds = [
        ("Выживание база", "Выживание · Обычный", "12 мая 2026 · 21:14", "экспер.", 0xFFFFC66C, True),
        ("Хардкор острова", "Хардкор · Обычный", "3 мая 2026 · 18:02", "", 0, True),
        ("Креатив город", "Творческий · Плоский", "27 апр 2026 · 12:40", "", 0, True),
        ("Спидран 1.8", "Выживание · Большой", "19 апр 2026 · 09:55", "другая версия", 0xFFFF8095, False),
        ("Тест снапшота", "Выживание · Амплитуда", "2 апр 2026 · 22:31", "", 0, False),
    ]
    list_h = len(worlds) * (rh + gap) - gap
    draw_soft_shadow(canvas, x - 8, y0 - 8, pw + 16, list_h + 16, 10, 4)
    canvas.rrect(x - 8, y0 - 8, pw + 16, list_h + 16, 10, argb(0x26FFFFFF))
    canvas.rrect(x - 7, y0 - 7, pw + 14, list_h + 14, 9, argb(0xF4121215))
    canvas.rect(x + 0, y0 - 8, pw + 1, 1, with_alpha(accent, 0.9))

    y = y0
    for index, (name, info, date, tag, tag_color, _) in enumerate(worlds):
        selected_row = index == 0
        background = mix(argb(0xCC101015), with_alpha(accent, 0xFF), 0.18 if selected_row else 0.0)
        border = with_alpha(accent, 0.75 if selected_row else 0.14)
        canvas.rrect(x, y, pw, rh, 6, border)
        canvas.rrect(x + 1, y + 1, pw - 2, rh - 2, 5, background)

        # Иконка мира 32×32 — зелёная трава с солнцем
        ix, iy = x + 5, y + (rh - 32) // 2
        canvas.rect(ix - 1, iy - 1, 34, 34, argb(0x40000000))
        canvas.vgradient(ix, iy, 32, 14, argb(0xFF74A9DE), argb(0xFFC3DDF2))
        canvas.vgradient(ix, iy + 14, 32, 18, argb(0xFF4C7A38), argb(0xFF22401C))
        canvas.circle(ix + 24, iy + 7, 3, argb(0xFFF7E28B))

        canvas.text(ix + 36, y + 5, name, argb(0xFFFFFFFF if selected_row else 0xFFE8E8F0))
        canvas.text(ix + 36, y + 16, info, argb(0xFFA6A6B2))
        canvas.text(ix + 36, y + 27, date, argb(0xFF6B6B78))
        if tag:
            canvas.text(x + pw - 8 - canvas.text_width(tag), y + 5, tag, argb(tag_color))
        y += rh + gap

    # Чипы действий
    chips = [("Играть", True, False), ("Создать", True, False), ("Изменить", True, False),
             ("Удалить", True, True), ("Назад", True, False)]
    widths = [max(46, canvas.text_width(c[0]) + 18) for c in chips]
    total = sum(widths) + 5 * (len(chips) - 1)
    cx = W // 2 - total // 2
    cy = H - 44
    for (label, enabled, danger), width in zip(chips, widths):
        color = 0xFFFF5C7A if danger else accent
        canvas.rrect(cx, cy, width, 20, 6, argb(color))
        canvas.rrect(cx + 1, cy + 1, width - 2, 18, 5, with_alpha(0xD90F0F13, 0xFF) if not danger else argb(0xE6160A0E))
        canvas.text(cx + (width - canvas.text_width(label)) // 2, cy + 6, label, argb(0xFFE8E8F0))
        cx += width + 5
    canvas.save(path)


# ---------- Экран серверов ----------

def render_servers(path):
    """DreamcastServersScreen: серверы с пингами и пилюлей версии ViaFabricPlus."""
    W, H = 640, 400
    canvas = Canvas(W, H, argb(0xFF0B0B0D))
    canvas.vgradient(0, 0, W, H, argb(0xFF101318), argb(0xFF06080A))
    accent = 0xFF45E3FF

    canvas.text(W // 2 - canvas.text_width("Сетевая игра") // 2, 18, "Сетевая игра", argb(0xFFF4F4FA))
    sub = "4 сервер(ов) · двойной клик — подключиться"
    canvas.text(W // 2 - canvas.text_width(sub) // 2, 30, sub, argb(0xFF9E9EAE))

    # Пилюля версии — правый верхний угол
    pill_label = "◆ Auto Detect (1.7+ servers)"
    pw_pill = min(150, canvas.text_width(pill_label) + 20)
    px, py = W - pw_pill - 6, 6
    pill_color = 0xFF8DE06C
    canvas.rrect(px, py, pw_pill, 16, 8, with_alpha(pill_color, 0.55))
    canvas.rrect(px + 1, py + 1, pw_pill - 2, 14, 7, mix(argb(0xD90C0C10), argb(pill_color), 0.14))
    shown = pill_label
    if canvas.text_width(shown) > pw_pill - 10:
        shown = shown[:pw_pill // 6] + "…"
    canvas.text(px + (pw_pill - canvas.text_width(shown)) // 2, py + 4, shown, argb(0xFFB4E39B))

    pw = 420
    x = W // 2 - pw // 2
    y0 = 52
    rh, gap = 36, 4
    servers = [
        ("Example Anarchy", "A Minecraft Server", "127/300", 32, True),
        ("РуСерв Выживание", "Выживание без вайпов · 1.21+", "42/120", 88, True),
        ("Hypothetical Net", "не отвечает", "", 0, False),
        ("Local Test", "проверка…", "", 0, None),
    ]
    list_h = len(servers) * (rh + gap) - gap
    draw_soft_shadow(canvas, x - 8, y0 - 8, pw + 16, list_h + 16, 10, 4)
    canvas.rrect(x - 8, y0 - 8, pw + 16, list_h + 16, 10, argb(0x26FFFFFF))
    canvas.rrect(x - 7, y0 - 7, pw + 14, list_h + 14, 9, argb(0xF4121215))
    canvas.rect(x, y0 - 8, pw + 1, 1, with_alpha(accent, 0.9))

    y = y0
    for index, (name, motd, players, ping, ok) in enumerate(servers):
        selected_row = index == 0
        background = mix(argb(0xCC101015), with_alpha(accent, 0xFF), 0.18 if selected_row else 0.0)
        border = with_alpha(accent, 0.75 if selected_row else 0.14)
        canvas.rrect(x, y, pw, rh, 6, border)
        canvas.rrect(x + 1, y + 1, pw - 2, rh - 2, 5, background)

        # Иконка сервера 24×24
        ix, iy = x + 5, y + (rh - 24) // 2
        canvas.rect(ix - 1, iy - 1, 26, 26, argb(0x40000000))
        canvas.rrect(ix, iy, 24, 24, 4, argb(0xFF2B2B33))
        canvas.rrect(ix + 4, iy + 4, 16, 6, 2, with_alpha(accent, 0.8))
        canvas.rrect(ix + 4, iy + 13, 10, 6, 2, argb(0x59FFFFFF))

        canvas.text(ix + 28, y + 5, name, argb(0xFFFFFFFF if selected_row else 0xFFE8E8F0))
        if ok is None:
            canvas.text(ix + 28, y + 17, motd, argb(0xFFFFC66C))
        elif ok:
            canvas.text(ix + 28, y + 17, motd, argb(0xFFA6A6B2))
        else:
            canvas.text(ix + 28, y + 17, motd, argb(0xFFFF8095))
        if players:
            canvas.text(x + pw - 8 - canvas.text_width(players), y + 5, players, argb(0xFF8DE06C))
        if ping > 0:
            ping_text = str(ping) + " мс"
            ping_color = 0xFF8DE06C if ping < 100 else 0xFFFFC66C
            canvas.text(x + pw - 8 - canvas.text_width(ping_text), y + 17, ping_text, argb(ping_color))
        y += rh + gap

    chips = [("Играть", False), ("Добавить", False), ("Изменить", False), ("Адрес", False),
             ("Удалить", True), ("Обновить", False), ("Назад", False)]
    widths = [max(46, canvas.text_width(c[0]) + 18) for c in chips]
    total = sum(widths) + 5 * (len(chips) - 1)
    cx = W // 2 - total // 2
    cy = H - 44
    for (label, danger), width in zip(chips, widths):
        color = 0xFFFF5C7A if danger else accent
        canvas.rrect(cx, cy, width, 20, 6, argb(color))
        canvas.rrect(cx + 1, cy + 1, width - 2, 18, 5, argb(0xE6160A0E) if danger else argb(0xD90F0F13))
        canvas.text(cx + (width - canvas.text_width(label)) // 2, cy + 6, label, argb(0xFFE8E8F0))
        cx += width + 5
    canvas.save(path)


# ---------- Trails + ESP (в игре) ----------

def render_trails_esp(path):
    """Мокап от первого лица: светящийся след Trails за спиной и ESP-боксы."""
    W, H = 640, 400
    canvas = Canvas(W, H, argb(0xFF000000))

    # Мир: закат, холмы, туман
    canvas.vgradient(0, 0, W, 190, argb(0xFF31415F), argb(0xFF7B6A8E))
    canvas.vgradient(0, 190, W, 90, argb(0xFF3F4B46), argb(0xFF20262B))
    canvas.vgradient(0, 280, W, 120, argb(0xFF24262B), argb(0xFF121317))
    canvas.circle(500, 120, 26, argb(0x66F2C14E))
    canvas.circle(500, 120, 16, argb(0xCCF7DE9B))
    for i in range(-2, 12):
        hx = i * 70
        canvas.rrect(hx, 170 + (i % 3) * 14, 70, 80, 20, argb(0x33222C33))

    # След Trails: плавная кривая от игрока (низ центра) назад с градиентом циан→фиолет
    points = []
    cx, cy = W * 0.52, H * 0.66
    for i in range(46):
        t = i / 45.0
        px = cx + t * 240 + math.sin(t * 7.5) * 26
        py = cy - t * 34 + math.sin(t * 5.2) * 10
        points.append((px, py, t))
    # Свечение (широкий полупрозрачный проход)
    for i in range(len(points) - 1):
        x0, y0, t0 = points[i]
        x1, y1, t1 = points[i + 1]
        color = mix(argb(ACCENT_CYAN), argb(ACCENT_VIOLET), t0)
        canvas.line(x0, y0, x1, y1, 8, with_alpha(color, 0.16 * (1.0 - t0 * 0.7)))
    # Сердцевина
    for i in range(len(points) - 1):
        x0, y0, t0 = points[i]
        x1, y1, t1 = points[i + 1]
        color = mix(argb(ACCENT_CYAN), argb(ACCENT_VIOLET), t0)
        canvas.line(x0, y0, x1, y1, 3, with_alpha(color, 0.9 * (1.0 - t0 * 0.75)))
    # Партиклы-искры вдоль следа
    import random
    random.seed(7)
    for i, (px, py, t) in enumerate(points):
        if i % 4 == 0:
            ox = random.uniform(-4, 4)
            oy = random.uniform(-4, 4)
            color = hsb(0.52 + t * 0.14, 0.75, 1.0)
            canvas.circle(px + ox, py + oy, 1.6, with_alpha(color, 0.85 * (1 - t * 0.7)))

    # ESP: боксы вокруг двух «мобов» впереди
    def esp_box(x, y, w, h, top_color, bottom_color, label):
        # Градиент по высоте: 4 линии на грань
        def color_at(fy):
            return mix(top_color, bottom_color, fy)
        edges = [
            ((x, y), (x + w, y), 0.0, 0.0),
            ((x + w, y), (x + w, y + h), 0.0, 1.0),
            ((x + w, y + h), (x, y + h), 1.0, 1.0),
            ((x, y + h), (x, y), 1.0, 0.0),
        ]
        # мягкое свечение
        for (ax, ay), (bx, by), fa, fb in edges:
            canvas.line(ax, ay, bx, by, 5, with_alpha(color_at(fa), 0.15))
        for (ax, ay), (bx, by), fa, fb in edges:
            canvas.line(ax, ay, bx, by, 2, color_at(fa))
        canvas.text(x, y - 11, label, with_alpha(color_at(0.2), 0.95))

    # «Зомби» справа и «игрок» слева (силуэты)
    def mob(cx, base_y, color_body):
        canvas.rrect(cx - 7, base_y - 26, 14, 26, 3, argb(color_body))       # тело
        canvas.rrect(cx - 5, base_y - 40, 10, 10, 2, argb(0xFF6E8A5A))       # голова
        canvas.rrect(cx - 10, base_y - 24, 3, 18, 1, argb(color_body))       # руки
        canvas.rrect(cx + 7, base_y - 24, 3, 18, 1, argb(color_body))

    mob(W * 0.24, 300, 0xFF3E6B4E)
    esp_box(W * 0.24 - 16, 300 - 44, 32, 44, argb(0xFF7C6CFF), argb(0xFF45E3FF), "Zombie · 18 HP")
    mob(W * 0.78, 276, 0xFF4E5A78)
    esp_box(W * 0.78 - 16, 276 - 44, 32, 44, argb(0xFF7C6CFF), argb(0xFF45E3FF), "Player · 36 HP")

    # Glow-ободок вокруг «игрока» (режим Glow)
    esp_box(W * 0.78 - 19, 276 - 47, 38, 50, with_alpha(argb(0xFF45E3FF), 0.35), with_alpha(argb(0xFF7C6CFF), 0.35), "")

    # Подпись модулей в углу — как список активных модулей HUD
    y = 6
    for name, hue in [("Trails", 0.52), ("ESP", 0.63), ("FreeCam", 0.44)]:
        canvas.text(6, y, name, hsb(hue, 0.75, 1.0))
        y += LINE_HEIGHT + 2

    canvas.text(W - 6 - canvas.text_width("Trails ◆ Вместе · ESP ◆ Вместе"), H - 14,
                "Trails ◆ Вместе · ESP ◆ Вместе", argb(0xFFA6A6B2))
    canvas.save(path)


# ---------- HUD ----------

def draw_media_card(canvas, screen_w, screen_h, track, playing=True):
    accent = 0xFF45E3FF
    w, h = 150, 34 + LINE_HEIGHT
    x, y = screen_w - w - 6, screen_h - h - 6
    canvas.rrect(x, y, w, h, 6, argb(0xD2080809))
    canvas.rrect(x - 1, y - 1, w + 2, h + 2, 7, argb(0x2AFFFFFF))
    icon = "▶" if playing else "❚❚"
    canvas.text(x + 8, y + 7, icon, with_alpha(accent, 0.95 if playing else 0.5))
    canvas.text(x + 20, y + 7, track, argb(0xFFEDEDF5))
    bar_x, bar_y, bar_w = x + 8, y + 7 + LINE_HEIGHT + 4, w - 16
    canvas.rrect(bar_x, bar_y, bar_w, 4, 2, argb(0x33FFFFFF))
    fill = int(bar_w * 0.42)
    if fill > 4:
        canvas.rrect(bar_x, bar_y, fill, 4, 2, with_alpha(accent, 0.9))
    canvas.text(bar_x, bar_y + 7, "1:23 / 3:20", argb(0xFFA6A6B2))
    eq_x = x + w - 8 - (7 * 3 - 1)
    heights = [4, 7, 3, 9, 5, 8, 2]
    for i, bh in enumerate(heights):
        canvas.rect(eq_x + i * 3, bar_y + 9 + 6 - bh, 2, bh,
                    with_alpha(accent, 0.25 + 0.7 * (bh / 9.0)))


def render_hud(path):
    canvas = Canvas(560, 220, argb(0xFF000000))

    canvas.vgradient(0, 0, 560, 120, argb(0xFF6FA8E0), argb(0xFFB6D8F0))
    canvas.vgradient(0, 120, 560, 100, argb(0xFF3B6B2E), argb(0xFF223F1C))
    for i in range(0, 560, 32):
        canvas.rect(i, 118 + (i % 4) * 5, 31, 40, argb(0x2D5A3C30))
    canvas.rect(276, 106, 8, 8, argb(0xC8FFFFFF))

    x, y = 6, 6

    # Водяной знак: пилюля с градиентом темы (перелив по символам)
    brand = "Dreamcast " + VERSION
    pill_w = canvas.text_width(brand) + 28
    pill_h = LINE_HEIGHT + 8
    canvas.rrect(x, y, pill_w, pill_h, 5, argb(0x2AFFFFFF))
    canvas.rrect(x + 1, y + 1, pill_w - 2, pill_h - 2, 4, argb(0xE0070708))
    for i in range(pill_w - 10):
        t = i / max(1, pill_w - 11)
        canvas.rect(x + 5 + i, y, 1, 1, with_alpha(mix(argb(ACCENT_VIOLET), argb(ACCENT_CYAN), t), 0.85))
    canvas.rect(x + 6, y + pill_h // 2 - 2, 4, 4, mix(argb(ACCENT_VIOLET), argb(ACCENT_CYAN), 0.5))
    cursor = x + 15
    for index, symbol in enumerate(brand):
        t = index / max(1, len(brand) - 1)
        canvas.text(cursor, y + 4, symbol, mix(argb(ACCENT_VIOLET), argb(ACCENT_CYAN), t))
        cursor += canvas.text_width(symbol)
    y += pill_h + 5

    lines = ["FPS: 144", "XYZ: 128 64 -255", "Направление: Север (-Z)", "Пинг: 42 мс"]
    width = max(canvas.text_width(line) for line in lines) + 15
    height = len(lines) * (LINE_HEIGHT + 2) - 2 + 12

    canvas.rrect(x, y, width, height, 4, argb(0x2AFFFFFF))
    canvas.rrect(x + 1, y + 1, width - 2, height - 2, 3, argb(0xB80A0A0D))
    canvas.vgradient(x + 1, y + 3, 2, height - 6, argb(ACCENT_CYAN), argb(ACCENT_VIOLET))

    text_y = y + 6
    for line in lines:
        canvas.text(x + 9, text_y, line, argb(0xFFEDEDF5))
        text_y += LINE_HEIGHT + 2
    y += height + 5

    for index, module_name in enumerate(["Trails", "ESP", "AutoWalk", "FreeCam"]):
        t = index / 4.0
        canvas.text(x, y, module_name, mix(argb(ACCENT_CYAN), argb(ACCENT_VIOLET), t))
        y += LINE_HEIGHT + 2

    accent = 0xFFFFC66C
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
    canvas.rect(panel_x + panel_w - 12, panel_y + 10, 4, 4, with_alpha(accent, 0.9))

    # Элемент «Бинды»: панель справа, имя зелёное у включённых
    binds = [("X", "KillAura", True), ("R", "AutoTotem", False), ("N", "FreeCam", True), ("V", "Обводка рук", False)]
    bind_w = max(canvas.text_width(k) + 8 + canvas.text_width(n) for k, n, _ in binds) + 12
    bh = len(binds) * (LINE_HEIGHT + 3) - 3 + 12
    bx, by = 560 - bind_w - 6, 92
    canvas.rrect(bx, by, bind_w, bh, 5, argb(0x2AFFFFFF))
    canvas.rrect(bx + 1, by + 1, bind_w - 2, bh - 2, 4, argb(0xCC09090C))
    title_w = canvas.text_width("бинды") + 12
    canvas.rrect(bx + 4, by - 5, title_w, LINE_HEIGHT + 4, 4, argb(0xE60A0A0D))
    canvas.text(bx + 10, by - 4, "бинды", mix(argb(ACCENT_VIOLET), argb(ACCENT_CYAN), 0.5))
    row_y = by + 6
    for key, name, on in binds:
        key_w = canvas.text_width(key) + 8
        canvas.rrect(bx + 6, row_y - 1, key_w, LINE_HEIGHT + 2, 3, argb(0x8F7BE08A if on else 0x30FFFFFF))
        canvas.text(bx + 10, row_y, key, argb(TEXT_PRIMARY if on else 0xFFEDEDF5))
        canvas.text(bx + 6 + key_w + 6, row_y, name, argb(0xFF7BE08A) if on else argb(0xFFF6F6F8))
        row_y += LINE_HEIGHT + 3

    # Элемент «Уведомления»: стопка сверху справа, одна «уезжает»
    notes = [("Модуль", "KillAura — включён", 0xFF7BE08A, 1.0),
             ("Модуль", "FreeCam — выключен", argb(ACCENT_VIOLET), 1.0),
             ("Конфиг", "Настройки сохранены", 0xFF7BE08A, 0.65)]
    ny = 6
    for title, message, color, shown in notes:
        nw = canvas.text_width(title) + 18 + canvas.text_width(message) + 16
        nh = LINE_HEIGHT + 10
        nx = 560 - 6 - nw + round((1.0 - shown) * 8)
        canvas.rrect(nx, ny, nw, nh, 6, with_alpha(color, 0.75 * shown))
        canvas.rrect(nx + 1, ny + 1, nw - 2, nh - 2, 5, with_alpha(argb(0xE40A0A0D), shown))
        canvas.rect(nx + 2, ny + 4, 1, nh - 8, with_alpha(color, shown))
        canvas.text(nx + 9, ny + 5, title, with_alpha(color, shown))
        canvas.text(nx + 9 + canvas.text_width(title) + 5, ny + 6, message,
                    with_alpha(argb(0xFFA6A6B2), shown))
        ny += nh + 3

    draw_media_card(canvas, 560, 220, "netherlands indie.wav")

    canvas.save(path)


# ---------- Настройки ----------

def render_settings(path):
    """DreamcastSettingsScreen — список настроек чёрным стеклом."""
    W, H = 560, 330
    canvas = Canvas(W, H, argb(0xFF0B0B0D))
    canvas.vgradient(0, 0, W, H // 2, argb(0xFF16161A), argb(0xFF0A0A0C))
    accent = ACCENT_VIOLET

    title = "Настройки"
    canvas.text(W // 2 - canvas.text_width(title) // 2, 20, title, argb(0xFFF4F4FA))
    pw, rh, gap = 340, 22, 3
    x = W // 2 - pw // 2
    y0 = 52
    rows = [
        ("Дальность прорисовки", "slider", 0.61, "18 чанк."),
        ("Максимум FPS", "slider", 0.94, "240"),
        ("Mipmap", "slider", 1.0, "4 ур."),
        ("Чувствительность мыши", "slider", 0.5, "50 %"),
        ("Тени сущностей", "toggle", 1, ""),
        ("Виньетка", "toggle", 0, ""),
        ("Покачивание камеры", "toggle", 1, ""),
        ("Облака", "cycle", 0, "Облака"),
        ("Качество графики", "cycle", 0, "Детально"),
        ("Назначить клавиши", "action", 0, "→"),
    ]
    list_h = len(rows) * (rh + gap) - gap
    canvas.rrect(x - 10, y0 - 12, pw + 20, list_h + 24, 10, argb(0x26FFFFFF))
    canvas.rrect(x - 9, y0 - 11, pw + 18, list_h + 22, 9, argb(0xF4121215))
    y = y0
    for i, (label, kind, val, text) in enumerate(rows):
        if i == 3:
            canvas.rect(x - 6, y - 1, pw + 12, rh - 1, argb(0x0FFFFFFF))
        canvas.text(x + 6, y + (rh - LINE_HEIGHT) // 2, label, argb(0xFFE8E8F0))
        if kind == "toggle":
            tx, ty = x + pw - 8 - 26, y + (rh - 11) // 2
            canvas.rrect(tx, ty, 26, 11, 5, argb(0x33FFFFFF))
            knob_x = tx + 1 if not val else tx + 26 - 12
            canvas.rrect(knob_x, ty + 1, 11, 9, 4, argb(0xFFEDEDF5))
            if val:
                canvas.rrect(tx + 2, ty + 2, 22, 7, 3, with_alpha(accent, 0.45))
        elif kind == "slider":
            sw = 96
            sx = x + pw - 12 - sw - canvas.text_width(text) - 8
            sy = y + (rh - 4) // 2
            canvas.rrect(sx, sy, sw, 4, 2, argb(0x33FFFFFF))
            fill = max(4, int(sw * val))
            canvas.rrect(sx, sy, fill, 4, 2, with_alpha(accent, 0.9))
            canvas.text(sx + sw + 8, y + (rh - LINE_HEIGHT) // 2, text, argb(0xFFB9B9C6))
        else:
            canvas.text(x + pw - 10 - canvas.text_width(text), y + (rh - LINE_HEIGHT) // 2,
                        text, with_alpha(accent, 0.95) if kind == "cycle" else argb(0xFFA6A6B2))
        y += rh + gap
    # Кнопка «Готово»
    back_y = H - 34
    back_w = 150
    bx = x + (pw - back_w) // 2
    draw_soft_shadow(canvas, bx, back_y, back_w, 20, 8, 4)
    canvas.rrect(bx, back_y, back_w, 20, 8, argb(0x66FFFFFF))
    canvas.rrect(bx, back_y, back_w, 20, 8, argb(0xF61C1C22))
    canvas.rect(bx + 8, back_y, back_w - 16, 1, with_alpha(accent, 0.9))
    canvas.text(bx + (back_w - canvas.text_width("Готово")) // 2, back_y + 6, "Готово", argb(0xFFE8E8F0))
    canvas.save(path)


# ---------- AutoTotem ----------

def render_totem(path):
    """AutoTotem: момент «предсказал смэш — надел тотем»."""
    W, H = 560, 250
    canvas = Canvas(W, H, argb(0xFF000000))
    canvas.vgradient(0, 0, W, 150, argb(0xFF20242E), argb(0xFF0E1014))
    canvas.vgradient(0, 150, W, 100, argb(0xFF27351F), argb(0xFF141B10))

    accent = 0xFFFF5C7A

    pw, x, y = 240, 16, 16
    rows = [
        ("Режим", "legit"),
        ("Ставить при HP ≤", "6"),
        ("Предсказывать урон", "toggle1"),
        ("Окно предсказания", "8 тиков"),
        ("Дальность угрозы", "16"),
        ("Краш: пикирующая булава", "toggle1"),
        ("Снайпер: снаряды", "toggle1"),
        ("Снимать после", "60 тиков"),
    ]
    ph = 26 + len(rows) * 17 + 10
    canvas.rrect(x - 1, y - 1, pw + 2, ph + 2, 11, argb(0x2AFFFFFF))
    canvas.rrect(x, y, pw, ph, 10, argb(0xF6101013))
    canvas.rect(x + 8, y, pw - 16, 1, with_alpha(accent, 0.9))
    canvas.text(x + 10, y + 7, "AutoTotem", argb(0xFFFFFFFF))
    canvas.rrect(x + pw - 34, y + 6, 24, 10, 5, with_alpha(accent, 0.9))
    canvas.rrect(x + pw - 23, y + 7, 12, 8, 4, argb(0xFFFFFFFF))
    ry = y + 26
    for label, val in rows:
        canvas.text(x + 10, ry + 4, label, argb(0xFFD6D6DE))
        if val.startswith("toggle"):
            tx = x + pw - 8 - 24
            canvas.rrect(tx, ry + 3, 24, 10, 5, argb(0x33FFFFFF))
            canvas.rrect(tx + 1, ry + 4, 11, 8, 4, argb(0xFFEDEDF5))
            canvas.rrect(tx + 12, ry + 3, 11, 10, 5, with_alpha(accent, 0.9))
        else:
            canvas.text(x + pw - 10 - canvas.text_width(val), ry + 4, val, with_alpha(accent, 0.95))
        ry += 17

    hx, hy = W - 240, 16
    status = "угроза: mace ≈ 17 HP"
    sw = canvas.text_width(status) + 20
    canvas.rrect(hx, hy, sw, 22, 6, argb(0xE6070708))
    canvas.rect(hx, hy, sw, 1, with_alpha(accent, 0.9))
    canvas.rect(hx + 6, hy + 7, 8, 8, with_alpha(accent, 1.0))
    canvas.text(hx + 20, hy + 7, status, argb(0xFFFFD7DD))
    canvas.save(path)


def render_media(path):
    """MediaPlayer: карточка на HUD."""
    W, H = 560, 230
    canvas = Canvas(W, H, argb(0xFF000000))
    canvas.vgradient(0, 0, W, 130, argb(0xFF101418), argb(0xFF05060A))
    draw_media_card(canvas, W, H, "netherlands indie.wav")
    canvas.save(path)


def render_hands(path):
    """Редактор раскладки рук: мир, рука с обводкой и панель параметров справа."""
    width, height = 560, 320
    canvas = Canvas(width, height, argb(0xFF101018))

    canvas.vgradient(0, 0, width, 190, argb(0xFF74A9DE), argb(0xFFC3DDF2))
    canvas.vgradient(0, 190, width, height - 190, argb(0xFF4C7A38), argb(0xFF22401C))
    for i in range(0, width, 32):
        canvas.rect(i, 188 + (i % 4) * 5, 31, 40, argb(0x2D5A3C30))
    canvas.rect(width // 2 - 4, 176, 8, 8, argb(0xC8FFFFFF))

    accent = 0xFF7C6CFF

    hand_x, hand_y, hand_w, hand_h = 300, 214, 62, 120
    item_x, item_y, item_w, item_h = 250, 150, 22, 96

    def hand_shape(grow):
        canvas.rrect(hand_x - grow, hand_y - grow, hand_w + grow * 2, hand_h + grow * 2,
                     10 + grow, with_alpha(accent, 0.85))
        canvas.rrect(item_x - grow, item_y - grow, item_w + grow * 2, item_h + grow * 2,
                     5 + grow, with_alpha(accent, 0.85))

    for grow in (4, 3, 2, 1):
        hand_shape(grow)

    canvas.rrect(item_x, item_y, item_w, item_h, 5, argb(0xFFB9B9C4))
    canvas.rrect(item_x + 3, item_y + 3, item_w - 6, item_h - 40, 3, argb(0xFF6E6E7A))
    canvas.rrect(item_x - 6, item_y + item_h - 26, item_w + 12, 9, 3, argb(0xFF8A5A2B))

    canvas.rrect(hand_x, hand_y, hand_w, hand_h, 10, argb(0xFFD9A87C))
    canvas.rrect(hand_x + 6, hand_y + 8, hand_w - 12, hand_h - 16, 8, argb(0xFFE5BC95))
    canvas.rrect(hand_x, hand_y + hand_h - 26, hand_w, 26, 10, argb(0xFFC89468))

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


if __name__ == "__main__":
    render_menu(os.path.join(OUT_DIR, "preview-mainmenu.png"))
    render_clickgui(os.path.join(OUT_DIR, "preview-clickgui.png"))
    render_worlds(os.path.join(OUT_DIR, "preview-worlds.png"))
    render_servers(os.path.join(OUT_DIR, "preview-servers.png"))
    render_trails_esp(os.path.join(OUT_DIR, "preview-trails-esp.png"))
    render_hud(os.path.join(OUT_DIR, "preview-hud.png"))
    render_settings(os.path.join(OUT_DIR, "preview-settings.png"))
    render_totem(os.path.join(OUT_DIR, "preview-autototem.png"))
    render_media(os.path.join(OUT_DIR, "preview-media.png"))
    render_hands(os.path.join(OUT_DIR, "preview-hands.png"))
    print("Мокапы сохранены:", os.path.abspath(OUT_DIR))
