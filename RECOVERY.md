# RECOVERY — how to switch Stillhome off if it locks you out

Stillhome's lockdown blocks (on the phone) Accessibility settings, Notification access, Usage access,
its own App info / uninstall / battery / autostart pages. These two paths always work because
they don't go through those screens. **Test both before lockdown arms** (see "Test" at the bottom).

Phone: Xiaomi Redmi Note 13 Pro (23117RA68G), HyperOS 3, Android 16.

---

## Path A — from the PC with adb (fastest)

Needs: USB cable, USB debugging still on (Settings → Additional settings → Developer options).

```sh
# 1. Turn off Stillhome's accessibility service (stops all blocking at once)
#    (if other services are listed, `get` first and put back the others)
adb shell settings get secure enabled_accessibility_services
adb shell settings delete secure enabled_accessibility_services

# 2. Stop the app and give the home screen back to the Xiaomi launcher
adb shell am force-stop lt.stillhome
adb shell cmd package set-home-activity com.miui.home/.launcher.Launcher

# 3. Undo grayscale if it is stuck on
adb shell settings put secure accessibility_display_daltonizer_enabled 0

# 4a. Disable without losing data ...
adb shell pm disable-user --user 0 lt.stillhome
#     ... re-enable later with:  adb shell pm enable lt.stillhome
# 4b. ... or uninstall completely (deletes all Stillhome data)
adb uninstall lt.stillhome

```

If `adb devices` shows nothing: Settings → Additional settings → Developer options → USB debugging on,
and accept the "Allow USB debugging?" prompt on the phone.

---

## Path B — Safe mode (no PC needed)

Safe mode starts Android with **all downloaded apps disabled**, so Stillhome's guard is not running.

1. Hold **Power** until the power menu appears.
2. **Long-press "Power off"** (or "Restart"). If HyperOS asks "Reboot to safe mode?", tap **OK**.
   - If that prompt doesn't appear: power the phone off completely. Press **Power** to turn it on, and as
     soon as the **Redmi / Xiaomi logo** appears, press and **hold Volume Down** until the lock screen shows.
3. Unlock. "Safe mode" is written in the bottom-left corner.
4. Settings → Apps → Manage apps → **Stillhome** → **Uninstall** (or Force stop + turn off its Accessibility).
   (Home screen in safe mode is the Xiaomi launcher.)
5. Restart normally (Power → Restart) to leave safe mode.

---

## If only grayscale is stuck

Settings → Accessibility → Vision → Colour correction → off. Or `adb shell settings put secure accessibility_display_daltonizer_enabled 0`.

## If the phone still shows the Xiaomi launcher after uninstall

Settings → Apps → Default apps → Home app → System launcher.

---

## Test (do this before lockdown arms)

- [ ] **adb path**: run step 1 above, check the red "guard off" text appears on the Stillhome home screen, then
      turn the service back on (Settings → Accessibility → Downloaded apps → Stillhome).
- [ ] **Safe mode path**: reboot into safe mode (Path B, steps 1–3), confirm "Safe mode" shows and the Xiaomi
      launcher is the home screen, then restart normally.
