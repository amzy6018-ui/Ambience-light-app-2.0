# LumaSync Ambient

An Ambilight-style dynamic backlight system for **Android TV / Google TV** and **Arduino Uno** over USB Serial using the **Adalight** protocol.

- **Android TV App (`com.example.lumasync`)**: Pure Kotlin (zero AndroidX/Compose overhead) foreground `MediaProjection` screen capture at `128×72` (~30 fps), edge-region RGB averaging with exponential smoothing, optional 2.2 gamma correction, saturation boost, and letterbox black-bar skipping.
- **Arduino Uno Sketch (`arduino/ambient_uno/ambient_uno.ino`)**: FastLED WS2812B (`GRB`) driver on pin `6` with read timeouts, automatic 2 s idle fade-to-black, and configurable 5 V current limiting.

---

## LED Strip Layout & Protocol

Default configuration: **108 LEDs** (`LEFT = 27`, `TOP = 27`, `RIGHT = 27`, `BOTTOM = 27`), sampling `DX = 12 px` from left/right edges and `DY = 8 px` from top/bottom edges on a `128×72` frame.

Physical strip order (looking at the front of the TV):
1. **Bottom-left → Top-left** (up the left side)
2. **Top-left → Top-right** (across the top)
3. **Top-right → Bottom-right** (down the right side)
4. **Bottom-right → Bottom-left** (along the bottom, right-to-left)

Any side count can be set to `0` in the app's **Settings** screen (e.g., if you leave the bottom open for a TV stand).

### Adalight Packet Format
```text
['A', 'd', 'a', hi, lo, chk, R0, G0, B0, R1, G1, B1, ...]
```
- `hi`, `lo`: high and low bytes of `(LED_COUNT - 1)`
- `chk`: `hi ^ lo ^ 0x55`
- Payload: `LED_COUNT × 3` RGB bytes

> **Important:** `SERIAL_BAUD` and `NUM_LEDS` in `arduino/ambient_uno/ambient_uno.ino` **must match** the Baud Rate and Total LED count configured in the LumaSync Settings screen.

---

## Hardware & Wiring Guide

### Parts Required
- **Arduino Uno** (or compatible ATmega328P board with USB-B / USB-UART)
- **WS2812B addressable LED strip** (5 V, GRB order; 108 LEDs by default)
- **External 5 V DC power supply** sized for your strip (e.g., 5 V 3 A–5 A; do **not** power the LED strip from the Uno's 5 V pin)
- **330 Ω – 470 Ω resistor** (data line series protection)
- **1000 µF electrolytic capacitor** (rated ≥ 6.3 V or 10 V, across the strip's 5 V power supply)
- **USB cable** connecting the Arduino Uno to the Android TV / Google TV USB port

### Wiring Connections

```text
+--------------------+               +------------------------------+
|  External 5V PSU   |               |       WS2812B LED Strip      |
|                    |               |                              |
|  +5V  -------------+---+-----------| +5V                          |
|                    |   |           |                              |
|                    |  [+ 1000 uF]  |                              |
|                    |  [- Cap    ]  |                              |
|                    |   |           |                              |
|  GND  -------------+---+-------+---| GND                          |
+--------------------+           |   |                              |
                                 |   | DIN                          |
+--------------------+           |   +--+---------------------------+
|    Arduino Uno     |           |      |
|                    |           |   [330–470 Ω]
|  GND  -------------+-----------+      |
|  Pin 6 (DATA) -----+------------------+
|  USB Port <========> Android TV USB Port
+--------------------+
```

1. **Data Line**: Connect Arduino **Pin 6** through a **330 Ω – 470 Ω resistor** (placed as close to the strip's `DIN` pad as possible) to the WS2812B `DIN` input. Follow the directional arrows (`->`) printed on the strip.
2. **Separate 5 V Power Supply**: Connect the external **5 V PSU `+5V`** directly to the strip's `+5V` pad.
3. **Common Ground (Mandatory)**: Connect the external **5 V PSU `GND`**, the **LED strip `GND`**, and an **Arduino Uno `GND` pin** together. Without a shared ground reference, the data signal will glitch or flicker.
4. **1000 µF Capacitor**: Place a **1000 µF electrolytic capacitor** across `+5V` and `GND` at the strip power input (observe polarity: `-` stripe to `GND`, `+` leg to `+5V`) to absorb inrush current spikes.

---

## Arduino Setup

1. Open **Arduino IDE** and install the **FastLED** library via *Sketch → Include Library → Manage Libraries…*.
2. Open `arduino/ambient_uno/ambient_uno.ino`.
3. Verify/adjust the constants at the top of the sketch:
   - `NUM_LEDS` (default `108` — must equal the app's total LED count)
   - `SERIAL_BAUD` (default `115200` — must equal the app's baud rate)
   - `MAX_MILLIAMPS` (default `2500` — set to match your 5 V power supply rating)
4. Select **Arduino Uno** and upload the sketch.
5. On boot, LED #0 flashes **red for 300 ms**, then clears.

---

## Building the Debug APK

Build the debug APK from a fresh clone using the Gradle Wrapper (or system `gradle`):

```bash
./gradlew assembleDebug
```

*(If running in an environment without `gradlew`, use `gradle :app:assembleDebug`.)*

The output APK is written to:
```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## Sideloading to Android TV / Google TV with `adb`

1. On your TV, go to **Settings → System → About** and click **Android TV OS build** 7 times to enable **Developer options**.
2. Go to **Settings → System → Developer options** and enable **USB debugging** (and **Network debugging** / **ADB debugging** if connecting over Wi-Fi/Ethernet).
3. Find your TV's IP address in **Settings → Network & Internet**.
4. From your computer, connect and install the APK:

```bash
adb connect <TV_IP_ADDRESS>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

5. Launch **LumaSync Ambient** from the TV Apps row (or run via ADB):

```bash
adb shell monkey -p com.example.lumasync -c android.intent.category.LEANBACK_LAUNCHER 1
```

6. Plug the Arduino Uno into the TV's USB port, select **Start Ambient** with your TV remote D-pad, and accept both the **USB permission** and **Screen capture** prompts.

> **Note:** Streaming apps using hardware DRM (`FLAG_SECURE`, such as Netflix/Disney+) output black frames to `MediaProjection` due to Android OS content protection. Unprotected video, Plex/Kodi/Jellyfin, YouTube, home UI, and games work normally.
