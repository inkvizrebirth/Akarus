<div align="center">

# Dreamcast DLC

**Клиентский мод для Minecraft 26.2: свой интерфейс, HUD и игровые модули.**

[![Minecraft](https://img.shields.io/badge/Minecraft-26.2-71b722?logo=minecraft&logoColor=white)](https://www.minecraft.net/)
[![Java](https://img.shields.io/badge/Java-25-orange?logo=openjdk&logoColor=white)](https://adoptium.net/)
[![Version](https://img.shields.io/badge/version-0.9.0-7C6CFF)](https://github.com/inkvizrebirth/Dreamcast/releases)
[![Build](https://github.com/inkvizrebirth/Dreamcast/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/inkvizrebirth/Dreamcast/actions/workflows/build.yml)

</div>

![Dreamcast HUD](docs/preview-hud.png)

## Главное

- полностью свои главное меню, пауза, настройки, миры и серверы;
- ClickGUI со стеклом, поиском, темами и анимациями;
- перетаскиваемый HUD: Target HUD, эффекты, броня/оффхенд, keystrokes + CPS, сессия, бинды, уведомления и музыка;
- KillAura, Scaffold (Normal/Legit/Telly), AutoBuff с питьём и splash-зельями, AutoTotem, NoFall, ESP, Trails, FreeCam/FreeLook, Baritone-модули и другое;
- встроенные ViaFabricPlus, Sodium, Lithium, ImmediatelyFast и Mod Menu.

![ClickGUI](docs/preview-clickgui.png)

## Установка

1. Установи Fabric Loader для **Minecraft 26.2** и Fabric API `0.159+`.
2. Скачай `dreamcast-0.9.0.jar` из [Releases](https://github.com/inkvizrebirth/Dreamcast/releases) и положи в `.minecraft/mods`.
3. Запусти игру на Java 25.

## Управление

| Клавиша | Действие |
|---|---|
| `RShift` | ClickGUI |
| `H` | редактор HUD |
| `X` | KillAura |
| `N` | FreeCam |
| `K` | FreeLook |
| `B` | AutoMine |
| `G` | AutoWalk |
| `R` | AutoTotem |

## Сборка

```bash
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

CI собирает релиз с embedded-модами. Лицензия — [CC0](LICENSE).
