#!/usr/bin/env bash
set -euo pipefail

ROOT=/tmp/wp05-tracking-contract
SERVER="$ROOT/server"
DB="$SERVER/plugins/EnthusiaLoreItems/loreitems.db"
EVIDENCE="$ROOT/evidence"
BOT_PID=""
SERVER_PID=""

wait_ready() {
  local logfile="$1"
  for _ in $(seq 1 180); do
    if grep -q 'Queued direct-delivery processing is active.' "$logfile" \
      && grep -q 'WP-05 deterministic acceptance helper is active' "$logfile"; then
      return 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      cat "$logfile"
      return 1
    fi
    sleep 1
  done
  echo "server readiness timed out" >&2
  cat "$logfile"
  return 1
}

wait_marker() {
  local marker="$1"
  local tries="${2:-240}"
  for _ in $(seq 1 "$tries"); do
    if [[ -f "$ROOT/bot.failed" ]]; then
      cat "$ROOT/bot.failed" >&2
      return 1
    fi
    [[ -f "$ROOT/$marker" ]] && return 0
    sleep .25
  done
  echo "marker timed out: $marker" >&2
  return 1
}

wait_player_copy() {
  local lookup_key="$1"
  python3 - "$DB" "$lookup_key" <<'PY'
import sqlite3,sys,time
path,key=sys.argv[1:3]
last=[]
for _ in range(160):
    with sqlite3.connect(path) as c:
        last=c.execute("""
          select s.state,s.location_type,s.location_key,s.container_path,i.instance_id
          from instance_current_state s
          join lore_instances i on i.instance_id=s.instance_id
          join lore_definitions d on d.definition_id=i.definition_id
          where d.lookup_key=?
        """,(key,)).fetchall()
        if any(state=='CONFIRMED_NOW' and kind=='PLAYER_INVENTORY' for state,kind,_,_,_ in last):
            print('player copy ready',key,last)
            break
    time.sleep(.25)
else:
    raise SystemExit(f'queued delivery for {key} never reached player inventory: {last}')
PY
  sleep .35
}

start_server() {
  local logfile="$1"
  rm -f "$SERVER/server.stdin"
  mkfifo "$SERVER/server.stdin"
  exec 3<>"$SERVER/server.stdin"
  (
    cd "$SERVER"
    java -Xms512M -Xmx1536M -Dpaper.disablePluginRemapping=true -jar paper.jar --nogui \
      <server.stdin >"$logfile" 2>&1
  ) &
  SERVER_PID=$!
  wait_ready "$logfile"
}

stop_server() {
  local pid="$SERVER_PID"
  local status=0
  if [[ -n "$pid" ]]; then
    if kill -0 "$pid" 2>/dev/null; then
      echo stop >&3 || true
    fi
    if wait "$pid"; then
      status=0
    else
      status=$?
    fi
  fi
  exec 3>&- || true
  rm -f "$SERVER/server.stdin"
  SERVER_PID=""
  return "$status"
}

cleanup() {
  touch "$ROOT/bot.stop" || true
  if [[ -n "$BOT_PID" ]]; then
    wait "$BOT_PID" || true
  fi
  stop_server
}
trap cleanup EXIT

rm -f "$ROOT"/go-* "$ROOT"/track*-done "$ROOT"/track1-reconnected "$ROOT"/bot.failed "$ROOT"/bot.stop
start_server "$EVIDENCE/server.log"
echo 'setworldspawn 0 70 0' >&3

(
  cd "$ROOT/bot"
  node "$GITHUB_WORKSPACE/acceptance-harness/scripts/wp05-tracking-bot.js"
) >"$EVIDENCE/bot-stdout.log" 2>&1 &
BOT_PID=$!

for _ in $(seq 1 120); do
  [[ -s "$ROOT/bot.uuid" ]] && break
  sleep .25
done
[[ -s "$ROOT/bot.uuid" ]]
echo 'op Wp05TrackBot' >&3
echo 'clear Wp05TrackBot' >&3

# ACC-TRACK-001: ordinary player storage/equipment/cursor/Ender Chest, then disconnect/rejoin.
echo 'wp05accept source Wp05TrackBot helmet' >&3
sleep .5
echo 'wp05accept perform Wp05TrackBot loreitems create acc_track_contract Track Contract' >&3
sleep 1
echo 'clear Wp05TrackBot' >&3
sleep .5
echo 'wp05accept perform Wp05TrackBot loreitems give acc_track_contract' >&3
wait_player_copy acc_track_contract
python3 - "$DB" <<'PY'
import sqlite3,sys,time
path=sys.argv[1]
for _ in range(180):
    with sqlite3.connect(path) as c:
        rows=c.execute("""
            select i.instance_id
            from lore_instances i join lore_definitions d on d.definition_id=i.definition_id
            where d.lookup_key='acc_track_contract' and i.lifecycle_state='ACTIVE'
        """).fetchall()
        if len(rows)==1:
            open('/tmp/wp05-tracking-contract/evidence/track1-id.txt','w').write(rows[0][0]+'\n')
            break
    time.sleep(.25)
else:
    raise SystemExit('track1 instance not ready')
PY

touch "$ROOT/go-track1"
wait_marker track1-online-done
sleep 1
python3 - "$DB" <<'PY'
import sqlite3
path='/tmp/wp05-tracking-contract/server/plugins/EnthusiaLoreItems/loreitems.db'
iid=open('/tmp/wp05-tracking-contract/evidence/track1-id.txt').read().strip()
with sqlite3.connect(path) as c:
    obs=c.execute('select location_type,container_path,confidence,source from instance_observations where instance_id=? order by observation_id',(iid,)).fetchall()
    paths={(kind,slot) for kind,slot,_,_ in obs}
    print('TRACK1 observations',obs)
    # One inventory can have many slots; only holder transitions belong in
    # durable user-facing history, not offhand/armor/cursor rearrangements.
    assert any(kind=='PLAYER_INVENTORY' for kind,slot in paths),paths
    assert any(kind=='PLAYER_ENDER_CHEST' for kind,slot in paths),paths
    assert not any(kind=='PLAYER_INVENTORY' and slot=='offhand' for kind,slot in paths),paths
    assert not any(kind=='PLAYER_INVENTORY' and slot and slot.startswith('armor:') for kind,slot in paths),paths
    assert not any(kind=='PLAYER_INVENTORY' and slot=='cursor' for kind,slot in paths),paths
    state=c.execute('select state,location_type from instance_current_state where instance_id=?',(iid,)).fetchone()
    assert state and state[0]=='LAST_CONFIRMED',state
PY

touch "$ROOT/go-reconnect"
wait_marker track1-reconnected 180
sleep 1
python3 - "$DB" <<'PY' | tee "$EVIDENCE/case-results.txt"
import sqlite3
path='/tmp/wp05-tracking-contract/server/plugins/EnthusiaLoreItems/loreitems.db'
iid=open('/tmp/wp05-tracking-contract/evidence/track1-id.txt').read().strip()
with sqlite3.connect(path) as c:
    state=c.execute('select state,location_type,location_key,container_path from instance_current_state where instance_id=?',(iid,)).fetchone()
    print('TRACK1 reconnect state',state)
    assert state and state[0]=='CONFIRMED_NOW' and state[1]=='PLAYER_INVENTORY',state
    assert c.execute('select count(*) from lore_instances where instance_id=?',(iid,)).fetchone()[0]==1
print('PASS ACC-TRACK-001: storage/offhand/armor/cursor/Ender/offline/rejoin identity continuity')
PY

# ACC-TRACK-002: chest/hopper/nested storage plus natural chunk unload/reload.
echo 'clear Wp05TrackBot' >&3
echo 'wp05accept source Wp05TrackBot sword' >&3
sleep .5
echo 'wp05accept perform Wp05TrackBot loreitems create acc_track_nested Track Nested' >&3
sleep 1
echo 'clear Wp05TrackBot' >&3
sleep .5
echo 'wp05accept perform Wp05TrackBot loreitems give acc_track_nested' >&3
wait_player_copy acc_track_nested

touch "$ROOT/go-track2-chest"
wait_marker track2-chest-done 180
touch "$ROOT/go-track2-hopper"
wait_marker track2-hopper-done 180

echo 'wp05accept perform Wp05TrackBot loreitems give acc_track_nested' >&3
wait_player_copy acc_track_nested
echo 'wp05accept place Wp05TrackBot shulker' >&3
sleep 1
touch "$ROOT/go-track2-shulker"
wait_marker track2-shulker-done 180

echo 'wp05accept perform Wp05TrackBot loreitems give acc_track_nested' >&3
wait_player_copy acc_track_nested
echo 'wp05accept place Wp05TrackBot bundle' >&3
sleep 1
touch "$ROOT/go-track2-bundle"
wait_marker track2-bundle-done 180
sleep 1

python3 - "$DB" <<'PY'
import sqlite3
path='/tmp/wp05-tracking-contract/server/plugins/EnthusiaLoreItems/loreitems.db'
with sqlite3.connect(path) as c:
    did=c.execute("select definition_id from lore_definitions where lookup_key='acc_track_nested'").fetchone()[0]
    obs=c.execute('select instance_id,location_type,location_key,container_path,source from instance_observations where definition_id=? order by observation_id',(did,)).fetchall()
    print('TRACK2 pre-unload observations',obs)
    assert any(row[1]=='BLOCK_CONTAINER' for row in obs),obs
    assert any(row[1]=='NESTED_CONTAINER' and row[3] and '/shulker:' in row[3] for row in obs),obs
    assert any(row[1]=='NESTED_CONTAINER' and row[3] and '/bundle:' in row[3] for row in obs),obs
PY

# Move spawn/player far enough that the test chunks can unload naturally. No force-load APIs are used.
echo 'setworldspawn 256 70 0' >&3
touch "$ROOT/go-track2-away"
wait_marker track2-away-done 200
python3 - "$DB" <<'PY'
import sqlite3,time
path='/tmp/wp05-tracking-contract/server/plugins/EnthusiaLoreItems/loreitems.db'
rows=[]
for _ in range(120):
    with sqlite3.connect(path) as c:
        did=c.execute("select definition_id from lore_definitions where lookup_key='acc_track_nested'").fetchone()[0]
        rows=c.execute("""
          select s.state,s.location_type,s.location_key,s.container_path
          from instance_current_state s join lore_instances i on i.instance_id=s.instance_id
          where i.definition_id=?
        """,(did,)).fetchall()
        retained=[r for r in rows if r[1] in ('BLOCK_CONTAINER','NESTED_CONTAINER')]
        if len(retained)>=3 and all(r[0]=='LAST_CONFIRMED' for r in retained):
            print('TRACK2 unloaded states',retained)
            break
    time.sleep(.25)
else:
    raise SystemExit(f'TRACK2 did not converge to LAST_CONFIRMED after natural unload: {rows}')
PY

touch "$ROOT/go-track2-return"
wait_marker track2-return-done 200
python3 - "$DB" <<'PY' | tee -a "$EVIDENCE/case-results.txt"
import sqlite3,time
path='/tmp/wp05-tracking-contract/server/plugins/EnthusiaLoreItems/loreitems.db'
rows=[]
for _ in range(120):
    with sqlite3.connect(path) as c:
        did=c.execute("select definition_id from lore_definitions where lookup_key='acc_track_nested'").fetchone()[0]
        rows=c.execute("""
          select s.state,s.location_type,s.location_key,s.container_path
          from instance_current_state s join lore_instances i on i.instance_id=s.instance_id
          where i.definition_id=?
        """,(did,)).fetchall()
        nested=[r for r in rows if r[1]=='NESTED_CONTAINER']
        if len(nested)>=2 and all(r[0]=='CONFIRMED_NOW' for r in nested):
            break
    time.sleep(.25)
else:
    raise SystemExit(f'TRACK2 nested locations did not re-confirm after reload/access: {rows}')
with sqlite3.connect(path) as c:
    did=c.execute("select definition_id from lore_definitions where lookup_key='acc_track_nested'").fetchone()[0]
    sources=[r[0] for r in c.execute('select source from instance_observations where definition_id=?',(did,)).fetchall()]
    assert any('chunk-unload' in source for source in sources),sources
    assert any('chunk-load' in source for source in sources),sources
print('PASS ACC-TRACK-002 allowed-mode: chest/hopper/nested shulker+bundle plus natural unload/reload retention')
PY

echo 'setworldspawn 0 70 0' >&3

# ACC-TRACK-003: natural drop/pickup, ordinary player display placement, controlled death and chunk lifecycle.
echo 'clear Wp05TrackBot' >&3
echo 'wp05accept source Wp05TrackBot sword' >&3
sleep .5
echo 'wp05accept perform Wp05TrackBot loreitems create acc_track_world Track World' >&3
sleep 1
echo 'clear Wp05TrackBot' >&3
sleep .5
echo 'wp05accept perform Wp05TrackBot loreitems give acc_track_world' >&3
wait_player_copy acc_track_world

touch "$ROOT/go-track3-drop"
wait_marker track3-drop-done 160
python3 - "$DB" <<'PY'
import sqlite3,time
path='/tmp/wp05-tracking-contract/server/plugins/EnthusiaLoreItems/loreitems.db'
for _ in range(100):
    with sqlite3.connect(path) as c:
        did=c.execute("select definition_id from lore_definitions where lookup_key='acc_track_world'").fetchone()[0]
        count=c.execute("""
          select count(*) from instance_current_state s join lore_instances i on i.instance_id=s.instance_id
          where i.definition_id=? and s.location_type='DROPPED_ITEM'
        """,(did,)).fetchone()[0]
        if count>=1: break
    time.sleep(.2)
else:
    raise SystemExit('normal drop not tracked')
PY

touch "$ROOT/go-track3-pickup"
wait_marker track3-pickup-done 160
# Move world spawn away immediately after pickup so the display chunk is held only by ordinary player
# presence during placement and can unload naturally once the player leaves.
echo 'setworldspawn 256 70 0' >&3
sleep .5

# Empty frame fixtures are deterministic server setup. The real client still moves the tracked item
# into each display, so PaperItemFrameChangeEvent / PlayerArmorStandManipulateEvent tracking is exercised.
echo 'setblock 72 71 1 minecraft:stone' >&3
echo 'summon minecraft:item_frame 72.5 71.5 0.96875 {Facing:2b,Tags:["wp05-acceptance"]}' >&3
sleep .5
touch "$ROOT/go-track3-frame"
wait_marker track3-frame-done 160

echo 'wp05accept perform Wp05TrackBot loreitems give acc_track_world' >&3
wait_player_copy acc_track_world
echo 'setblock 74 71 1 minecraft:stone' >&3
echo 'summon minecraft:glow_item_frame 74.5 71.5 0.96875 {Facing:2b,Tags:["wp05-acceptance"]}' >&3
sleep .5
touch "$ROOT/go-track3-glowframe"
wait_marker track3-glowframe-done 160

echo 'wp05accept perform Wp05TrackBot loreitems give acc_track_world' >&3
wait_player_copy acc_track_world
echo 'setblock 76 69 0 minecraft:stone' >&3
echo 'setblock 76 70 0 minecraft:air' >&3
echo 'setblock 76 71 0 minecraft:air' >&3
echo 'summon minecraft:armor_stand 76 70 0 {ShowArms:1b,Tags:["wp05-acceptance"]}' >&3
touch "$ROOT/go-track3-armorstand"
wait_marker track3-armorstand-done 160

python3 - "$DB" <<'PY' | tee -a "$EVIDENCE/case-results.txt"
import sqlite3,time
path='/tmp/wp05-tracking-contract/server/plugins/EnthusiaLoreItems/loreitems.db'
rows=[]
for _ in range(120):
    with sqlite3.connect(path) as c:
        did=c.execute("select definition_id from lore_definitions where lookup_key='acc_track_world'").fetchone()[0]
        rows=c.execute("""
          select s.state,s.location_type,s.location_key,s.container_path,i.instance_id
          from instance_current_state s join lore_instances i on i.instance_id=s.instance_id
          where i.definition_id=? and s.location_type in ('ITEM_FRAME','ARMOR_STAND')
        """,(did,)).fetchall()
        if len(rows)>=3 and all(r[0]=='CONFIRMED_NOW' for r in rows):
            sources=[r[0] for r in c.execute("""
              select source from instance_observations where definition_id=?
              and location_type in ('ITEM_FRAME','ARMOR_STAND')
            """,(did,)).fetchall()]
            if any('item-frame-change-unique' in source for source in sources) \
              and any('armor-stand-manipulate-unique' in source for source in sources):
                print('TRACK3 ordinary display states',rows)
                print('TRACK3 ordinary display sources',sources)
                break
    time.sleep(.25)
else:
    raise SystemExit(f'TRACK3 ordinary client display placement did not become authoritative: {rows}')
print('PASS ACC-TRACK-003 ordinary client frame/glow-frame/armor-stand placement observed authoritatively')
PY

# Controlled death/drop occurs in the same display chunk so one natural unload covers the isolated fixture.
echo 'setblock 69 70 0 minecraft:stone' >&3
echo 'tp Wp05TrackBot 69 71 0' >&3
sleep 1
echo 'wp05accept perform Wp05TrackBot loreitems give acc_track_world' >&3
wait_player_copy acc_track_world
echo 'kill Wp05TrackBot' >&3
sleep 5

# Removing the spawn/player tickets forces no chunks; it merely permits normal server unload.
echo 'setworldspawn 256 70 0' >&3
touch "$ROOT/go-track3-away"
wait_marker track3-away-done 220
python3 - "$DB" "$EVIDENCE/track3-unload-state.txt" <<'PY'
import sqlite3,sys,time
path,evidence=sys.argv[1:3]
rows=[]
for _ in range(600):
    with sqlite3.connect(path) as c:
        did=c.execute("select definition_id from lore_definitions where lookup_key='acc_track_world'").fetchone()[0]
        rows=c.execute("""
          select s.state,s.location_type,s.location_key,s.container_path,i.instance_id
          from instance_current_state s join lore_instances i on i.instance_id=s.instance_id
          where i.definition_id=? and s.location_type in ('ITEM_FRAME','ARMOR_STAND')
        """,(did,)).fetchall()
        if len(rows)>=3 and all(r[0]=='LAST_CONFIRMED' for r in rows):
            open(evidence,'w').write('TRACK3 unloaded display states '+repr(rows)+'\n')
            print('TRACK3 unloaded display states',rows)
            break
    time.sleep(.25)
else:
    with sqlite3.connect(path) as c:
        obs=c.execute("""
          select instance_id,location_type,location_key,container_path,confidence,source
          from instance_observations where definition_id=? order by observation_id desc limit 40
        """,(did,)).fetchall()
    open(evidence,'w').write('TRACK3 unload timeout states '+repr(rows)+'\nobservations '+repr(obs)+'\n')
    raise SystemExit(f'TRACK3 displays not retained as LAST_CONFIRMED on unload: {rows}')
PY

touch "$ROOT/go-track3-return"
wait_marker track3-return-done 220
python3 - "$DB" <<'PY' | tee -a "$EVIDENCE/case-results.txt"
import sqlite3,time
path='/tmp/wp05-tracking-contract/server/plugins/EnthusiaLoreItems/loreitems.db'
rows=[]
for _ in range(180):
    with sqlite3.connect(path) as c:
        did=c.execute("select definition_id from lore_definitions where lookup_key='acc_track_world'").fetchone()[0]
        rows=c.execute("""
          select s.state,s.location_type,s.location_key,s.container_path
          from instance_current_state s join lore_instances i on i.instance_id=s.instance_id
          where i.definition_id=? and s.location_type in ('ITEM_FRAME','ARMOR_STAND')
        """,(did,)).fetchall()
        if len(rows)>=3 and all(r[0]=='CONFIRMED_NOW' for r in rows): break
    time.sleep(.25)
else:
    raise SystemExit(f'TRACK3 displays not re-confirmed after natural chunk reload: {rows}')
with sqlite3.connect(path) as c:
    did=c.execute("select definition_id from lore_definitions where lookup_key='acc_track_world'").fetchone()[0]
    obs=c.execute('select location_type,container_path,source from instance_observations where definition_id=?',(did,)).fetchall()
    print('TRACK3 observations',obs)
    assert any(kind=='DROPPED_ITEM' for kind,_,_ in obs),obs
    assert sum(1 for kind,_,_ in obs if kind=='ITEM_FRAME')>=2,obs
    assert any(kind=='ARMOR_STAND' for kind,_,_ in obs),obs
    assert any('player-death' in source or 'item-spawn' in source for _,_,source in obs),obs
    assert any('chunk-unload' in source for _,_,source in obs),obs
    assert any('chunk-load' in source for _,_,source in obs),obs
    assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
    assert c.execute('pragma foreign_key_check').fetchall()==[]
print('PASS ACC-TRACK-003: drop/pickup + frame/glow-frame/armor-stand + death/drop + natural chunk unload/reload')
PY

echo 'setworldspawn 0 70 0' >&3
touch "$ROOT/bot.stop"
wait "$BOT_PID"
BOT_PID=""
stop_server

# Restart against the exact same database/JAR and prove durable schema/current-state recovery.
start_server "$EVIDENCE/server-restart.log"
sleep 6
python3 - "$DB" <<'PY' | tee -a "$EVIDENCE/case-results.txt"
import sqlite3,sys
with sqlite3.connect(sys.argv[1]) as c:
    assert [row[0] for row in c.execute('select version from schema_history order by version')]==list(range(1,12))
    assert c.execute('pragma integrity_check').fetchone()[0]=='ok'
    assert c.execute('pragma foreign_key_check').fetchall()==[]
    assert c.execute('select count(*) from instance_current_state').fetchone()[0]>0
print('PASS tracking restart durability/integrity')
PY

if grep -E 'Exception ticking world|Could not pass event|Server thread.*ERROR|java\.lang\.(NullPointerException|IllegalStateException)' \
  "$EVIDENCE/server.log" "$EVIDENCE/server-restart.log"; then
  echo 'unexpected server error signature in tracking acceptance logs' >&2
  exit 1
fi

stop_server
trap - EXIT