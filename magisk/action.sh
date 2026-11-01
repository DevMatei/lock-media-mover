#!/system/bin/sh

KEY=lockmediamover_bottom_dp
DEFAULT=160
STEP=10
MAX=400
IDLE=${LMM_IDLE:-6}

current() {
  v=$(settings get secure "$KEY" 2>/dev/null)
  case "$v" in
    -1) echo -1 ;;
    ''|*[!0-9]*) echo "$DEFAULT" ;;
    *) echo "$v" ;;
  esac
}

show() {
  if [ "$1" -lt 0 ]; then
    echo "  > OFF (stock position)"
  else
    echo "  > ${1}dp from the bottom"
  fi
}

wait_key() {
  ev=$(timeout "$IDLE" getevent -lq 2>/dev/null | grep -m1 -E "KEY_VOLUME(UP|DOWN) +DOWN")
  case "$ev" in
    *KEY_VOLUMEUP*) echo up ;;
    *KEY_VOLUMEDOWN*) echo down ;;
  esac
}

CUR=$(current)
echo "Lock Media Mover"
echo ""
echo "  Vol+  move the player up"
echo "  Vol-  move the player down"
echo "  (stop pressing for ${IDLE}s to finish)"
echo ""
show "$CUR"

PRESSES=0
while :; do
  K=$(wait_key)
  [ -z "$K" ] && break
  PRESSES=$((PRESSES + 1))
  if [ "$K" = up ]; then
    if [ "$CUR" -lt 0 ]; then CUR=0; else CUR=$((CUR + STEP)); fi
    [ "$CUR" -gt "$MAX" ] && CUR=$MAX
  else
    if [ "$CUR" -le 0 ]; then CUR=-1; else CUR=$((CUR - STEP)); fi
    [ "$CUR" -lt -1 ] && CUR=-1
  fi
  settings put secure "$KEY" "$CUR"
  show "$CUR"
done

echo ""
if [ "$PRESSES" -eq 0 ]; then
  echo "No key presses, nothing changed."
  echo "You can also use the WebUI (KernelSU, KsuWebUI or MMRL),"
  echo "or a root shell: settings put secure $KEY <dp>"
else
  echo "Saved. Lock your phone to see it :)"
fi
