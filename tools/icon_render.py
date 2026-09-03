"""
Генератор лёгких иконок модулей и стрелок для списков (assets/akarus/textures/gui/icons/).

Иконки белые монохромные: в игре они тонируются цветом через blit(..., color).
Рисуем в 4x и уменьшаем — получаются гладкие линии при крошечном размере файла.

Запуск:  python3 tools/icon_render.py
"""

import os

from PIL import Image, ImageDraw

SIZE = 40          # логический размер иконки
SS = 4             # суперсэмплинг
STROKE = 3.4       # толщина линии в логических пикселях
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                   "src", "main", "resources", "assets", "akarus", "textures", "gui", "icons")

WHITE = (255, 255, 255, 255)


class Icon:
    def __init__(self):
        self.img = Image.new("RGBA", (SIZE * SS, SIZE * SS), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    # координаты логические → физические
    def _r(self, box):
        return [v * SS for v in box]

    def line(self, x0, y0, x1, y1, width=STROKE, color=WHITE):
        self.d.line([x0 * SS, y0 * SS, x1 * SS, y1 * SS], fill=color, width=int(width * SS))

    def arc(self, box, start, end, width=STROKE, color=WHITE):
        self.d.arc(self._r(box), start, end, fill=color, width=int(width * SS))

    def ellipse(self, box, width=STROKE, color=WHITE, fill=None):
        self.d.ellipse(self._r(box), outline=color, width=int(width * SS),
                       fill=fill)

    def rrect(self, box, radius, width=STROKE, color=WHITE, fill=None):
        self.d.rounded_rectangle(self._r(box), radius=radius * SS, outline=color,
                                 width=int(width * SS), fill=fill)

    def rect(self, box, width=STROKE, color=WHITE, fill=None):
        self.d.rectangle(self._r(box), outline=color, width=int(width * SS), fill=fill)

    def dot(self, cx, cy, r, color=WHITE):
        self.d.ellipse(self._r([cx - r, cy - r, cx + r, cy + r]), fill=color)

    def save(self, name):
        os.makedirs(OUT, exist_ok=True)
        img = self.img.resize((SIZE, SIZE), Image.LANCZOS)
        path = os.path.join(OUT, name + ".png")
        img.save(path, optimize=True)
        return path


def icon_hud_info():
    # список строк с маркерами — «инфопанель»
    icon = Icon()
    for row, width in ((8, 22), (17, 28), (26, 16)):
        icon.dot(7, row + 3, 1.8)
        icon.line(13, row + 3, 13 + width, row + 3, 3.0)
    return icon


def icon_freecam():
    # видеокамера: корпус + объектив-клин
    icon = Icon()
    icon.rrect([5, 12, 24, 28], 4)
    icon.line(24, 18, 34, 12)
    icon.line(24, 22, 34, 28)
    icon.line(24, 18, 34, 28) if False else None
    return icon


def icon_freelook():
    # глаз со стрелками-орбитами — «осмотр вокруг»
    icon = Icon()
    icon.ellipse([5, 14, 35, 26], 3.0)
    icon.dot(20, 20, 4.2)
    icon.arc([2, 4, 38, 30], 205, 265, 2.6)
    icon.arc([2, 10, 38, 36], 25, 85, 2.6)
    return icon


def icon_auto_mine():
    # кирка: дуга-голова + диагональная рукоять
    icon = Icon()
    icon.arc([6, 4, 34, 26], 200, 340, 3.2)
    icon.line(11, 19, 31, 35, 3.2)
    return icon


def icon_auto_walk():
    # пунктирный маршрут со стрелкой к цели
    icon = Icon()
    icon.dot(7, 33, 2.2)
    icon.dot(13, 27, 2.0)
    icon.dot(19, 21, 2.0)
    icon.line(24, 15, 33, 6, 3.2)
    icon.line(33, 6, 25, 6, 3.0)
    icon.line(33, 6, 33, 14, 3.0)
    return icon


def icon_kill_aura():
    # прицел: круг + крест с разрывом в центре
    icon = Icon()
    icon.ellipse([7, 7, 33, 33], 3.0)
    for x0, y0, x1, y1 in ((20, 3, 20, 12), (20, 28, 20, 37), (3, 20, 12, 20), (28, 20, 37, 20)):
        icon.line(x0, y0, x1, y1, 3.0)
    icon.dot(20, 20, 2.2)
    return icon


def icon_sprint():
    # двойной шеврон вперёд
    icon = Icon()
    for shift in (0, 11):
        icon.line(10 + shift, 8, 20 + shift, 20, 3.6)
        icon.line(10 + shift, 32, 20 + shift, 20, 3.6)
    return icon


def icon_no_fov():
    # объектив: два круга + прямоугольник кадра
    icon = Icon()
    icon.rect([5, 10, 35, 30], 3.0)
    icon.ellipse([13, 13, 27, 27], 3.0)
    icon.dot(20, 20, 2.0)
    return icon


def icon_no_blind():
    # перечёркнутый глаз
    icon = Icon()
    icon.ellipse([5, 13, 35, 27], 3.0)
    icon.dot(20, 20, 4.0)
    icon.line(9, 33, 31, 7, 3.4)
    return icon


def icon_no_fall_damage():
    # стрелка вниз над волной — «падение в воду»
    icon = Icon()
    icon.line(20, 5, 20, 22, 3.2)
    icon.line(13, 15, 20, 23, 3.0)
    icon.line(27, 15, 20, 23, 3.0)
    icon.arc([6, 22, 22, 34], 180, 300, 3.0)
    icon.arc([20, 22, 36, 34], 240, 360, 3.0)
    return icon


def icon_hand_shader():
    # ладонь-варежка с дугами свечения
    icon = Icon()
    icon.rrect([12, 14, 28, 34], 6)
    icon.line(16, 14, 16, 8, 3.0)
    icon.line(20, 14, 20, 6, 3.0)
    icon.line(24, 14, 24, 8, 3.0)
    icon.arc([6, 8, 36, 36], 190, 250, 2.6)
    icon.arc([6, 8, 36, 36], 290, 350, 2.6)
    return icon


def icon_view_model():
    # рука + стрелки сдвига по осям
    icon = Icon()
    icon.rrect([8, 16, 22, 32], 4)
    icon.line(30, 8, 30, 22, 3.0)
    icon.line(26, 12, 30, 8, 2.8)
    icon.line(34, 12, 30, 8, 2.8)
    icon.line(26, 30, 34, 30, 3.0)
    icon.line(26, 34, 34, 34, 3.0)
    return icon


def icon_auto_totem():
    # тотем: тельце, глаза, крылышки
    icon = Icon()
    icon.rrect([15, 10, 25, 34], 3)
    icon.dot(18.5, 16, 1.6)
    icon.dot(21.5, 16, 1.6)
    icon.line(15, 22, 8, 18, 2.6)
    icon.line(15, 26, 8, 26, 2.6)
    icon.line(25, 22, 32, 18, 2.6)
    icon.line(25, 26, 32, 26, 2.6)
    return icon


def icon_media_player():
    # нота
    icon = Icon()
    icon.ellipse([8, 26, 20, 36], 3.2)
    icon.line(19, 31, 19, 7, 3.2)
    icon.line(19, 7, 32, 12, 3.2)
    icon.line(32, 12, 32, 27, 3.0)
    icon.ellipse([27, 22, 35, 30], 3.0)
    return icon


def icon_trails():
    # затухающая волнистая лента с искрой
    icon = Icon()
    icon.line(5, 30, 12, 24, 2.4)
    icon.line(12, 24, 18, 28, 3.0)
    icon.line(18, 28, 25, 18, 3.6)
    icon.line(25, 18, 34, 10, 4.2)
    icon.dot(31, 20, 1.8)
    icon.dot(27, 26, 1.5)
    return icon


def icon_esp():
    # пунктирная рамка + точка
    icon = Icon()
    for x0, y0, x1, y1 in ((7, 7, 16, 7), (24, 7, 33, 7),
                           (7, 33, 16, 33), (24, 33, 33, 33),
                           (7, 7, 7, 16), (7, 24, 7, 33),
                           (33, 7, 33, 16), (33, 24, 33, 33)):
        icon.line(x0, y0, x1, y1, 3.2)
    icon.ellipse([16, 16, 24, 24], 2.6)
    icon.dot(20, 20, 1.8)
    return icon


def icon_click_gui():
    # окно меню: заголовок + строки-настройки
    icon = Icon()
    icon.rrect([6, 7, 34, 33], 3)
    icon.line(6, 14, 34, 14, 2.8)
    icon.dot(11, 10.5, 1.6)
    for y in (19, 24, 29):
        icon.line(10, y, 22, y, 2.6)
        icon.dot(28, y - 0.5, 1.7)
    return icon


def icon_target_esp():
    # скобки-углы + прицел + полоска здоровья
    icon = Icon()
    for x0, y0, x1, y1, x2, y2 in (
            (6, 14, 6, 6, 14, 6), (26, 6, 34, 6, 34, 14),
            (6, 26, 6, 34, 14, 34), (34, 26, 34, 34, 26, 34)):
        icon.line(x0, y0, x1, y1, 3.0)
        icon.line(x1, y1, x2, y2, 3.0)
    icon.ellipse([15, 15, 25, 25], 2.6)
    icon.dot(20, 20, 1.8)
    icon.line(8, 37, 30, 37, 3.0)
    return icon


def icon_arrow_down():
    # стрела вниз: «список свёрнут»
    icon = Icon()
    icon.line(20, 9, 20, 30, 4.0)
    icon.line(11, 22, 20, 31, 4.0)
    icon.line(29, 22, 20, 31, 4.0)
    return icon


def icon_arrow_up():
    # стрела вверх: «список раскрыт»
    icon = Icon()
    icon.line(20, 31, 20, 10, 4.0)
    icon.line(11, 18, 20, 9, 4.0)
    icon.line(29, 18, 20, 9, 4.0)
    return icon




def icon_spider():
    # паутина-лестница: диагональ со ступеньками и «капля» воды
    icon = Icon()
    icon.line(8, 32, 8, 8, 3.4)
    icon.line(8, 8, 32, 8, 3.4)
    icon.line(32, 8, 32, 32, 3.4)
    icon.line(16, 8, 16, 16, 2.6)
    icon.line(16, 16, 24, 16, 2.6)
    icon.line(24, 16, 24, 24, 2.6)
    icon.dot(24, 30, 2.2)
    return icon


def icon_block_esp():
    # куб в рамке-скобках + пик
    icon = Icon()
    icon.rrect([12, 12, 28, 28], 2, 3.0)
    icon.line(12, 12, 7, 7, 2.6)
    icon.line(28, 12, 33, 7, 2.6)
    icon.line(12, 28, 7, 33, 2.6)
    icon.line(28, 28, 33, 33, 2.6)
    icon.dot(20, 20, 2.4)
    return icon

ICONS = {
    "hud_info": icon_hud_info,
    "free_cam": icon_freecam,
    "freelook": icon_freelook,
    "auto_mine": icon_auto_mine,
    "auto_walk": icon_auto_walk,
    "kill_aura": icon_kill_aura,
    "sprint": icon_sprint,
    "no_fov": icon_no_fov,
    "no_blind": icon_no_blind,
    "no_fall_damage": icon_no_fall_damage,
    "hand_shader": icon_hand_shader,
    "view_model": icon_view_model,
    "auto_totem": icon_auto_totem,
    "media_player": icon_media_player,
    "trails": icon_trails,
    "esp": icon_esp,
    "click_gui": icon_click_gui,
    "target_esp": icon_target_esp,
    "block_esp": icon_block_esp,
    "spider": icon_spider,
    "arrow_down": icon_arrow_down,
    "arrow_up": icon_arrow_up,
}

if __name__ == "__main__":
    total = 0
    for name, factory in ICONS.items():
        path = factory().save(name)
        size = os.path.getsize(path)
        total += size
        print(f"{name}.png  {size} байт")
    print(f"Итого: {total} байт")
