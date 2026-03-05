// ESP32-C3 Task Timer: multi-counter time tracker + 3 countdown timers on SH1106 OLED
// One active tracker at a time; fast switching between tasks.
// Three countdown timers: default 5 min, 30 min, 1.5 hours. Cyclic (auto-restart).
//
// ──────────────────────────────────────────────────────────────────────────────
// Hardware (adjust pins if needed):
//  OLED SH1106 128x64 I2C (1.3"): SDA=GPIO6, SCL=GPIO7, address 0x3C
//  Питание экрана: 3.3V → VDD,  GND → GND
//  Порядок пинов на плате слева направо: VDD  GND  SCK  SDA
//  Buttons (to GND, using internal pull-up): OK=GPIO2, NEXT=GPIO3, BACK=GPIO4
//  Buzzer passive (без встроенного генератора): GPIO5
//
// Required Libraries (Arduino IDE → Library Manager):
//  - Adafruit GFX
//  - Adafruit SH110X   ← вместо SSD1306!
//  - Preferences (included in ESP32 core)
// Board: ESP32C3 Dev Module (Arduino core for ESP32 v2.0+)
//
// ── Controls ───────────────────────────────────────────────────────────────
// TRACKERS tab:
//   OK   (short)       → Start/stop selected tracker
//   NEXT (short)       → Select next tracker
//   BACK (short)       → Select previous tracker
//   OK   (long ~1 s)   → Reset time of selected tracker
//
// COUNTDOWNS tab:
//   OK   (short)       → Start/stop selected countdown
//   NEXT (short)       → Select next countdown
//   BACK (short)       → Select previous countdown
//   OK   (long)        → Reset to preset (5 min,  30 min, 1.5 hour)
//   NEXT (long)        → +5 minutes
//   BACK (long)        → −5 minutes (minimum 1 minute)
//
// Tab switching: hold NEXT + BACK together for ~1 second.
//
// NVS Storage: Saves trackers/countdowns state, current tab, and selections
// to flash memory (NVS — non-volatile storage; key-value). Restores on reboot.
// ──────────────────────────────────────────────────────────────────────────────

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SH110X.h>
#include <Preferences.h>

// ── Pinout (edit to match your board) ─────────────────────────────────────────────────
constexpr uint8_t PIN_OLED_SDA = 6;
constexpr uint8_t PIN_OLED_SCL = 7;
constexpr uint8_t PIN_BTN_OK   = 2;
constexpr uint8_t PIN_BTN_NEXT = 3;
constexpr uint8_t PIN_BTN_BACK = 4;
constexpr uint8_t PIN_BUZZER   = 5;  // passive buzzer preferred

// ── Display ─────────────────────────────────────────────────--────────────────
constexpr int OLED_W       = 128;
constexpr int OLED_H       = 64;   // 1.3" OLED 128x64  ← меняй сюда если другой экран
constexpr int VISIBLE_ROWS = 4;    // сколько строк влезает на экран
constexpr int ROW_H        = OLED_H / VISIBLE_ROWS; // высота одной строки в пикселях (16)
Adafruit_SH1106G display(OLED_W, OLED_H, &Wire, -1);

// ── Config ──────────────────────────────────────────────-────────────────────
constexpr uint8_t  MAX_TRACKERS   = 5;
constexpr uint8_t  MAX_COUNTDOWNS = 3;
constexpr uint32_t AUTOSAVE_MS    = 60000;
constexpr uint32_t LONG_MS        = 1000;  // long press = ~1 сек
constexpr uint32_t TAB_SWITCH_MS  = 800;   // holding down two buttons to change tabs

Preferences prefs;

// ── Data structures ───────────────────────────────────────────────────────────
struct Tracker {
  char     name[12];
  uint32_t seconds = 0;   // accumulated seconds
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

// Buttons
struct Btn {
  uint8_t  pin;
  bool     last;
  uint32_t tDown;
};
Btn bOK  {PIN_BTN_OK,   true, 0};
Btn bNEXT{PIN_BTN_NEXT, true, 0};
Btn bBACK{PIN_BTN_BACK, true, 0};

// ── utils ─────────────────────────────────────────────────────────────────
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

// ── Persistance, NVS ─────────────────────────────────────────────────────────
void saveAll() {
  prefs.begin("ttracker", false);

  prefs.putUChar("tab",  (uint8_t)currentTab);
  prefs.putChar ("selT", selTracker);
  prefs.putChar ("actT", activeIdx);
  prefs.putChar ("selC", selCountdown);

  for (uint8_t i = 0; i < MAX_TRACKERS; i++) {
    char k1[8], k2[8];
    snprintf(k1, 8, "tn%u", i);
    snprintf(k2, 8, "ts%u", i);
    prefs.putString(k1, trackers[i].name);
    prefs.putUInt(k2, trackers[i].seconds);
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

  // Trackers
  for (uint8_t i = 0; i < MAX_TRACKERS; i++) {
    char k1[8], k2[8];
    snprintf(k1, 8, "tn%u", i);
    snprintf(k2, 8, "ts%u", i);
    String n = prefs.getString(k1, "");
    if (n.length() == 0) {
      snprintf(trackers[i].name, sizeof(trackers[i].name), "T%u", i + 1);
      trackers[i].seconds = 0;
    } else {
      strncpy(trackers[i].name, n.c_str(), sizeof(trackers[i].name));
      trackers[i].name[sizeof(trackers[i].name) - 1] = '\0';
      trackers[i].seconds = prefs.getUInt(k2, 0);
    }
  }

  // Countdowns: 0 → 5 min, 1 → 1.5 hours by default
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

// ── Button handling ─────────────────────────────────────────────────────────
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

bool bothHeld() {
  return !digitalRead(bNEXT.pin) && !digitalRead(bBACK.pin);
}

// ── Actions ───────────────────────────────────────────────────────────────
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

void resetTracker() {
  trackers[selTracker].seconds = 0;
  if (activeIdx == selTracker) {
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

// Reset countdown to factory preset (irnores userSec)
// Long OK press in countdown tabs
void resetCountdown() {
  auto &cd = countdowns[selCountdown];
  cd.userSec = cd.presetSec; // to current preset
  cd.remainSec = cd.presetSec; // to current preset
  cd.running   = false;
  beep(700, 30);
}

// Adjust user-configured duration by deltaSec (±5 min steps, etc.)
void adjustCountdown(int deltaSec) {
  auto &cd = countdowns[selCountdown];
  // int32_t np = (int32_t)cd.presetSec + deltaSec;
  int32_t np = (int32_t)cd.remainSec + deltaSec;
  if (np < 60)        np = 60;        // at least 1 minute
  if (np > 24 * 3600) np = 24 * 3600; // no more than 24 hours
  cd.userSec = (uint32_t)np;
  if (!cd.running) {
    // cd.remainSec = cd.presetSec;
    cd.remainSec = cd.userSec;
  }
  beep(1000, 20);
}

// ── Drawing two lines (general logic) ─────────────────────────────────────
// rowIndex: 0 or 1, label: “T1”/“C1”, active: whether this is active
// seconds: time in seconds (for display)
void drawRow(int rowIndex, const String &label, bool selected, bool active, uint32_t seconds) {
  int baseY = rowIndex * ROW_H;

  // Row background
  if (selected) {
    display.fillRect(0, baseY, OLED_W, ROW_H, SH110X_WHITE);
  }

  // Colors
  uint16_t fg = selected ? SH110X_BLACK : SH110X_WHITE;

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

  int timeX = OLED_W - w2;  // press to the right
  int timeY = baseY + 1;        // top padding
  display.setCursor(timeX, timeY);
  display.print(t);
}

// Trackers — scrollable list, VISIBLE_ROWS строк вокруг выбранного
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

// Countdowns — scrollable list, VISIBLE_ROWS строк
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

// ── Setup / Loop ────────────────────────────────────────────────────────────
void setup() {
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
  if (!display.begin(0x3C, true)) {   // 0x3C — стандартный адрес; если не работает попробуй 0x3D
    for (;;) {
      beep(400, 150);
      delay(400);
    }
  }

  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SH110X_WHITE);

  loadAll();

  lastTickMs   = millis();
  lastAutosave = millis();

  // Splash
  display.setCursor(8, 8);
  display.println("ESP32-C3 TimeTracker");
  display.setCursor(50, 18);
  display.println("v2.2");
  display.display();
  beep(1200, 40); delay(60); beep(1600, 50);
  delay(2000);
}

void loop() {
  uint32_t now = millis();

  // Buttons
  bool pOK=false,  rOK=false,  lOK=false;
  bool pNX=false,  rNX=false,  lNX=false;
  bool pBK=false,  rBK=false,  lBK=false;

  readBtn(bOK,   pOK, rOK, lOK);
  readBtn(bNEXT, pNX, rNX, lNX);
  readBtn(bBACK, pBK, rBK, lBK);

  // Switching tabs with two buttons with a “latch”
  static uint32_t bothDownAt      = 0;
  static bool     bothWasDown     = false;
  static bool     tabSwitchLatched = false;

  bool both = bothHeld();
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

  // Tab logic
  if (currentTab == TAB_TRACKERS) {  // TRACKERS
    if (rNX && !lNX) { selTracker = (selTracker + 1) % MAX_TRACKERS;  beep(1500, 15); }
    if (rBK && !lBK) { selTracker = (selTracker - 1 + MAX_TRACKERS) % MAX_TRACKERS;  beep(1500, 15); }
    if (rOK && !lOK) { toggleTracker(); }
    if (lOK) { resetTracker(); }
  } else { // COUNTDOWNS
    if (rNX && !lNX) { selCountdown = (selCountdown + 1) % MAX_COUNTDOWNS;  beep(1500, 15); }
    if (rBK && !lBK) { selCountdown = (selCountdown - 1 + MAX_COUNTDOWNS) % MAX_COUNTDOWNS;  beep(1500, 15); }
    if (rOK && !lOK) { toggleCountdown(); }
    if (lOK) { resetCountdown(); }
    if (lNX) { adjustCountdown(5 * 60); }
    if (lBK) { adjustCountdown(-5 * 60); }
  }

  // 1 Hz tick: increase trackers and decrease countdowns
  if (now - lastTickMs >= 1000) {
    uint32_t dt = (now - lastTickMs) / 1000;
    lastTickMs += dt * 1000;

    if (activeIdx >= 0) {
      trackers[activeIdx].seconds += dt;
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

  // Drawing
  drawTab();
}