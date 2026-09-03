# Akarus

Каркас клиентского мода (мод-клиента) **Akarus** для **Minecraft 26.2** на **Fabric**.

- **ClickGUI** — собственное меню в чёрных тонах: размытый фон, мягкая многослойная тень,
  «волна» в месте клика, плавные анимации, категории, тумблеры, слайдеры и текстовые поля;
- **система модулей** — включение/выключение, свои клавиши, настройки, сохранение в JSON;
- **модули**: HUD-инфо, **FreeCam** (реальный полёт сквозь блоки) и **AutoMine** (добыча через Baritone).

![ClickGUI](docs/preview-clickgui.png)

HUD в игре выглядит так:

![HUD](docs/preview-hud.png)

> Мокапы выше сгенерированы скриптом `tools/preview_render.py` — он повторяет геометрию и цвета
> кода, но рисует их через Pillow, поэтому это «примерная» картинка, а не скриншот из игры.

## Где взять готовый jar

1. **GitHub Actions (рекомендуется, если локально нет JDK 25 или нет доступа к Gradle):**
   готовый jar лежит в [Releases](https://github.com/inkvizrebirth/AIO-Client/releases) —
   например [Akarus v0.2.0](https://github.com/inkvizrebirth/AIO-Client/releases/tag/v0.2.0),
   файл `akarus-0.2.0.jar`.
   Либо: вкладка **Actions** → workflow **build** → **Run workflow** → после завершения
   скачать артефакт `akarus` из секции Artifacts (там же всегда лежит `akarus-build-log` с логом сборки).
2. **Локально:**

```bash
./gradlew build          # собрать мод, jar появится в build/libs/
./gradlew runClient      # запустить игру с модом из папки run/
```

Готовый jar кладём в папку `mods` клиента с Fabric Loader 0.19.3 и Fabric API.

## Требования

| Что | Версия |
| --- | --- |
| Minecraft (Java Edition) | 26.2 |
| Fabric Loader | 0.19.3 |
| Fabric API | 0.158.0+26.2 |
| JDK | **25** (иначе будет ошибка `release version 25 not supported`) |
| Gradle | 9.5.1 (через `gradlew`) |

JDK 25: [Adoptium Temurin 25](https://adoptium.net/temurin/releases/?version=25).
Первый запуск сборки скачивает Minecraft, маппинги и Fabric API — нужен интернет.

## Модули

| Модуль | Категория | Клавиша | Что делает |
| --- | --- | --- | --- |
| **HUD-инфо** | HUD | `H` | FPS, координаты, направление, пинг, водяной знак, список активных модулей |
| **FreeCam** | Движение | `N` | Полёт сквозь блоки: игрок двигается по-настоящему, можно ломать и ставить с того места, где «камера» |
| **AutoMine** | Прочее | `B` | Автоматическая добыча блоков через Baritone: настраивается **что** добывать и **сколько** |

### FreeCam

Это не классический «глаз отдельно от тела». Игрок получает `noPhysics` и режим полёта,
то есть перемещается физически: можно подлететь к стене и ломать/ставить блоки именно там,
куда смотришь.

- **Скорость** (1–20) — скорость полёта;
- **Ускорение на спринт** — при зажатом спринте скорость удваивается;
- в одиночной игре состояние синхронизируется и с серверной копией игрока, поэтому рывков нет;
- на сервере сервер может откатывать позицию — это нормально для клиентского ноклипа;
- при выключении игрок аккуратно поднимается вверх, если застрял внутри блока.

### AutoMine (Baritone)

Для работы нужен установленный **Baritone** (Fabric, версия для Minecraft 26.2, например
[baritone v1.19.0](https://github.com/cabaletta/baritone/releases) — файл `baritone-api-fabric-1.19.0.jar`
или `baritone-standalone-fabric-1.19.0.jar`).

Настройки модуля:

- **Блок** — что добывать, например `diamond_ore`, `ancient_debris`, `oak_log`;
- **Сколько** — сколько предметов получить (`0` — без ограничения);
- **Командами чата** — использовать чат-команды Baritone (`#mine 16 diamond_ore`) вместо API.
  Полезно, если API не ответил; при выключении модуля отправляется `#stop`.

Интеграция сделана через reflection (`BaritoneAPI → getPrimaryBaritone → getMineProcess → mineByName`),
поэтому Akarus собирается и работает **без** Baritone — просто AutoMine при включении скажет,
что Baritone не найден, и выключится.

## Управление

| Действие | Клавиша |
| --- | --- |
| Открыть меню клиента | **Правый Shift** |
| HUD-инфо | **H** |
| FreeCam | **N** |
| AutoMine | **B** |
| В меню: переключить модуль | ЛКМ по строке |
| В меню: открыть настройки модуля | ПКМ по строке |
| В меню: слайдер | тянуть ЛКМ |
| В меню: текстовое поле | ЛКМ — фокус, печатать, Enter/ESC — снять фокус |
| В меню: прокрутка списка | колесо мыши |
| В меню: переместить окно | перетаскивание за шапку |

Клавиши меняются в стандартных настройках управления Minecraft, категория **«Akarus»**.

## Что где лежит

```
src/main/java/com/akarus/client/
├── AkarusClient.java             — точка входа, регистрация всего, клавиша меню
├── baritone/BaritoneBridge.java  — мост к Baritone (reflection + чат-команды)
├── config/ConfigManager.java     — сохранение настроек в config/akarus.json
├── gui/
│   ├── ClickGuiScreen.java       — окно ClickGUI (категории, модули, настройки)
│   └── hud/HudRenderer.java      — отрисовка HUD через Fabric HUD API
├── module/
│   ├── Module.java               — базовый класс модуля
│   ├── ModuleCategory.java       — категории (вкладки) модулей
│   ├── ModuleManager.java        — реестр модулей, обработка клавиш и тиков
│   └── impl/                     — сами модули: HudInfo, FreeCam, AutoMine
├── settings/                     — настройки: булевы (тумблер), числа (слайдер), строки (поле)
└── util/RenderUtils.java         — скруглённые прямоугольники, тень, градиенты, волна
```

## Как добавить свой модуль

1. Создаём класс-наследник `Module` в `module/impl`:

```java
public class ZoomModule extends Module {
    private final IntSetting fov = intSetting("fov", "Сила приближения", 30, 5, 90);
    private final StringSetting mode = textSetting("mode", "Режим", "smooth");

    public ZoomModule() {
        super("zoom", "Зум", "Приближает обзор", ModuleCategory.RENDER, GLFW.GLFW_KEY_Z);
    }

    @Override
    public void tick() {
        // логика модуля, пока он включён
    }

    @Override
    public void onSettingsChanged() {
        // вызывается после изменения любой настройки в меню
    }
}
```

2. Регистрируем его в `ModuleManager.init()`:

```java
register(new HudInfoModule());
register(new FreeCamModule());
register(new AutoMineModule());
register(new ZoomModule());
```

Всё: модуль появится в меню, получит свою клавишу, настройки и запишется в конфиг.

## Планы на ближайшие шаги

- выбор блока для AutoMine списком (а не строкой) и подсказки при вводе;
- перетаскивание и масштабирование элементов HUD мышью;
- темы оформления и свои цвета акцента;
- новые модули (рендер, бой).

## Полезные ссылки

- [Fabric для Minecraft 26.2](https://fabricmc.net/2026/06/15/262.html) — что изменилось в API;
- [Fabric Docs](https://docs.fabricmc.net/develop) — официальная документация;
- [Пример мода от Fabric](https://github.com/FabricMC/fabric-example-mod) — основа `build.gradle`;
- [Baritone](https://github.com/cabaletta/baritone) — путь и автодобыча.

## Лицензия

CC0 1.0 Universal — делайте с кодом что угодно.
