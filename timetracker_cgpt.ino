// ESP32-C3 TimeTracker: 5 task time trackers + 3 cyclic countdowns on an SSD1306 OLED
// Only one tracker runs at a time: starting another task stops the current one.
// Three countdowns (default 5 min, 30 min, 1.5 h) restart automatically at zero.
//
// ──────────────────────────────────────────────────────────────────────────────
// Hardware (adjust pins if needed):
//  OLED SSD1306 128x64 I2C (1.3"): SDA=GPIO6, SCL=GPIO7, address 0x3C
//  OLED power: 3.3V → VDD, GND → GND
//  OLED module pin order, left to right: VDD  GND  SCK  SDA
//  Buttons (to GND, using internal pull-up): OK=GPIO2, NEXT=GPIO3, BACK=GPIO4
//  Passive buzzer (no built-in oscillator), driven via a bipolar transistor: GPIO5
//
// Required Libraries (Arduino IDE → Library Manager):
//  - Adafruit GFX
//  - Adafruit SSD1306
//  - NimBLE-Arduino (2.x)
//  - Preferences (included in ESP32 core)
// Board: ESP32C3 Dev Module (Arduino core for ESP32 v3.0+, needed for ledcAttach())
//
// ── Controls ──────────────────────────────────────────────────────────────────
// Long press = hold for ~1 s; it is recognized when the button is released.
//
// TRACKERS tab:
//   OK   (short)       → Start/stop selected tracker
//   NEXT (short)       → Select next tracker
//   BACK (short)       → Select previous tracker
//   OK   (long)        → Reset time of selected tracker
//
// COUNTDOWNS tab:
//   OK   (short)       → Start/stop selected countdown
//   NEXT (short)       → Select next countdown
//   BACK (short)       → Select previous countdown
//   OK   (long)        → Reset to factory preset (5 min, 30 min, 1.5 h)
//   NEXT (long)        → −5 minutes (minimum 1 minute)
//   BACK (long)        → +5 minutes (maximum 24 hours)
//
// Tab switching: hold NEXT + BACK together for ~1 second.
//
// NVS Storage: Saves trackers/countdowns state, current tab, and selections
// to flash memory (NVS — non-volatile storage; key-value). Restores on reboot.
//
// ── Bluetooth LE ──────────────────────────────────────────────────────────────
// Advertises as "TimeTracker" for the Android companion app (android/). The
// tracker knows nothing about the app's tasks: it publishes its timers and
// accepts two commands, "reset tracker N" and "set countdown N to M minutes".
// Besides the time on the display, every tracker keeps a total that no button
// ever resets; the app counts task time from it. Byte layout:
// docs/ble-protocol.md
// ──────────────────────────────────────────────────────────────────────────────

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Preferences.h>
#include <NimBLEDevice.h>

// ── Pinout (edit to match your board) ─────────────────────────────────────────
constexpr uint8_t PIN_OLED_SDA = 6;
constexpr uint8_t PIN_OLED_SCL = 7;
constexpr uint8_t PIN_BTN_OK   = 2;
constexpr uint8_t PIN_BTN_NEXT = 3;
constexpr uint8_t PIN_BTN_BACK = 4;
constexpr uint8_t PIN_BUZZER   = 5;  // passive buzzer preferred

// ── Display ───────────────────────────────────────────────────────────────────
constexpr int OLED_W       = 128;
constexpr int OLED_H       = 64;   // 1.3" OLED 128x64  ← change here for a different panel
constexpr int VISIBLE_ROWS = 4;    // rows that fit on the screen
constexpr int ROW_H        = OLED_H / VISIBLE_ROWS; // row height in pixels (16)
// 1.3" modules come with an SSD1306 or an SH1106 controller and look the same; this one is an SSD1306.
// The wrong driver shifts the image or leaves the screen dark: see README → Troubleshooting.
Adafruit_SSD1306 display(OLED_W, OLED_H, &Wire, -1);

// ── Config ────────────────────────────────────────────────────────────────────
constexpr uint8_t  MAX_TRACKERS   = 5;
constexpr uint8_t  MAX_COUNTDOWNS = 3;
constexpr uint32_t AUTOSAVE_MS    = 60000;
constexpr uint32_t LONG_MS        = 1000;  // long press = ~1 s
constexpr uint32_t TAB_SWITCH_MS  = 800;   // holding down two buttons to change tabs

Preferences prefs;

// ── Data structures ───────────────────────────────────────────────────────────
struct Tracker {
  char     name[12];
  uint32_t seconds = 0;   // accumulated seconds (shown, reset by long OK)
  uint32_t total   = 0;   // all seconds ever counted; never reset (used by the app)
};

struct Countdown {
  uint32_t presetSec = 0; // factory default duration
  uint32_t userSec = 0; // last user configured duration
  int32_t  remainSec = 0; // remaining (can be negative briefly when ringing)
  bool     running   = false;
};

Tracker   trackers[MAX_TRACKERS];
Countdown countdowns[MAX_COUNTDOWNS];

enum Tab { TAB_TRACKERS, TAB_COUNTDOWNS };
Tab currentTab = TAB_TRACKERS;

int8_t selTracker   = 0;
int8_t activeIdx    = -1;  // index of running tracker, -1 if none
int8_t selCountdown = 0;

// Timing
uint32_t lastTickMs   = 0; // for 1 Hz tick
uint32_t lastAutosave = 0;

// Identity reported to the app (see docs/ble-protocol.md)
uint32_t deviceId = 0;  // random, created on first boot; changes only when NVS is wiped
uint32_t bootId   = 0;  // random, new on every boot

// Buttons
struct Btn {
  uint8_t  pin;
  bool     last;
  uint32_t tDown;
};
Btn bOK  {PIN_BTN_OK,   true, 0};
Btn bNEXT{PIN_BTN_NEXT, true, 0};
Btn bBACK{PIN_BTN_BACK, true, 0};

// ── Utils ─────────────────────────────────────────────────────────────────────
// format HH:MM:SS
String fmtHMS(uint32_t s) {
  uint32_t h = s / 3600; s %= 3600;
  uint32_t m = s / 60;   s %= 60;
  char buf[16];
  snprintf(buf, sizeof(buf), "%02lu:%02lu:%02lu", (unsigned long)h, (unsigned long)m, (unsigned long)s);
  return String(buf);
}

void beep(uint16_t freq, uint16_t ms) {
  ledcAttach(PIN_BUZZER, freq, 10);
  ledcWrite(PIN_BUZZER, 512);
  delay(ms);
  ledcWrite(PIN_BUZZER, 0);
  ledcDetach(PIN_BUZZER);
}

// ── Persistence (NVS) ─────────────────────────────────────────────────────────
void saveAll() {
  prefs.begin("ttracker", false);

  prefs.putUChar("tab",  (uint8_t)currentTab);
  prefs.putChar ("selT", selTracker);
  prefs.putChar ("actT", activeIdx);
  prefs.putChar ("selC", selCountdown);

  for (uint8_t i = 0; i < MAX_TRACKERS; i++) {
    char k1[8], k2[8], k6[8];
    snprintf(k1, 8, "tn%u", i);
    snprintf(k2, 8, "ts%u", i);
    snprintf(k6, 8, "tt%u", i);   // total
    prefs.putString(k1, trackers[i].name);
    prefs.putUInt(k2, trackers[i].seconds);
    prefs.putUInt(k6, trackers[i].total);
  }

  for (uint8_t i = 0; i < MAX_COUNTDOWNS; i++) {
    char k3[8], k4[8], k5[8];
    snprintf(k3, 8, "cu%u", i);   // userSec
    snprintf(k4, 8, "cr%u", i);   // remainSec
    snprintf(k5, 8, "cl%u", i);   // running
    prefs.putUInt(k3, countdowns[i].userSec);
    prefs.putInt(k4, countdowns[i].remainSec);
    prefs.putBool(k5, countdowns[i].running);
  }

  prefs.end();
}

void loadAll() {
  prefs.begin("ttracker", true);

  currentTab   = (Tab)prefs.getUChar("tab", 0);
  selTracker   = prefs.getChar("selT", 0);
  activeIdx    = prefs.getChar("actT", -1);
  selCountdown = prefs.getChar("selC", 0);
  deviceId     = prefs.getUInt("dev", 0);

  // Trackers
  for (uint8_t i = 0; i < MAX_TRACKERS; i++) {
    char k1[8], k2[8], k6[8];
    snprintf(k1, 8, "tn%u", i);
    snprintf(k2, 8, "ts%u", i);
    snprintf(k6, 8, "tt%u", i);
    String n = prefs.getString(k1, "");
    if (n.length() == 0) {
      snprintf(trackers[i].name, sizeof(trackers[i].name), "T%u", i + 1);
      trackers[i].seconds = 0;
    } else {
      strncpy(trackers[i].name, n.c_str(), sizeof(trackers[i].name));
      trackers[i].name[sizeof(trackers[i].name) - 1] = '\0';
      trackers[i].seconds = prefs.getUInt(k2, 0);
    }
    // Firmware without totals saved none: start them from the shown time
    trackers[i].total = prefs.getUInt(k6, trackers[i].seconds);
  }

  // Countdowns: factory presets 5 min, 30 min, 1.5 hours
  for (uint8_t i = 0; i < MAX_COUNTDOWNS; i++) {
    char k3[8], k4[8], k5[8];
    snprintf(k3, 8, "cu%u", i);   // userSec
    snprintf(k4, 8, "cr%u", i);   // remainSec
    snprintf(k5, 8, "cl%u", i);   // running

    uint32_t defPreset;
    if (i == 0)      defPreset = 5 * 60;   // 5 min
    else if (i == 1) defPreset = 30 * 60;  // 30 min
    else if (i == 2) defPreset = 90 * 60;  // 1.5 hours
    else             defPreset = 5 * 60;

    countdowns[i].presetSec = defPreset;    // factory default, not stored
    countdowns[i].userSec = prefs.getUInt(k3, defPreset);
    countdowns[i].remainSec = prefs.getInt (k4, (int32_t)countdowns[i].presetSec);
    countdowns[i].running   = prefs.getBool(k5, false);
  }

  prefs.end();
}

// ── Button handling ───────────────────────────────────────────────────────────
bool readBtn(Btn &b, bool &pressed, bool &released, bool &longPress) {
  bool level = digitalRead(b.pin); // with INPUT_PULLUP: 1=idle, 0=pressed
  uint32_t now = millis();
  pressed = released = longPress = false;

  if (b.last && !level) {     // went down
    b.tDown = now;
    pressed = true;  // edge
  } else if (!b.last && level) { // went up
    released = true;
    if (now - b.tDown >= LONG_MS) {  // long press recognized on release
      longPress = true;
    }
  }

  b.last = level;
  return !level; // current is pressed
}

// ── Actions ───────────────────────────────────────────────────────────────────
void switchToTab(Tab t) {
  currentTab = t;
  beep(1600, 40);
}

void startTracker(int8_t i) {
  if (i < 0 || i >= MAX_TRACKERS) return;
  activeIdx = i;
  beep(1200, 30);
}

void stopTracker() {
  activeIdx = -1;
  beep(800, 30);
}

void toggleTracker() {
  if (activeIdx == selTracker) stopTracker();
  else                         startTracker(selTracker);
}

// Clears the shown time only; the total keeps counting (the app relies on it)
void resetTracker(int8_t i) {
  if (i < 0 || i >= MAX_TRACKERS) return;
  trackers[i].seconds = 0;
  if (activeIdx == i) {
    lastTickMs = millis();
  }
  beep(600, 30);
}

void toggleCountdown() {
  auto &cd = countdowns[selCountdown];
  if (cd.running) {
    cd.running = false;
    beep(800, 30);
  } else {
    if (cd.remainSec <= 0) cd.remainSec = cd.userSec;
    cd.running = true;
    beep(1200, 30);
  }
}

// Reset countdown to factory preset (ignores userSec)
// Long OK press on the countdowns tab
void resetCountdown() {
  auto &cd = countdowns[selCountdown];
  cd.userSec = cd.presetSec; // to current preset
  cd.remainSec = cd.presetSec; // to current preset
  cd.running   = false;
  beep(700, 30);
}

// Adjust user-configured duration by deltaSec (±5 min steps)
void adjustCountdown(int deltaSec) {
  auto &cd = countdowns[selCountdown];
  int32_t np = (int32_t)cd.remainSec + deltaSec;
  if (np < 60)        np = 60;        // at least 1 minute
  if (np > 24 * 3600) np = 24 * 3600; // no more than 24 hours
  cd.userSec = (uint32_t)np;
  if (!cd.running) {
    cd.remainSec = cd.userSec;
  }
  beep(1000, 20);
}

// Set a countdown's duration exactly (from the app, whole minutes).
// Like adjustCountdown(), a running countdown finishes its current cycle first.
void setCountdownDuration(uint8_t i, uint32_t sec) {
  auto &cd = countdowns[i];
  cd.userSec = sec;
  if (!cd.running) {
    cd.remainSec = cd.userSec;
  }
  beep(1000, 20);
}

// ── Bluetooth LE ──────────────────────────────────────────────────────────────
// NimBLE runs in its own FreeRTOS task. To keep the timers single-threaded, its
// callbacks only queue commands and copy the last published snapshot; all
// timer state is touched in loop() alone.
#define BLE_NAME       "TimeTracker"
#define BLE_SVC_UUID   "603a0001-2e1a-4102-9367-4c8626517b1d"
#define BLE_STATE_UUID "603a0002-2e1a-4102-9367-4c8626517b1d"  // read, notify
#define BLE_CMD_UUID   "603a0003-2e1a-4102-9367-4c8626517b1d"  // write

constexpr uint8_t  BLE_PROTOCOL      = 1;
constexpr uint8_t  CMD_RESET_TRACKER = 0x01;  // [op, seq, tracker]
constexpr uint8_t  CMD_SET_COUNTDOWN = 0x02;  // [op, seq, countdown, seconds u32 LE]
constexpr uint8_t  ACK_OK = 0, ACK_UNKNOWN = 1, ACK_BAD_ARG = 2;
constexpr uint16_t BLE_CMD_MAX = 8;
constexpr uint16_t STATE_LEN   = 14 + MAX_TRACKERS * 8 + MAX_COUNTDOWNS * 9;

struct BleCmd {
  uint8_t len;
  uint8_t data[BLE_CMD_MAX];
};

QueueHandle_t         bleQueue    = nullptr;
NimBLECharacteristic *bleStateChr = nullptr;
volatile bool         bleSubscribed = false;
volatile bool         blePushNow    = false;  // send the snapshot even if unchanged
portMUX_TYPE          bleMux = portMUX_INITIALIZER_UNLOCKED;
uint8_t               bleState[STATE_LEN];    // last published snapshot, guarded by bleMux
uint8_t               bleAckSeq    = 0;       // seq of the last processed command
uint8_t               bleAckStatus = ACK_OK;

void put32(uint8_t *p, uint32_t v) {
  p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24;
}

uint32_t get32(const uint8_t *p) {
  return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
}

void buildState(uint8_t *b) {
  b[0] = BLE_PROTOCOL;
  b[1] = bleAckSeq;
  b[2] = bleAckStatus;
  b[3] = (uint8_t)activeIdx;  // -1 → 0xFF
  put32(b + 4, deviceId);
  put32(b + 8, bootId);
  b[12] = MAX_TRACKERS;
  b[13] = MAX_COUNTDOWNS;
  uint8_t *p = b + 14;
  for (uint8_t i = 0; i < MAX_TRACKERS; i++, p += 8) {
    put32(p,     trackers[i].seconds);
    put32(p + 4, trackers[i].total);
  }
  for (uint8_t i = 0; i < MAX_COUNTDOWNS; i++, p += 9) {
    put32(p,     countdowns[i].userSec);
    put32(p + 4, (uint32_t)countdowns[i].remainSec);
    p[8] = countdowns[i].running ? 1 : 0;
  }
}

class BleServerCallbacks : public NimBLEServerCallbacks {
  void onDisconnect(NimBLEServer *, NimBLEConnInfo &, int) override {
    bleSubscribed = false;
  }
};

class BleStateCallbacks : public NimBLECharacteristicCallbacks {
  void onRead(NimBLECharacteristic *chr, NimBLEConnInfo &) override {
    uint8_t copy[STATE_LEN];
    portENTER_CRITICAL(&bleMux);
    memcpy(copy, bleState, STATE_LEN);
    portEXIT_CRITICAL(&bleMux);
    chr->setValue(copy, STATE_LEN);
  }
  void onSubscribe(NimBLECharacteristic *, NimBLEConnInfo &, uint16_t subValue) override {
    bleSubscribed = (subValue & 1) != 0;
    blePushNow = true;
  }
};

class BleCmdCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *chr, NimBLEConnInfo &) override {
    NimBLEAttValue v = chr->getValue();
    BleCmd cmd{};
    cmd.len = v.size() < BLE_CMD_MAX ? v.size() : BLE_CMD_MAX;
    memcpy(cmd.data, v.data(), cmd.len);
    xQueueSend(bleQueue, &cmd, 0);
  }
};

BleServerCallbacks bleServerCallbacks;
BleStateCallbacks  bleStateCallbacks;
BleCmdCallbacks    bleCmdCallbacks;

void bleBegin() {
  bleQueue = xQueueCreate(8, sizeof(BleCmd));
  NimBLEDevice::init(BLE_NAME);

  // With the radio on, esp_random() is a true random number
  while (bootId == 0) bootId = esp_random();
  if (deviceId == 0) {
    while (deviceId == 0) deviceId = esp_random();
    prefs.begin("ttracker", false);
    prefs.putUInt("dev", deviceId);
    prefs.end();
  }
  buildState(bleState);

  NimBLEServer *server = NimBLEDevice::createServer();
  server->setCallbacks(&bleServerCallbacks);
  server->advertiseOnDisconnect(true);

  NimBLEService *svc = server->createService(BLE_SVC_UUID);
  bleStateChr = svc->createCharacteristic(BLE_STATE_UUID,
                                          NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY, STATE_LEN);
  bleStateChr->setCallbacks(&bleStateCallbacks);
  NimBLECharacteristic *cmdChr = svc->createCharacteristic(BLE_CMD_UUID, NIMBLE_PROPERTY::WRITE, BLE_CMD_MAX);
  cmdChr->setCallbacks(&bleCmdCallbacks);

  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  adv->enableScanResponse(true);   // before setName(): the name goes to the scan response
  adv->setName(BLE_NAME);
  adv->addServiceUUID(BLE_SVC_UUID);
  adv->start();
}

// Apply the commands the app has written since the last loop
void bleHandleCommands() {
  BleCmd cmd;
  while (xQueueReceive(bleQueue, &cmd, 0) == pdTRUE) {
    if (cmd.len < 2) continue;  // no seq to acknowledge
    uint8_t op = cmd.data[0];
    uint8_t status;

    if (op == CMD_RESET_TRACKER) {
      if (cmd.len == 3 && cmd.data[2] < MAX_TRACKERS) {
        resetTracker(cmd.data[2]);
        status = ACK_OK;
      } else {
        status = ACK_BAD_ARG;
      }
    } else if (op == CMD_SET_COUNTDOWN) {
      uint32_t sec = (cmd.len == 7) ? get32(cmd.data + 3) : 0;
      if (cmd.len == 7 && cmd.data[2] < MAX_COUNTDOWNS && sec >= 60 && sec <= 24 * 3600) {
        setCountdownDuration(cmd.data[2], sec - sec % 60);  // whole minutes
        status = ACK_OK;
      } else {
        status = ACK_BAD_ARG;
      }
    } else {
      status = ACK_UNKNOWN;
    }

    bleAckSeq    = cmd.data[1];
    bleAckStatus = status;
    blePushNow   = true;  // acknowledge even if nothing changed
  }
}

// Publish the snapshot when it changes (about once a second while anything runs)
void blePublish() {
  uint8_t buf[STATE_LEN];
  buildState(buf);

  portENTER_CRITICAL(&bleMux);
  bool changed = memcmp(buf, bleState, STATE_LEN) != 0;
  if (changed) memcpy(bleState, buf, STATE_LEN);
  portEXIT_CRITICAL(&bleMux);

  if ((changed || blePushNow) && bleSubscribed) {
    blePushNow = false;
    bleStateChr->notify(buf, STATE_LEN);
  }
}

// ── Drawing ───────────────────────────────────────────────────────────────────
// Draws one list row (shared by both tabs).
// rowIndex: 0..VISIBLE_ROWS-1, label: "T1"/"C1", selected: inverted row,
// active: running (dot after the label), seconds: time to display
void drawRow(int rowIndex, const String &label, bool selected, bool active, uint32_t seconds) {
  int baseY = rowIndex * ROW_H;

  // Row background
  if (selected) {
    display.fillRect(0, baseY, OLED_W, ROW_H, SSD1306_WHITE);
  }

  // Colors
  uint16_t fg = selected ? SSD1306_BLACK : SSD1306_WHITE;

  // 1) Title (T1/C1) in font 1
  display.setTextSize(1);
  display.setTextColor(fg);
  int16_t x1, y1;
  uint16_t w1, h1;
  display.getTextBounds(label, 0, 0, &x1, &y1, &w1, &h1);
  int labelX = 2;  // left padding
  int labelY = baseY + 4; // top padding
  display.setCursor(labelX, labelY);
  display.print(label);

  // Circle coords ●
  int dotX = labelX + w1 + 8;
  int dotY = baseY + ROW_H / 2; // center by row height

  if (active) {
    display.fillCircle(dotX, dotY, 2, fg);
  }

  // 2) Time in font 2 (right)
  String t = fmtHMS(seconds);
  display.setTextSize(2);
  display.setTextColor(fg);
  int16_t x2, y2;
  uint16_t w2, h2;
  display.getTextBounds(t, 0, 0, &x2, &y2, &w2, &h2);

  int timeX = OLED_W - w2;  // right-aligned
  int timeY = baseY + 1;        // top padding
  display.setCursor(timeX, timeY);
  display.print(t);
}

// Trackers — scrollable list, VISIBLE_ROWS rows around the selected one
void drawTrackers() {
  display.clearDisplay();

  int visible = (MAX_TRACKERS < VISIBLE_ROWS) ? MAX_TRACKERS : VISIBLE_ROWS;

  int start = selTracker - visible / 2;
  if (start < 0) start = 0;
  if (start > MAX_TRACKERS - visible) start = MAX_TRACKERS - visible;

  for (int row = 0; row < visible; ++row) {
    int idx = start + row;
    String label = String("T") + String(idx + 1);
    bool selected = (idx == selTracker);
    bool active   = (idx == activeIdx);

    uint32_t secs = trackers[idx].seconds;
    drawRow(row, label, selected, active, secs);
  }

  display.display();
}

// Countdowns — scrollable list, VISIBLE_ROWS rows around the selected one
void drawCountdowns() {
  display.clearDisplay();

  int visible = (MAX_COUNTDOWNS < VISIBLE_ROWS) ? MAX_COUNTDOWNS : VISIBLE_ROWS;

  int start = selCountdown - visible / 2;
  if (start < 0) start = 0;
  if (start > MAX_COUNTDOWNS - visible) start = MAX_COUNTDOWNS - visible;

  for (int row = 0; row < visible; ++row) {
    int idx = start + row;
    String label = String("C") + String(idx + 1);
    bool selected = (idx == selCountdown);
    bool active   = countdowns[idx].running;

    uint32_t secs = (countdowns[idx].remainSec > 0) ? (uint32_t)countdowns[idx].remainSec : (uint32_t)countdowns[idx].userSec;
    drawRow(row, label, selected, active, secs);
  }

  display.display();
}

void drawTab() {
  if (currentTab == TAB_TRACKERS) drawTrackers();
  else drawCountdowns();
}

// ── Setup / Loop ──────────────────────────────────────────────────────────────
void setup() {
  // Factory reset: uncomment, flash once, then comment out again and re-flash.
  /*
  prefs.begin("ttracker", false);
  prefs.clear();
  prefs.end();
  */
  pinMode(PIN_BTN_OK,   INPUT_PULLUP);
  pinMode(PIN_BTN_NEXT, INPUT_PULLUP);
  pinMode(PIN_BTN_BACK, INPUT_PULLUP);
  pinMode(PIN_BUZZER,   OUTPUT);

  Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {   // 0x3C is the usual address; try 0x3D if it doesn't work
    for (;;) {                         // no display: beep forever
      beep(400, 150);
      delay(400);
    }
  }

  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  loadAll();
  bleBegin();

  lastTickMs   = millis();
  lastAutosave = millis();

  // Splash
  display.setCursor(8, 8);
  display.println("ESP32-C3 TimeTracker");
  display.setCursor(50, 18);
  display.println("v3.0");
  display.display();
  beep(1200, 40); delay(60); beep(1600, 50);
  delay(2000);
}

void loop() {
  uint32_t now = millis();

  // Buttons (p = pressed edge, r = released, l = released after a long press)
  bool pOK=false,  rOK=false,  lOK=false;
  bool pNX=false,  rNX=false,  lNX=false;
  bool pBK=false,  rBK=false,  lBK=false;

  readBtn(bOK, pOK, rOK, lOK);
  bool nxHeld = readBtn(bNEXT, pNX, rNX, lNX);
  bool bkHeld = readBtn(bBACK, pBK, rBK, lBK);

  // Switching tabs with two buttons with a "latch"
  static uint32_t bothDownAt      = 0;
  static bool     bothWasDown     = false;
  static bool     tabSwitchLatched = false;

  bool both = nxHeld && bkHeld;
  if (both && !bothWasDown) {
    bothDownAt = now;
    tabSwitchLatched = false;
  }

  if (both && !tabSwitchLatched && (now - bothDownAt) >= TAB_SWITCH_MS) {
    // once for holding
    switchToTab(currentTab == TAB_TRACKERS ? TAB_COUNTDOWNS : TAB_TRACKERS);
    tabSwitchLatched = true;
  }

  if (!both) {
    tabSwitchLatched = false;
  }

  bothWasDown = both;

  // Once NEXT and BACK have been held together, ignore their releases until both
  // are up again. Otherwise letting go after a tab switch would also fire their
  // short/long-press actions (move the selection, change a countdown by ±5 min).
  static bool chordHeld = false;
  if (both) chordHeld = true;
  if (chordHeld) {
    rNX = lNX = rBK = lBK = false;
    if (!nxHeld && !bkHeld) chordHeld = false;
  }

  // Tab logic
  if (currentTab == TAB_TRACKERS) {  // TRACKERS
    if (rNX && !lNX) { selTracker = (selTracker + 1) % MAX_TRACKERS;  beep(1500, 15); }
    if (rBK && !lBK) { selTracker = (selTracker - 1 + MAX_TRACKERS) % MAX_TRACKERS;  beep(1500, 15); }
    if (rOK && !lOK) { toggleTracker(); }
    if (lOK) { resetTracker(selTracker); }
  } else { // COUNTDOWNS
    if (rNX && !lNX) { selCountdown = (selCountdown + 1) % MAX_COUNTDOWNS;  beep(1500, 15); }
    if (rBK && !lBK) { selCountdown = (selCountdown - 1 + MAX_COUNTDOWNS) % MAX_COUNTDOWNS;  beep(1500, 15); }
    if (rOK && !lOK) { toggleCountdown(); }
    if (lOK) { resetCountdown(); }
    if (lNX) { adjustCountdown(-5 * 60); }  // NEXT (long) → shorter
    if (lBK) { adjustCountdown(+5 * 60); }  // BACK (long) → longer
  }

  // Commands from the app
  bleHandleCommands();

  // 1 Hz tick: count trackers up and countdowns down
  if (now - lastTickMs >= 1000) {
    uint32_t dt = (now - lastTickMs) / 1000;
    lastTickMs += dt * 1000;

    if (activeIdx >= 0) {
      trackers[activeIdx].seconds += dt;
      trackers[activeIdx].total   += dt;
    }

    for (uint8_t i = 0; i < MAX_COUNTDOWNS; i++) {
      if (countdowns[i].running) {
        countdowns[i].remainSec -= (int32_t)dt;
        if (countdowns[i].remainSec <= 0) {
          // loop from last user-configured duration and beep each cycle
          for (uint8_t k = 0; k < 3; k++) {
            beep(200, 120);
            delay(150);
          }
          countdowns[i].remainSec += (int32_t)countdowns[i].userSec;
        }
      }
    }
  }

  // Autosave
  if (now - lastAutosave >= AUTOSAVE_MS) {
    lastAutosave = now;
    saveAll();
  }

  // Tell the app
  blePublish();

  // Drawing
  drawTab();
}