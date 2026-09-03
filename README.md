<div align="center">

# Dreamcast DLC

**Клиентский мод Minecraft в чёрных тонах — GUI, HUD и модули целиком свои**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-71b722?logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![FabricAPI](https://img.shields.io/badge/Fabric-loader%200.19%2B-ddd0c9)](https://fabricmc.net/)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Version](https://img.shields.io/badge/version-0.8.0-7C6CFF)](https://github.com/inkvizrebirth/Akarus/releases)
[![Build](https://github.com/inkvizrebirth/Akarus/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/inkvizrebirth/Akarus/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-CC0-yellow)](LICENSE)

[![Telegram](https://img.shields.io/badge/Telegram-@inkviz01-26A5E4?style=for-the-badge&logo=telegram&logoColor=white)](https://t.me/inkviz01)

</div>

![Главное меню](docs/preview-mainmenu.png)

## Что это

Dreamcast DLC — клиентский мод на **Fabric для Minecraft 26.2** (Java 25). Он заменяет
собой оболочку игры: **главное меню, меню паузы, настройки, списки миров и серверов —
полностью свои**, в чёрной плавной стилистике «стекла» с фиолетово-циановым акцентом,
как и ClickGUI с HUD. Внутри — набор игровых модулей (включая Trails и ESP),
музыкальный плеер и встроенный **ViaFabricPlus** для подключения к серверам других
версий.

![ClickGUI](docs/preview-clickgui.png)

## Модули

![Trails и ESP](docs/preview-trails-esp.png)

| Модуль | Бинд | Что делает |
|---|---|---|
| **Trails** | — | Светящийся след за игроком: лента в мире (толщина, длина, градиент/радуга) и/или цветные партиклы |
| **ESP** | — | Подсветка сущностей: Glow (ванильное свечение своим цветом), Box (3D-бокс с градиентом по высоте или «только углы»), фильтры целей и радиус |
| **AutoTotem** | `R` | Тотем в левой руке **до** удара: предсказывает молаут-смэш сверху, трезубцы/стрелы и сближение мили. Легит/обычный/турбо, окно и радиус настраиваются |
| **KillAura** | `X` | RayTrace-обязательный, Авто-Блок щитом, Смарт-крит, сброс спринта + лимиты: не бить во время еды/тотема, только с оружием, не под водой, не в прыжке, отступление при малом HP |
| NoFallDamage | — | Мгновенное ведро воды в ноги при опасном падении |
| FreeCam / FreeLook | `N` | Полёт камеры без движения игрока / осмотр вокруг себя |
| AutoWalk | ПКМ | Baritone идёт на блок, на который смотришь |
| AutoMine | `B` | Автосбор урожая / добыча через Baritone |
| Sprint, NoFOV, NoBlind | — | Постоянный бег, фикс FOV, слепота выключена |
| Hand Shader / ViewModel | `V` | Цветная обводка руки с градиентом, сдвиг и масштаб рук |
| **MediaPlayer** | — | Фоновая музыка (wav) мимо звука игры: свой плеер, карточка на HUD с прогрессом и эквалайзером, кнопка «открыть папку с музыкой» в ClickGUI |
| HUD-инфо | `H` | Ватермарк, FPS/XYZ/пинг, список активных модулей с плавным входом |

![HUD](docs/preview-hud.png)

## Не ванильный интерфейс

- **Главное меню** — шейдерный фон с медленным дрейфом, логотип Dreamcast, свои кнопки.
- **Список миров** — свой экран вместо SelectWorldScreen: иконки миров, дата, теги
  «эксперимент/другая версия», чипы Играть/Создать/Изменить/Удалить с инлайн-подтверждением.
- **Список серверов** — свой экран вместо JoinMultiplayerScreen: пинги, MOTD, игроки
  онлайн, иконки, добавление/редактирование/быстрый вход.
- **Меню паузы** — своё чёрное «стекло» вместо PauseScreen.
- **Настройки** — свой экран вместо ванильных: прорисовка, FPS, mipmap, мышь, облака,
  пресеты графики; назначение клавиш — нативный экран.
- **Версия протокола** — пилюля «◆ версия» в правом верхнем углу списка серверов:
  переключает протокол через **вшитый ViaFabricPlus** (включая «Автоопределение» для
  1.7+ серверов), с поиском по списку версий.
- **ClickGUI** (правый Shift) — 500×310, вкладки категорий с иконками, поиск по модулям,
  семь типов настроек, слайдеры с перетаскиванием, свои бинды модулей, ripple-эффекты.

![Списки миров и серверов](docs/preview-worlds.png)
![Список серверов с пилюлей версии](docs/preview-servers.png)

![Настройки](docs/preview-settings.png)

## Музыка

Мод ищет треки в `<папка игры>/akarus/media`. Форматы: wav/aiff/au (PCM, без внешних
библиотек — играет системный `javax.sound`, не зависит от FPS и звука игры).
Кнопка **«Открыть папку с музыкой»** есть прямо в модуле MediaPlayer в ClickGUI.

## Установка

1. Поставь [Fabric Loader](https://fabricmc.net/use/installer/) для **Minecraft 26.2** и [Fabric API](https://modrinth.com/mod/fabric-api) (0.159+ для 26.2) — оба jar'а в `.minecraft/mods`.
2. Скачай `akarus-0.8.0.jar` из [releases](https://github.com/inkvizrebirth/Akarus/releases) и положи туда же.
3. ViaFabricPlus **уже встроен** (nested jar) — отдельной установкой можно не заморачиваться.

Сборка из исходников: `JAVA_HOME=jdk25 ./gradlew build` (CI дополнительно скачивает VFP
в `libs/` перед сборкой; без него мод собирается без виа внутри).

## Как это устроено

Клиентская точка входа (`"client"` в fabric.mod.json), 11 миксинов без refmap'а
(Minecraft 26.2 использует имена Mojang напрямую; поля ввода после 26.2 живут в
`ClientInput`, поэтому миксин клавиатуры наследует его), HUD — через
`HudElementRegistry`-события Fabric, world-рендер Trails/ESP — через новые события
`LevelExtractionEvents`/`LevelRenderEvents` (извлечение данных на потоке игры,
геометрия — на сборе кадра), предсказание урона AutoTotem — по дельтам позиций
врагов (скорость + ETA до контакта/приземления), GUI — полностью свой рендер поверх
игры.

## Лицензия

CC0. Делай что хочешь.
