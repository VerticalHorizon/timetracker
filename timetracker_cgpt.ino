// ESP32-C3 Task Timer: multi-counter time tracker + 2 countdowns on SSD1306 OLED
// Один активный трекер за раз; можно быстро переключаться между задачами.
// Два таймера обратного отсчёта: один по умолчанию 5 минут, второй 1.5 часа.
//
// ──────────────────────────────────────────────────────────────────────────────
// Железо (под себя можно поправить пины):
//  OLED SSD1306 128x32 I2C: SDA=GPIO6, SCL=GPIO7, адрес 0x3C
//  Кнопки (на GND, используем внутреннюю подтяжку): OK=GPIO2, NEXT=GPIO3, BACK=GPIO4
//  Зуммер (желательно пассивный): GPIO5 (используем tone())
//
// Библиотеки (Arduino IDE → Library Manager):
//  - Adafruit GFX
//  - Adafruit SSD1306
//  - Preferences (входит в ESP32 core)
// Плата: ESP32C3 Dev Module (Arduino core for ESP32 v2.0+)
//
// ── Управление ───────────────────────────────────────────────────────────────
// Вкладка TRACKERS (счётчики задач):
//   OK   (клик)        → старт/стоп выделенного трекера
//   NEXT (клик)        → выбрать следующий трекер
//   BACK (клик)        → выбрать предыдущий трекер
//   OK   (долгое 1.5с) → сбросить время текущего трекера
//   NEXT (долгое 1.5с) → "добавить"/обнулить трекер (быстрый способ завести новый)
//   BACK (долгое)      → сейчас НИЧЕГО не делает (переименование убрано)
//
// Вкладка COUNTDOWNS (обратные отсчёты):
//   OK   (клик)        → старт/стоп выделенного таймера
//   NEXT (клик)        → выбрать следующий таймер
//   BACK (клик)        → выбрать предыдущий таймер
//   OK   (долгое)      → сбросить к пресету (5 мин или 1.5 часа)
//   NEXT (долгое)      → +5 минут к пресету
//   BACK (долгое)      → −5 минут (минимум 1 минута)
//
// Переключение вкладок: удерживать NEXT + BACK одновременно ~1 секунду.
//
// Хранение в NVS: состояние трекеров и таймеров, текущая вкладка и выборы
// сохраняются во flash (NVS — non-volatile storage; ключ-значение),
// чтобы после выключения питания всё восстановилось.
// ──────────────────────────────────────────────────────────────────────────────

#include <Arduino.h>
#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <Preferences.h>

// ── Пины (можешь поменять под свою плату) ───────────────────────────────────
constexpr uint8_t PIN_OLED_SDA = 6;
constexpr uint8_t PIN_OLED_SCL = 7;
constexpr uint8_t PIN_BTN_OK   = 2;
constexpr uint8_t PIN_BTN_NEXT = 3;
constexpr uint8_t PIN_BTN_BACK = 4;
constexpr uint8_t PIN_BUZZER   = 5;  // пассивный зуммер

// ── Дисплей ─────────────────────────────────────────────────────────────────
constexpr int OLED_W = 128;
constexpr int OLED_H = 32;  // твой 0.91" OLED 128x32
Adafruit_SSD1306 display(OLED_W, OLED_H, &Wire, -1);

// ── Конфиг ──────────────────────────────────────────────────────────────────
constexpr uint8_t MAX_TRACKERS   = 5; // было 8
constexpr uint8_t MAX_COUNTDOWNS = 2; // было 4
constexpr uint32_t AUTOSAVE_MS   = 10000;
constexpr uint32_t LONG_MS       = 1000;  // долгая кнопка

Preferences prefs;

// ── Данные ──────────────────────────────────────────────────────────────────
struct Tracker {
  char     name[12];
  uint32_t seconds = 0;   // накопленное время в секундах
};

struct Countdown {
  uint32_t presetSec = 0; // пресет
  int32_t  remainSec = 0; // оставшееся
  bool     running   = false;
};

Tracker   trackers[MAX_TRACKERS];
Countdown countdowns[MAX_COUNTDOWNS];

// вкладки
enum Tab { TAB_TRACKERS, TAB_COUNTDOWNS };
Tab currentTab = TAB_TRACKERS;

int8_t selTracker   = 0;   // выделенный трекер
int8_t activeIdx    = -1;  // какой трекер сейчас тикает (-1 = никакой)
int8_t selCountdown = 0;   // выделенный обратный таймер

// время
uint32_t lastTickMs   = 0;   // для 1 Гц тика приложения
uint32_t lastAutosave = 0;

// кнопки
struct Btn { uint8_t pin; bool last; uint32_t tDown; };
Btn bOK{PIN_BTN_OK, true, 0}, bNEXT{PIN_BTN_NEXT, true, 0}, bBACK{PIN_BTN_BACK, true, 0};
// ── Утилиты ─────────────────────────────────────────────────────────────────
// Формат времени: при часах → H:MM:SS, иначе MM:SS
String fmtHMS(uint32_t s) {
  uint32_t h = s / 3600; s %= 3600;
  uint32_t m = s / 60;   s %= 60;
  char buf[16];
  if (h > 0) snprintf(buf, sizeof(buf), "%lu:%02lu:%02lu", (unsigned long)h, (unsigned long)m, (unsigned long)s);
  else       snprintf(buf, sizeof(buf), "%02lu:%02lu", (unsigned long)m, (unsigned long)s);
  return String(buf);
}

void beep(uint16_t freq, uint16_t ms) {
  tone(PIN_BUZZER, freq, ms);
}

// ── Хранение в NVS ─────────────────────────────────────────────────────────
void saveAll() {
  prefs.begin("ttracker", false);

  prefs.putUChar("tab", (uint8_t)currentTab);
  prefs.putChar("selT", selTracker);
  prefs.putChar("actT", activeIdx);
  prefs.putChar("selC", selCountdown);

  for (uint8_t i=0;i<MAX_TRACKERS;i++) {
    char k1[8], k2[8]; snprintf(k1,8,"tn%u",i); snprintf(k2,8,"ts%u",i);
    prefs.putString(k1, trackers[i].name);
    prefs.putUInt(k2, trackers[i].seconds);
  }
  for (uint8_t i=0;i<MAX_COUNTDOWNS;i++) {
    char k3[8], k4[8], k5[8]; snprintf(k3,8,"cp%u",i); snprintf(k4,8,"cr%u",i); snprintf(k5,8,"cl%u",i);
    prefs.putUInt(k3, countdowns[i].presetSec);
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

  // трекеры
  for (uint8_t i=0;i<MAX_TRACKERS;i++) {
    char k1[8], k2[8]; snprintf(k1,8,"tn%u",i); snprintf(k2,8,"ts%u",i);
    String n = prefs.getString(k1, "");
    if (n.length()==0) {
      snprintf(trackers[i].name, sizeof(trackers[i].name), "T%u", i+1);
      trackers[i].seconds = 0;
    } else {
      strncpy(trackers[i].name, n.c_str(), sizeof(trackers[i].name));
      trackers[i].name[sizeof(trackers[i].name)-1]=' ';
      trackers[i].seconds = prefs.getUInt(k2, 0);
    }
  }

  // countdowns: 0 → 5 мин, 1 → 1.5 часа по умолчанию
  for (uint8_t i=0;i<MAX_COUNTDOWNS;i++) {
    char k3[8], k4[8], k5[8]; snprintf(k3,8,"cp%u",i); snprintf(k4,8,"cr%u",i); snprintf(k5,8,"cl%u",i);
    uint32_t defPreset;
    if (i == 0)      defPreset = 5 * 60;        // 5 минут
    else if (i == 1) defPreset = 90 * 60;       // 1.5 часа
    else             defPreset = 5 * 60;        // на всякий

    countdowns[i].presetSec = prefs.getUInt(k3, defPreset);
    countdowns[i].remainSec = prefs.getInt(k4, (int32_t)countdowns[i].presetSec);
    countdowns[i].running   = prefs.getBool(k5, false);
  }

  prefs.end();
}

// ── Отрисовка ───────────────────────────────────────────────────────────────
void drawHeader(const char* title) {
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0,0);
  display.print(title);
  display.drawFastHLine(0,8,OLED_W,SSD1306_WHITE);
}

void drawTrackers() {
  display.clearDisplay();
  drawHeader("TRACKERS");

  const int rowH = 10;
  // На 32px по высоте у нас влезет по факту 2 строки, остальные просто не увидим —
  // но можно пролистывать селектором.
  int visibleRows = (OLED_H - 10) / rowH;
  if (visibleRows < 1) visibleRows = 1;
  if (visibleRows > MAX_TRACKERS) visibleRows = MAX_TRACKERS;

  int start = selTracker - visibleRows/2;
  if (start < 0) start = 0;
  if (start > MAX_TRACKERS - visibleRows) start = MAX_TRACKERS - visibleRows;

  int y = 12;
  for (int row=0, i=start; row<visibleRows && y<OLED_H; ++row, ++i, y+=rowH) {
    bool sel = (i==selTracker);
    if (sel) display.fillRect(0,y-1,OLED_W,rowH, SSD1306_WHITE);

    display.setCursor(2,y);
    display.setTextSize(1);
    display.setTextColor(sel?SSD1306_BLACK:SSD1306_WHITE);
    display.print(trackers[i].name);

    String t = fmtHMS(trackers[i].seconds + ((activeIdx==i)? (millis()-lastTickMs)/1000 : 0));
    int16_t tw = t.length()*6;
    display.setCursor(OLED_W - tw - 4, y);
    display.print(t);
    if (activeIdx==i) {
      display.fillCircle(OLED_W-4, y-2, 2, sel?SSD1306_BLACK:SSD1306_WHITE);
    }
  }

  display.display();
}

void drawCountdowns() {
  display.clearDisplay();
  drawHeader("COUNTDOWNS");

  const int rowH = 10;
  int visibleRows = (OLED_H - 10) / rowH;
  if (visibleRows < 1) visibleRows = 1;
  if (visibleRows > MAX_COUNTDOWNS) visibleRows = MAX_COUNTDOWNS;

  int start = selCountdown - visibleRows/2;
  if (start < 0) start = 0;
  if (start > MAX_COUNTDOWNS - visibleRows) start = MAX_COUNTDOWNS - visibleRows;

  int y = 12;
  for (int row=0, i=start; row<visibleRows && y<OLED_H; ++row, ++i, y+=rowH) {
    bool sel = (i==selCountdown);
    if (sel) display.fillRect(0,y-1,OLED_W,rowH, SSD1306_WHITE);

    display.setCursor(2,y);
    display.setTextSize(1);
    display.setTextColor(sel?SSD1306_BLACK:SSD1306_WHITE);

    char label[6]; snprintf(label,sizeof(label),"C%u", i+1);
    display.print(label);

    int32_t r = countdowns[i].remainSec;
    if (r < 0) r = 0;
    String t = fmtHMS((uint32_t)r);
    int16_t tw = t.length()*6;
    display.setCursor(OLED_W - tw - 4, y);
    display.print(t);

    if (countdowns[i].running) {
      display.fillCircle(OLED_W-4, y-2, 2, sel?SSD1306_BLACK:SSD1306_WHITE);
    }
  }

  display.display();
}

void drawTab() {
  if (currentTab==TAB_TRACKERS) drawTrackers(); else drawCountdowns();
}

// ── Кнопки ──────────────────────────────────────────────────────────────────
bool readBtn(Btn &b, bool &pressed, bool &released, bool &longPress) {
  bool level = digitalRead(b.pin); // INPUT_PULLUP: 1=отпущена, 0=нажата
  uint32_t now = millis();
  pressed = released = longPress = false;

  if (b.last && !level) {           // фронт вниз
    b.tDown = now;
    pressed = true;
  } else if (!b.last && level) {    // отпускание
    released = true;
    if (now - b.tDown >= LONG_MS) longPress = true;
  }

  b.last = level;
  return !level; // сейчас нажата?
}

bool bothHeld() {
  return !digitalRead(bNEXT.pin) && !digitalRead(bBACK.pin);
}

// ── Действия ────────────────────────────────────────────────────────────────
void switchToTab(Tab t) {
  currentTab = t;
  beep(1600, 40);
}

void startTracker(int8_t i) {
  if (i<0 || i>=MAX_TRACKERS) return;
  activeIdx = i;
  beep(1200, 30);
}

void stopTracker() {
  activeIdx = -1;
  beep(800, 30);
}

void toggleTracker() {
  if (activeIdx==selTracker) stopTracker(); else startTracker(selTracker);
}

void addOrResetTracker() {
  // Просто обнуляем выбранный трекер (быстрый "новый")
  trackers[selTracker].seconds = 0;
  beep(1400, 40);
}

void resetTracker() {
  trackers[selTracker].seconds = 0;
  if (activeIdx==selTracker) lastTickMs = millis();
  beep(600, 30);
}

void toggleCountdown() {
  auto &cd = countdowns[selCountdown];
  if (cd.running) {
    cd.running = false;
    beep(800, 30);
  } else {
    if (cd.remainSec <= 0) cd.remainSec = cd.presetSec;
    cd.running = true;
    beep(1200, 30);
  }
}

void resetCountdown() {
  auto &cd = countdowns[selCountdown];
  cd.remainSec = cd.presetSec;
  cd.running   = false;
  beep(700, 30);
}

void adjustCountdown(int deltaSec) {
  auto &cd = countdowns[selCountdown];
  int32_t np = (int32_t)cd.presetSec + deltaSec;
  if (np < 60) np = 60;
  if (np > 24*3600) np = 24*3600;
  cd.presetSec = (uint32_t)np;
  if (!cd.running) cd.remainSec = cd.presetSec;
  beep(1000, 20);
}

// ── Setup / Loop ────────────────────────────────────────────────────────────
void setup() {
  pinMode(PIN_BTN_OK,   INPUT_PULLUP);
  pinMode(PIN_BTN_NEXT, INPUT_PULLUP);
  pinMode(PIN_BTN_BACK, INPUT_PULLUP);
  pinMode(PIN_BUZZER,   OUTPUT);

  Wire.begin(PIN_OLED_SDA, PIN_OLED_SCL);
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    // Если адрес другой — поменяй 0x3C на свой или прогони I2C-сканер
    for(;;) { tone(PIN_BUZZER, 400, 150); delay(400); }
  }
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);

  loadAll();

  lastTickMs   = millis();
  lastAutosave = millis();
  // Splash
  display.setCursor(8, 10);
  display.println("ESP32-C3 Timer");
  display.setCursor(40, 22);
  display.println("v1.1");
  display.display();
  beep(1200,40); delay(60); beep(1600,50);
  delay(500);
}

void loop() {
  uint32_t now = millis();

  // кнопки
  bool pOK=false,rOK=false,lOK=false;   readBtn(bOK,   pOK, rOK, lOK);
  bool pNX=false,rNX=false,lNX=false;   readBtn(bNEXT, pNX, rNX, lNX);
  bool pBK=false,rBK=false,lBK=false;   readBtn(bBACK, pBK, rBK, lBK);

  // переключение вкладок (NEXT+BACK держим ~1с)
  static uint32_t bothDownAt = 0; static bool bothWasDown=false;
  bool both = bothHeld();
  if (both && !bothWasDown) {
    bothDownAt = now;
  }
  if (both && (now - bothDownAt) > 900) {
    switchToTab(currentTab==TAB_TRACKERS ? TAB_COUNTDOWNS : TAB_TRACKERS);
    bothDownAt = now + 100000; // блокируем повтор до отпускания
  }
  bothWasDown = both;

  // Логика по вкладкам
  if (currentTab==TAB_TRACKERS) {
    if (rNX && !lNX) { selTracker = (selTracker+1)%MAX_TRACKERS; beep(1500,15);}  
    if (rBK && !lBK) { selTracker = (selTracker-1+MAX_TRACKERS)%MAX_TRACKERS; beep(1500,15);}  
    if (rOK && !lOK) { toggleTracker(); }
    if (lOK)         { resetTracker(); }
    if (lNX)         { addOrResetTracker(); }
    // lBK (долгое BACK) теперь ничего не делает
  } else { // COUNTDOWNS
    if (rNX && !lNX) { selCountdown = (selCountdown+1)%MAX_COUNTDOWNS; beep(1500,15);}  
    if (rBK && !lBK) { selCountdown = (selCountdown-1+MAX_COUNTDOWNS)%MAX_COUNTDOWNS; beep(1500,15);}  
    if (rOK && !lOK) { toggleCountdown(); }
    if (lOK)         { resetCountdown(); }
    if (lNX)         { adjustCountdown(5*60); }
    if (lBK)         { adjustCountdown(-5*60); }
  }

  // 1 Гц тик: считаем время трекеров / обновляем countdown
  if (now - lastTickMs >= 1000) {
    uint32_t dt = (now - lastTickMs)/1000;
    lastTickMs += dt*1000;

    if (activeIdx>=0) trackers[activeIdx].seconds += dt;

    for (uint8_t i=0;i<MAX_COUNTDOWNS;i++) {
      if (countdowns[i].running) {
        countdowns[i].remainSec -= (int32_t)dt;
        if (countdowns[i].remainSec<=0) {
          countdowns[i].remainSec = 0;
          countdowns[i].running   = false;
          // короткая трель
          for (uint8_t k=0;k<3;k++) { beep(2000,120); delay(150); }
        }
      }
    }
  }

  // автосейв
  if (now - lastAutosave >= AUTOSAVE_MS) {
    lastAutosave = now;
    saveAll();
  }

  drawTab();
}
