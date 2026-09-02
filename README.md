# AIO Client

Стартовый каркас клиентского мода (мод-клиента) для **Minecraft 26.2** на **Fabric**.

Внутри уже есть три вещи, с которых обычно начинают такой проект:

- **ClickGUI** — собственное меню со списком модулей, категориями, тумблерами и настройками;
- **система модулей** — включение/выключение, свои клавиши, настройки, сохранение в JSON;
- **демонстрационный модуль HUD-инфо** — FPS, координаты, направление, пинг, водяной знак и список активных модулей.

![ClickGUI](docs/preview-clickgui.png)

HUD в игре выглядит так:

![HUD](docs/preview-hud.png)

> Мокапы выше сгенерированы скриптом `tools/preview_render.py` — он повторяет геометрию и цвета
> кода, но рисует их через Pillow, поэтому это «примерная» картинка, а не скриншот из игры.

## Требования

| Что | Версия |
| --- | --- |
| Minecraft (Java Edition) | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.158.0+26.2 |
| JDK | 25 |
| Gradle | 9.5.1 (через `gradlew`) |

## Сборка и запуск

```bash
./gradlew build          # собрать мод, jar появится в build/libs/
./gradlew runClient      # запустить игру с модом из папки run/
```

Готовый `aio-client-0.1.0.jar` кладём в папку `mods` клиента с Fabric Loader 0.19.3 и Fabric API.

Первый запуск скачивает Minecraft, маппинги и Fabric API — нужен интернет и минутка терпения.

## Управление

| Действие | Клавиша |
| --- | --- |
| Открыть меню клиента | **Правый Shift** |
| Включить/выключить HUD-инфо | **H** |
| В меню: переключить модуль | ЛКМ по строке |
| В меню: открыть настройки модуля | ПКМ по строке |
| В меню: прокрутка списка | колесо мыши |
| В меню: переместить окно | перетаскивание за шапку |

Клавиши меняются в стандартных настройках управления Minecraft, категория **«AIO Client»**.

## Что где лежит

```
src/main/java/com/aio/client/
├── AioClient.java               — точка входа, регистрация всего, клавиша меню
├── config/ConfigManager.java    — сохранение настроек в config/aio-client.json
├── gui/
│   ├── ClickGuiScreen.java      — окно ClickGUI (категории, модули, настройки)
│   └── hud/HudRenderer.java     — отрисовка HUD через Fabric HUD API
├── module/
│   ├── Module.java              — базовый класс модуля
│   ├── ModuleCategory.java      — категории (вкладки) модулей
│   ├── ModuleManager.java       — реестр модулей, обработка клавиш и тиков
│   └── impl/HudInfoModule.java  — демонстрационный модуль HUD-инфо
├── settings/                    — настройки модулей (пока булевы)
└── util/RenderUtils.java        — скруглённые прямоугольники, градиенты, тумблеры
```

## Как добавить свой модуль

1. Создаём класс-наследник `Module` в `module/impl`:

```java
public class ZoomModule extends Module {
    private final BooleanSetting smooth = bool("smooth", "Плавное приближение", true);

    public ZoomModule() {
        super("zoom", "Зум", "Приближает обзор при зажатой клавише",
                ModuleCategory.RENDER, GLFW.GLFW_KEY_Z);
    }

    @Override
    public void tick() {
        // логика модуля, пока он включён
    }
}
```

2. Регистрируем его в `ModuleManager.init()`:

```java
register(new HudInfoModule());
register(new ZoomModule());
```

Всё: модуль появится в меню, получит свою клавишу, настройки и запишется в конфиг.

## Планы на ближайшие шаги

- настройки-слайдеры (числа) и выбор из списка;
- привязка клавиш прямо внутри ClickGUI;
- перемещение элементов HUD мышью;
- темы оформления и свои цвета акцента;
- новые модули (рендер, движение, бой).

## Полезные ссылки

- [Fabric для Minecraft 26.2](https://fabricmc.net/2026/06/15/262.html) — что изменилось в API;
- [Fabric Docs](https://docs.fabricmc.net/develop) — официальная документация;
- [Пример мода от Fabric](https://github.com/FabricMC/fabric-example-mod) — основа `build.gradle`.

## Лицензия

CC0 1.0 Universal — делайте с кодом что угодно.
