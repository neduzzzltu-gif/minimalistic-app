# TESTING — what has been verified on the real phone

Phone: Redmi Note 13 Pro 23117RA68G, HyperOS 3, Android 16 (API 36), phone language English.
✅ = verified on the phone · 🟡 = built, needs your real use to verify · ❌ = known not working

## Screen structure mapped with `uiautomator dump` (2026-10-30, not guessed)

| App | Screen | Marker used by the detector |
|---|---|---|
| Instagram | tab bar | `feed_tab` `clips_tab` `direct_tab` `search_tab` `profile_tab`; selected tab has a selected `tab_icon` child |
| Instagram | feed | `feed_tab` selected, `reels_tray_container`, `row_feed_profile_header` |
| Instagram | Reels (tab or opened from a DM) | `clips_video_container`, `clips_media_component` |
| Instagram | DM inbox | `direct_tab` selected |
| Instagram | chat thread | `row_thread_composer_edittext`, `header_title` |
| Instagram | DM camera | `camera_shutter_button` + `header_title` ("Message <name>") |
| Instagram | Explore | `search_tab` selected, `action_bar_search_edit_text` |
| Instagram | profile | `profile_tab` selected, `row_profile_header_imageview` |
| Instagram | story viewer | `reel_viewer_title`, `reel_viewer_text_container` |
| Snapchat | page title (top TextView) | "Chat" / "Stories" / "Spotlight" (stays in tree under the media viewer) |
| Snapchat | media viewer | content-desc `opera_content_index:` |
| Snapchat | chat thread | `chat_input_text_field` |
| Snapchat | camera | `camera_capture_button` (also present under other pages → checked last) |
| Snapchat | Map | content-desc "Snap Map" |
| Snapchat | friend profile | `profile_identity_section_display_name` |
| YouTube | Shorts player | `reel_watch_fragment_root`, `reel_recycler`, `reel_player_page_container` |
| YouTube | normal player / miniplayer | `watch_player`, `modern_miniplayer_close` |
| Chrome | URL | `url_bar` (text = "reddit.com/r/Android/") |
| Settings | Accessibility | activity `.accessibility.MiuiAccessibilitySettingsActivity`, title "Accessibility" |
| Settings | Notification access | `Settings$NotificationAccessSettingsActivity`, title "Notification read, reply & control" |
| Settings | Usage access | `Settings$UsageAccessSettingsActivity`, title "App usage data" |
| Security app | App info (any app) | `com.miui.securitycenter/com.miui.appmanager.ApplicationsDetailsActivity`, `app_manager_details_applabel` |
| Security app | Battery details | `com.miui.powercenter.legacypowerrank.PowerDetailActivity` |
| Security app | Autostart | `com.miui.permcenter.autostart.AutoStartManagementActivity`, title "Background autostart" |

Lithuanian titles were extracted with `aapt2 dump resources` from the phone's own Settings.apk,
SecurityCenter.apk and package installer (e.g. "Pranešimų skaitymas, atsakymas į juos ir tvarkymas",
"Programų naudojimo duomenys", "Automatinis paleidimas fone", "Programos informacija").
Snapchat's Lithuanian titles ("Pokalbiai", "Istorijos") are **unverified guesses**.

Side effects while mapping: one Instagram story and one Snapchat story got marked as seen; a Snapchat
location prompt was answered "Don't Allow". Nothing was sent.

## Build / install
- ✅ Gradle 9.8.2 + AGP 9.5.0 on JDK 25 (Android Studio JBR), compileSdk/targetSdk 36, minSdk 33
- ✅ Merged manifest has no INTERNET permission (`aapt2 dump permissions`), plus `tools:node="remove"` guard
- ✅ First install with `adb install` (tap Install on the phone); updates with `tools/deploy.sh` (no prompt)
- ✅ Accessibility service + foreground service come back by themselves after an app update

## 1. Launcher UI
- ✅ Home: pure black, apps as 36sp text, left-aligned, vertically centred, dim status line
  ("12m today · target 2h · social 5/45 · yt 0/60")
- ✅ Drawer: "Apps ▾" + "Settings" top bar, alphabetical text list, thin scroll indicator, round search button
- ✅ Back from drawer → home
- ✅ Lock-screen status notification: VISIBILITY_PUBLIC, 2 quick-app actions, "Instagram · time left" + countdown chronometer during a session
- 🟡 search auto-open on single match, category filter dropdown, long-press → Settings (built, not yet tapped through)

## 2–3. Categories, Gate, sessions, limits
- ✅ Auto-labels applied (Instagram/Snapchat/… Social, YouTube Video, Temu/Shein Shops, …)
- ✅ Instagram opened from outside the launcher (like recents/notification) → Home → Gate
- ✅ Gate: reason categories, "Why are you opening X?", last reason for this app shown dimmed, duration options, budget left, Open disabled during the 10 s countdown
- ✅ Reason "too close to one of your last 5" rejected (≈80% similarity)
- ✅ Session start → app opens, grayscale on (`accessibility_display_daltonizer_enabled=1`, mode 0)
- ✅ Session end while app in front → app leaves, grayscale restored
- ✅ Cooldown Lock screen: "Cooldown after your last Social session · 27:52 left", no override
- ✅ Baseline phase: Gate shows + logs, no locks
- ✅ Bug found + fixed: screens opened from Home were stacking in the Home task
- ✅ Bug found + fixed: HyperOS runs GLOBAL_ACTION_HOME asynchronously → Gate now opened via our own Home

## 4. Enforcement + detectors
- ✅ Instagram DM inbox (chats-free mode) → allowed, no Gate
- ✅ Instagram Reels tab → Gate
- ✅ Chrome instagram.com → Gate; Chrome wikipedia.org → allowed; reddit.com during focus → Lock
- ✅ YouTube Shorts tab (with a valid YouTube session) → "Shorts are blocked", backed out
- ✅ Snapchat launch → camera, no Gate (after adding `camera_page` + 1.5 s grace for loading frames)
- ✅ Snapchat Stories tab → Gate
- ✅ Picture-in-picture: YouTube PiP after its session ended → dismissed
  (HyperOS ignores injected taps on its PiP menu and drag-to-dismiss; the app is brought back full screen
  and gated instead. Home is opened with FLAG_ACTIVITY_NO_USER_ACTION so apps can't auto-enter PiP.)
- ✅ Bug found + fixed: TYPE_WINDOWS_CHANGED events have no package name (PiP check never ran)
- ✅ Bug found + fixed: HyperOS PiP windows have no root node → package taken from the window title
- 🟡 Snapchat chat list / thread free (markers from dumps; not yet exercised live)
- 🟡 Instagram story viewer, Explore, profile, DM camera (markers from dumps; not exercised live)
- 🟡 "detector broken: Instagram" was flagged once during testing (some transient IG screen, probably the
  TransparentModalActivity at app start). Unknown screens are now logged with their view IDs
  (Settings shows the warning; `adb logcat -s StillDetector` shows the IDs)

## 5. Notification filter + digest (tested with notifications posted from adb as a "Social" app)
- ✅ "liked your photo" → Digest · "is live" → Drop · "sent you a reel" → Digest (counted as reel)
- ✅ One-time code ("verification code 482913") → Ping, original kept
- ✅ "Name: text" chat message → Ping
- ✅ Dropped/digested originals removed from the shade; Digest screen groups them by app
- 🟡 Re-post with sound for muted channels (needs your muted channels + real messages)
- 🟡 Inline Reply through a re-posted notification; digest-time notification at 20:00

## 6. Stats + review
- ✅ Own screen-on tracking ("28m today"), metered minutes per category, sessions
- ✅ Weekly review renders: phone time/day, gated minutes, sessions, reasons + intent answers,
  rule changes (pending marked ⏳), reels sent, self-check trend, goal ratings, Done at the very end
- 🟡 Automatic Sunday 20:00 opening; per-app breakdown (needs Usage access)

## 7. Quiz + baseline
- ✅ Onboarding opens on a fresh install/reset
- 🟡 Full quiz → rules → setup → baseline choice (left for you: your answers become your rules)

## 8. Focus mode
- ✅ Focus active → gated site in Chrome shows "Focus mode · 1:53 left"
- 🟡 Chat notifications → digest during focus (logic in Notifs.decide)

## 9. Cards + geofencing
- ✅ Store list bundled: 447 Vilnius stores, 56 brands (OSM Overpass, tools/fetch_stores.py)
- ✅ In-store state → "Maxima card" line on home → full-screen barcode at max brightness
- ✅ Invalid EAN check digit falls back to Code 128 instead of showing nothing
- 🟡 Camera scan, real geofence enter/dwell (needs location "Allow all the time" + walking into a store),
  lock-screen card button, app-only "checkout pass"

## 10. Loopholes
- ✅ PiP (above) · ✅ other browsers labelled Browser = gated whole-app (auto-label)
- 🟡 New app install → label prompt (PACKAGE_ADDED is registered at runtime; not exercised)

## 11. 24 h rule
- ✅ Video budget 60 → 45 (tightening) applied instantly
- ✅ Social budget 45 → 60 (loosening) → Pending changes: "applies in 23h 57m · cancel"
- ✅ Bug found + fixed: Settings pages didn't redraw after a change

## 12. Lockdown (armed temporarily for the test, then disarmed)
- ✅ Sent home from: Accessibility settings (title), Notification access, Usage access, Stillhome's App info,
  Autostart list, Battery details for Stillhome, uninstall dialog
- ✅ Not blocked: WhatsApp's App info, main Settings page
- ✅ Bug found + fixed: Android 14+ hides Accessibility settings + uninstall dialog from accessibility
  services unless `isAccessibilityTool="true"`
- 🟡 Lithuanian titles (strings taken from the phone's own APKs; phone language is English)

## Final state left on the phone
- Release build installed (not debuggable, no test hook), all test data wiped → onboarding shows
- Already granted over adb: default Home = Stillhome, accessibility service, notification access,
  notifications, display over other apps, Xiaomi background pop-ups, WRITE_SECURE_SETTINGS
- StayFree's accessibility service is still enabled (left untouched)
