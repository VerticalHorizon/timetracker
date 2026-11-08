# Bluetooth LE protocol

The firmware is a GATT server that advertises as **TimeTracker**. It knows nothing about the app's tasks or categories. It publishes its timers and accepts two commands. The Android app in [`android/`](../android) is the client.

| | UUID | Properties |
|---|---|---|
| Service | `603a0001-2e1a-4102-9367-4c8626517b1d` | advertised |
| State | `603a0002-2e1a-4102-9367-4c8626517b1d` | read, notify |
| Command | `603a0003-2e1a-4102-9367-4c8626517b1d` | write (with response) |

All integers are little-endian. Only one client can be connected at a time: the tracker stops advertising while a phone is connected.

## State

A snapshot of every timer, `14 + 8·T + 9·C` bytes long (81 bytes with the default 5 trackers and 3 countdowns). It is sent as a notification whenever it changes (about once a second while anything runs), right after the client subscribes, and after every command.

| Offset | Size | Field |
|---|---|---|
| 0 | 1 | Protocol version, `1` |
| 1 | 1 | Ack seq: the `seq` of the last command processed, `0` after boot |
| 2 | 1 | Ack status: `0` ok, `1` unknown command, `2` bad argument |
| 3 | 1 | Running tracker, signed; `-1` = none |
| 4 | 4 | Device id: random, created on first boot. A new id means the tracker's memory was wiped |
| 8 | 4 | Boot id: random, new on every power-up |
| 12 | 1 | `T`, number of trackers |
| 13 | 1 | `C`, number of countdowns |
| 14 | 8·T | Per tracker: `u32` shown seconds, `u32` total seconds |
| 14 + 8·T | 9·C | Per countdown: `u32` duration seconds, `i32` remaining seconds, `u8` running (0/1) |

**Shown seconds** is what the display shows. A long OK press on the tracker or a *reset tracker* command sets it to zero.

**Total seconds** counts every second the tracker has ever run and is never reset. Within one boot it only grows. After a power cut it can step back by up to a minute, because state is saved to flash once a minute. The boot id tells the client when that may have happened.

If the negotiated MTU is too small for the whole snapshot, notifications arrive truncated. The client then reads the characteristic instead, since a long read returns the full value.

## Commands

A command is `[opcode, seq, arguments…]`. The client picks `seq`. Once the tracker has applied the command, it copies `seq` into *ack seq* and publishes the new state, so the client knows which snapshot already reflects it. Commands are applied in the order they were written.

| Opcode | Bytes | Effect |
|---|---|---|
| `0x01` reset tracker | `01 seq tracker` | Shown seconds of `tracker` (0-based) → 0. The total is untouched; a running tracker keeps running. |
| `0x02` set countdown | `02 seq countdown s0 s1 s2 s3` | Duration of `countdown` (0-based) → `u32` seconds, 60…86400, rounded down to a whole minute. A stopped countdown shows the new duration at once. A running one finishes its current cycle first, the same as a long press on the tracker. |

The tracker beeps briefly after each command.

## How the app counts time

The app links each of its tasks to at most one tracker and remembers `(device id, boot id, last total)` for that link. For every new snapshot it adds `total − last total` to the task. Then:

- **The total grew or stayed the same**: that difference is time worked on the task.
- **The total shrank and the boot id changed**: the tracker restarted from an older save. The app takes the new total as the starting point and credits nothing.
- **The total shrank but the boot id is the same**: the snapshot is stale. The app ignores it.
- **The device id changed**: the tracker was wiped, or it is a different tracker. The app starts over from the new total and credits nothing.

When the app links a task to a tracker, it sends *reset tracker* and waits for the ack. It then takes `total − shown` from that snapshot as the starting point. The tracker's display starts from zero, while the task keeps everything it had before. Because the starting point comes from the total, time that tracker counted before the link can never end up in the task. A long OK press on a linked tracker only clears the display, so the task loses nothing.
