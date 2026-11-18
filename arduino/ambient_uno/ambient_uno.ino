/*
 * LumaSync Ambient - Arduino Uno Adalight Receiver
 *
 * Receives Adalight frames over USB Serial from the LumaSync Android TV app
 * and drives a WS2812B LED strip (GRB order) on data pin 6 using FastLED.
 *
 * IMPORTANT: SERIAL_BAUD and NUM_LEDS must match the Android app's Settings.
 */

#include <FastLED.h>

// ---- User Configuration ----
#define DATA_PIN        6
#define NUM_LEDS        108       // Must match total LEDs in LumaSync app (27*4 = 108)
#define SERIAL_BAUD     115200    // Must match baud rate in LumaSync app
#define BRIGHTNESS      120       // Default global brightness (~120 / 255)
#define MAX_MILLIAMPS   2500      // Configurable 5V power budget in mA (e.g. 2500 mA = 2.5 A)

#define READ_TIMEOUT_MS 100UL     // Serial byte timeout to prevent loop hangs on broken stream
#define IDLE_TIMEOUT_MS 2000UL    // Fade LEDs to black if no valid frame arrives for ~2 s
#define FADE_STEP_MS    25UL      // Fade interval while idle

CRGB leds[NUM_LEDS];

const uint8_t MAGIC[] = { 'A', 'd', 'a' };
unsigned long lastValidFrameMs = 0;
unsigned long lastFadeStepMs = 0;
bool fadedToBlack = true;

int16_t readByteWithTimeout(unsigned long timeoutMs) {
  unsigned long start = millis();
  while (Serial.available() == 0) {
    if (millis() - start >= timeoutMs) {
      return -1;
    }
  }
  return Serial.read();
}

bool areAllLedsBlack() {
  for (uint16_t i = 0; i < NUM_LEDS; i++) {
    if (leds[i].r != 0 || leds[i].g != 0 || leds[i].b != 0) {
      return false;
    }
  }
  return true;
}

void handleIdleFade() {
  unsigned long now = millis();
  if (!fadedToBlack && (now - lastValidFrameMs >= IDLE_TIMEOUT_MS)) {
    if (now - lastFadeStepMs >= FADE_STEP_MS) {
      lastFadeStepMs = now;
      fadeToBlackBy(leds, NUM_LEDS, 16);
      FastLED.show();
      if (areAllLedsBlack()) {
        fadedToBlack = true;
      }
    }
  }
}

void setup() {
  FastLED.addLeds<WS2812B, DATA_PIN, GRB>(leds, NUM_LEDS);
  FastLED.setBrightness(BRIGHTNESS);
  FastLED.setMaxPowerInVoltsAndMilliamps(5, MAX_MILLIAMPS);

  // Startup sanity flash: first LED red for 300 ms, then clear
  FastLED.clear(true);
  if (NUM_LEDS > 0) {
    leds[0] = CRGB::Red;
    FastLED.show();
    delay(300);
    FastLED.clear(true);
  }

  Serial.begin(SERIAL_BAUD);
  lastValidFrameMs = millis();
  lastFadeStepMs = lastValidFrameMs;
  fadedToBlack = true;
}

void loop() {
  // Wait for 'A' magic prefix while checking idle fade
  if (Serial.available() == 0) {
    handleIdleFade();
    return;
  }

  if (Serial.read() != MAGIC[0]) {
    handleIdleFade();
    return;
  }

  int16_t b1 = readByteWithTimeout(READ_TIMEOUT_MS);
  if (b1 != MAGIC[1]) return;

  int16_t b2 = readByteWithTimeout(READ_TIMEOUT_MS);
  if (b2 != MAGIC[2]) return;

  int16_t hiByte = readByteWithTimeout(READ_TIMEOUT_MS);
  int16_t loByte = readByteWithTimeout(READ_TIMEOUT_MS);
  int16_t chkByte = readByteWithTimeout(READ_TIMEOUT_MS);
  if (hiByte < 0 || loByte < 0 || chkByte < 0) return;

  uint8_t hi = (uint8_t)hiByte;
  uint8_t lo = (uint8_t)loByte;
  uint8_t chk = (uint8_t)chkByte;

  if (chk != (uint8_t)(hi ^ lo ^ 0x55)) {
    return;
  }

  uint16_t count = (((uint16_t)hi << 8) | lo) + 1;

  for (uint16_t i = 0; i < count; i++) {
    int16_t r = readByteWithTimeout(READ_TIMEOUT_MS);
    int16_t g = readByteWithTimeout(READ_TIMEOUT_MS);
    int16_t b = readByteWithTimeout(READ_TIMEOUT_MS);
    if (r < 0 || g < 0 || b < 0) {
      // Broken/truncated stream: abort frame cleanly without hanging
      return;
    }
    if (i < NUM_LEDS) {
      leds[i].r = (uint8_t)r;
      leds[i].g = (uint8_t)g;
      leds[i].b = (uint8_t)b;
    }
  }

  FastLED.show();
  lastValidFrameMs = millis();
  fadedToBlack = areAllLedsBlack();
}
