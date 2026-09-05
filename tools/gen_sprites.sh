#!/usr/bin/env bash
# Разовая генерация наших текстур поверх ванильных gui-спрайтов (ImageMagick).
# Размеры ровно как в jar (снято через CI-заметки), поэтому ванильные
# девять-срезов и растяжение продолжают работать без правок в коде.
set -euo pipefail
cd "$(dirname "$0")/.."
D=src/main/resources/assets/minecraft/textures/gui
W=$D/sprites/widget
C=$D/sprites/container
mkdir -p "$W" "$C"

# --- кнопки (200x20) ---
convert -size 200x20 gradient:"#2A2532"-"#141218" \
  \( -size 200x20 xc:none -fill "#FFFFFF8C" -draw "rectangle 3,1 197,1" \
     -fill "#9A5CFFFF" -draw "rectangle 3,19 197,19" \
     -fill none -stroke "#FFFFFF66" -strokewidth 1 -draw "roundrectangle 0.5,0.5 199.5,19.5 3,3" \) \
  -compose Over -composite "$W/button.png"

convert -size 200x20 gradient:"#3A3446"-"#1F1B28" \
  \( -size 200x20 xc:none -fill "#FFFFFFD9" -draw "rectangle 3,1 197,1" \
     -fill "#22D3EEFF" -draw "rectangle 3,19 197,19" \
     -fill none -stroke "#FFFFFF80" -strokewidth 1 -draw "roundrectangle 0.5,0.5 199.5,19.5 3,3" \) \
  -compose Over -composite "$W/button_highlighted.png"

convert -size 200x20 xc:"#1313178C" \
  \( -size 200x20 xc:none -fill none -stroke "#FFFFFF1F" -strokewidth 1 \
     -draw "roundrectangle 0.5,0.5 199.5,19.5 3,3" \) \
  -compose Over -composite "$W/button_disabled.png"

# --- слайдеры (200x20 + ручка 8x20) ---
convert -size 200x20 gradient:"#211D29"-"#100E14" \
  \( -size 200x20 xc:none -fill none -stroke "#FFFFFF4D" -strokewidth 1 \
     -draw "roundrectangle 0.5,0.5 199.5,19.5 3,3" \) \
  -compose Over -composite "$W/slider.png"

convert -size 200x20 gradient:"#2B2735"-"#17141D" \
  \( -size 200x20 xc:none -fill "#FFFFFF8C" -draw "rectangle 3,1 197,1" \
     -fill "#9A5CFFFF" -draw "rectangle 3,19 197,19" \
     -fill none -stroke "#FFFFFF73" -strokewidth 1 -draw "roundrectangle 0.5,0.5 199.5,19.5 3,3" \) \
  -compose Over -composite "$W/slider_highlighted.png"

convert -size 8x20 gradient:"#F2EDEA"-"#C9C2D8" \
  \( -size 8x20 xc:none -fill "#00000033" -draw "rectangle 0,0 0,19" -draw "rectangle 7,0 7,19" \
     -fill "#FFFFFFFF" -draw "rectangle 1,0 6,0" \) \
  -compose Over -composite "$W/slider_handle.png"

convert -size 8x20 gradient:"#FFFFFF"-"#DDD6F5" \
  \( -size 8x20 xc:none -fill "#00000066" -draw "rectangle 0,0 0,19" -draw "rectangle 7,0 7,19" \
     -fill "#22D3EE" -draw "rectangle 1,19 6,19" \) \
  -compose Over -composite "$W/slider_handle_highlighted.png"

# --- поля ввода (200x20) и крестик очистки (14x14) ---
convert -size 200x20 xc:"#06060ABF" \
  \( -size 200x20 xc:none -fill none -stroke "#FFFFFF40" -strokewidth 1 \
     -draw "roundrectangle 0.5,0.5 199.5,19.5 3,3" \) \
  -compose Over -composite "$W/text_field.png"

convert -size 200x20 xc:"#06060ABF" \
  \( -size 200x20 xc:none -fill none -stroke "#22D3EEE6" -strokewidth 1 \
     -draw "roundrectangle 0.5,0.5 199.5,19.5 3,3" \) \
  -compose Over -composite "$W/text_field_highlighted.png"

convert -size 14x14 xc:"#10101499" \
  \( -size 14x14 xc:none -fill "#E6E4E4F0" -draw "polygon 4,4 5,3 10,8 9,9" \
     -draw "polygon 9,3 10,4 5,9 4,8" \
     -fill none -stroke "#FFFFFF33" -strokewidth 1 -draw "roundrectangle 0.5,0.5 13.5,13.5 3,3" \) \
  -compose Over -composite "$W/cross_button.png"

convert -size 14x14 xc:"#241F2ECC" \
  \( -size 14x14 xc:none -fill "#FFFFFFFF" -draw "polygon 4,4 5,3 10,8 9,9" \
     -draw "polygon 9,3 10,4 5,9 4,8" \
     -fill none -stroke "#FFFFFF80" -strokewidth 1 -draw "roundrectangle 0.5,0.5 13.5,13.5 3,3" \) \
  -compose Over -composite "$W/cross_button_highlighted.png"

# --- чекбоксы (20x20) ---
checkbox() { # checkbox <имя> <фон> <обводка> <акцент|none>
  local name=$1 bg=$2 rim=$3 acc=$4
  if [ "$acc" = "none" ]; then
    convert -size 20x20 xc:"$bg" \
      \( -size 20x20 xc:none -fill none -stroke "$rim" -strokewidth 1 \
         -draw "roundrectangle 0.5,0.5 19.5,19.5 4,4" \) \
      -compose Over -composite "$W/$name.png"
  else
    convert -size 20x20 xc:"$bg" \
      \( -size 20x20 xc:none -fill "${acc}4C" \
         -draw "roundrectangle 3.5,3.5 16.5,16.5 3,3" \
         -fill "#F4F2FAFF" \
         -draw "polygon 6,10 8,12.5 14,5 15.4,6.4 8.1,14.6 4.7,11.3" \
         -fill none -stroke "$rim" -strokewidth 1 \
         -draw "roundrectangle 0.5,0.5 19.5,19.5 4,4" \) \
      -compose Over -composite "$W/$name.png"
  fi
}
checkbox checkbox "#1A1A20" "#FFFFFF33" none
checkbox checkbox_highlighted "#251F33" "#FFFFFF66" none
checkbox checkbox_selected "#241D33" "#FFFFFF66" "#9A5CFF"
checkbox checkbox_selected_highlighted "#33284A" "#FFFFFF99" "#22D3EE"

# --- скроллбар (6x32: бегунок и фон) ---
convert -size 6x32 gradient:"#9A5CFF99"-"#22D3EE99" \
  \( -size 6x32 xc:none -fill none -stroke "#FFFFFF40" -strokewidth 1 \
     -draw "roundrectangle 0.5,0.5 5.5,31.5 2,2" \) \
  -compose Over -composite "$W/scroller.png"

convert -size 6x32 xc:"#00000040" \
  \( -size 6x32 xc:none -fill none -stroke "#FFFFFF1A" -strokewidth 1 \
     -draw "roundrectangle 0.5,0.5 5.5,31.5 2,2" \) \
  -compose Over -composite "$W/scroller_background.png"

# --- вкладки (130x24) ---
convert -size 130x24 gradient:"#2A2532"-"#191620" \
  \( -size 130x24 xc:none -fill none -stroke "#FFFFFF33" -strokewidth 1 \
     -draw "roundrectangle 0.5,0.5 129.5,23.5 4,4" \) \
  -compose Over -composite "$W/tab.png"

convert -size 130x24 gradient:"#373142"-"#211D29" \
  \( -size 130x24 xc:none -fill none -stroke "#FFFFFF4D" -strokewidth 1 \
     -draw "roundrectangle 0.5,0.5 129.5,23.5 4,4" \) \
  -compose Over -composite "$W/tab_highlighted.png"

convert -size 130x24 gradient:"#3A2E5C"-"#241D33" \
  \( -size 130x24 xc:none -fill "#22D3EE" -draw "rectangle 4,1 126,1" \
     -fill none -stroke "#FFFFFF80" -strokewidth 1 -draw "roundrectangle 0.5,0.5 129.5,23.5 4,4" \) \
  -compose Over -composite "$W/tab_selected.png"

convert -size 130x24 gradient:"#4A3A72"-"#2C2440" \
  \( -size 130x24 xc:none -fill "#FFFFFFFF" -draw "rectangle 4,1 126,1" \
     -fill none -stroke "#FFFFFF99" -strokewidth 1 -draw "roundrectangle 0.5,0.5 129.5,23.5 4,4" \) \
  -compose Over -composite "$W/tab_selected_highlighted.png"

# --- слоты инвентаря (18x18) и подсветка выбранных (24x24) ---
convert -size 22x22 xc:"#0C0C12CC" \
  \( -size 22x22 xc:none -fill none -stroke "#FFFFFF40" -strokewidth 1 \
     -draw "roundrectangle 1.5,1.5 20.5,20.5 3,3" \
     -fill "#FFFFFF1F" -draw "rectangle 3,3 19,3" \) \
  -compose Over -composite "$C/slot.png"

convert -size 24x24 xc:none -fill none -stroke "#9A5CFFB3" -strokewidth 2 \
  -draw "roundrectangle 1,1 23,23 4,4" "$C/slot_highlight_back.png"

convert -size 22x22 xc:none -fill none -stroke "#FFFFFF66" -strokewidth 1 \
  -draw "roundrectangle 1.5,1.5 20.5,20.5 3,3" "$C/slot_highlight_front.png"

# --- фон меню (256x256 укладывается тайлами, поэтому почти однородный + дизер) ---
convert -size 256x256 xc:"#0D0A14" \
  \( -size 256x256 xc:gray50 +noise Gaussian -blur 0x0.5 -colorspace Gray \) \
  -compose SoftLight -composite "$D/options_background.png"


# --- креатив: вкладки (26x32) и бегунок списка (12x15) ---
KI=$D/sprites/container/creative_inventory
mkdir -p "$KI"
tab() { # tab <файл> <позиция top|bottom> <состояние selected|unselected>
  local file=$1 pos=$2 sel=$3
  local body rim line accent
  if [ "$sel" = "selected" ]; then
    body="#33284A"; rim="#FFFFFF73"; accent="#22D3EE"
  else
    body="#1A1820"; rim="#FFFFFF2E"; accent="#9A5CFF"
  fi
  if [ "$pos" = "top" ]; then
    convert -size 26x32 xc:none -fill "$body" -draw "roundrectangle 1,1 25,25 5,5" \
      \( -size 26x32 xc:none -fill "$accent" -draw "rectangle 4,2 22,2" \
         -fill none -stroke "$rim" -strokewidth 1 -draw "roundrectangle 1,1 25,25 5,5" \) \
      -compose Over -composite "$file"
  else
    convert -size 26x32 xc:none -fill "$body" -draw "roundrectangle 1,6 25,30 5,5" \
      \( -size 26x32 xc:none -fill "$accent" -draw "rectangle 4,29 22,29" \
         -fill none -stroke "$rim" -strokewidth 1 -draw "roundrectangle 1,6 25,30 5,5" \) \
      -compose Over -composite "$file"
  fi
}
for pos in top bottom; do
  for sel in selected unselected; do
    tab /tmp/tab_${pos}_${sel}.png "$pos" "$sel"
    for i in 1 2 3 4 5 6 7; do
      cp /tmp/tab_${pos}_${sel}.png "$KI/tab_${pos}_${sel}_${i}.png"
    done
  done
done

convert -size 12x15 xc:none -fill "#9A5CFFCC" -draw "roundrectangle 1,1 11,14 4,4" \
  -fill "#FFFFFF59" -draw "rectangle 3,2 9,2" "$KI/scroller.png"

convert -size 12x15 xc:none -fill "#3A3744A6" -draw "roundrectangle 1,1 11,14 4,4" \
  "$KI/scroller_disabled.png"


# --- листы фонов окон: только те, где в ваниле нет полезных глифов ---
# (generic_54 = сундуки/бочки/раздатчики-без-стрелок, inventory, shulker_box).
# Панель всегда начинается с (0,0) и имеет ширину 176, поэтому кромки рисуем
# по x=0/x=175 и y=0 — они совпадают для любой высоты окна.
S=$D/container
mkdir -p "$S"
sheet() { # sheet <файл>
  local out=$1
  # структурный дизер 4x4 вместо шума: выглядит так же, но сжимается в байты
  convert -size 4x4 xc:"#0E0D13" \
    -fill "#151221" -draw "point 0,0" -draw "point 2,2" -draw "point 1,3" -draw "point 3,1" \
    -fill "#0A090F" -draw "point 1,1" -draw "point 3,3" -draw "point 0,2" -draw "point 2,0" \
    /tmp/dc_tile.png
  convert -size 256x256 tile:/tmp/dc_tile.png \
    \( -size 256x48 gradient:"#1A1824"-"#0E0D12" \) -gravity North \
    -compose Blend -define compose:args=70 -composite -gravity NorthWest \
    -fill "#FFFFFF12" -draw "rectangle 0,0 175,0" -draw "rectangle 0,0 0,255" \
    -fill "#00000059" -draw "rectangle 175,0 175,255" -draw "rectangle 176,0 176,255" \
    -fill "#FFFFFF08" -draw "rectangle 1,1 174,1" \
    \( -size 256x256 xc:white -channel A -evaluate multiply 0.92 +channel \) -compose CopyOpacity -composite \
    "$out"
}
for name in generic_54 inventory shulker_box; do
  sheet "$S/$name.png"
done

identify "$W"/*.png "$C/slot.png" "$C/slot_highlight_back.png" "$C/slot_highlight_front.png" \
  "$D/options_background.png" | awk '{print $1, $3, $7, $8}'
