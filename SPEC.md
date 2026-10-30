FIRST: save this entire message, unchanged, as C:\launcher\SPEC.md. Confirm the file ends with section "19. Process". Then build the whole app from it. Re-read SPEC.md whenever unsure. Ignore test_screen.png and test_ui.xml.

# SPEC — Personal minimalist launcher + phone guard

Build a minimalist Android launcher that is also a strict guard against addictive apps.
Personal use only, sideloaded via adb, never published. Build the WHOLE app in one go.

## 1. Device & constraints
- Xiaomi Redmi Note 13 Pro (model 23117RA68G), HyperOS 3 (OS3.0.303.0, EU build), Android 16 (API 36).
- AMOLED screen: keep pure #000000 (black pixels are off).
- HyperOS aggressively kills background apps. Design for that.
- App UI language: English.
- Detection of system Settings screens must work with phone language English AND Lithuanian.
- PC setup is done and tested: adb on Path, JAVA_HOME = JDK bundled with Android Studio (Java 25), phone shows as "device", screenshot + uiautomator dump work, "Stay awake while charging" is on.

## 2. Stack
- Kotlin, Jetpack Compose, single module. minSdk 33, compileSdk/targetSdk 36.
- Use a Gradle + Android Gradle Plugin version that runs on Java 25, or configure a Java toolchain (17/21) if needed.
- DataStore (settings), Room (sessions, reasons, stats, cards, logs).
- NO INTERNET permission. No analytics. All data stays on the phone.
- Allowed libraries: AndroidX, ZXing (barcode scan + render), play-services-location (geofencing). Ask before adding anything else.
- Home must open instantly: no splash, no icons loaded, animations ≤150 ms.

## 3. UI — pure black, white/grey text, nothing else
Home:
- #000000 background, transparent status bar.
- Up to 6 apps as plain text, left-aligned, vertically centered, ~36sp, #E0E0E0, no icons. Picked in onboarding.
- Apps from gated categories can NEVER be pinned to home or lock screen.
- Optional small "focus" text (shown if enabled) — tap starts focus mode.
- One dim bottom line: "2h 14m today · target 2h · social 12/45 · yt 20/60 · 7 new".
  - Dim grey while under target; bright white once over target.
  - "7 new" = digest count; tap → digest screen.
- When inside a store with a saved card: extra dim line "Maxima card" → full-screen barcode.
- Red "guard off" text only if the accessibility service isn't running.
- Tap = open. Swipe up = drawer. Long-press empty space = Settings.

Drawer:
- Top bar: "Apps ▾" left (dropdown = category filter: All / Social / Chat / …), "Settings" right, ~18sp.
- Alphabetical list, text only, ~17sp, ~56dp rows, ~40dp indent, thin scroll indicator right.
- Round outlined search button bottom-right (56dp, 1.5dp white stroke). Tap → keyboard instantly, live filter, single match = auto-open.
- Back / swipe down = home.
- Font: system sans-serif (bundle Inter if closer to the reference).

Lock screen (apps can't modify the real lock screen, so use the guard's foreground notification, VISIBILITY_PUBLIC, updated every minute):
- Shows "2h 14m today · target 2h", or "[App] · mm:ss left" during a session.
- Up to 3 quick-app buttons (user-chosen, no gated categories).
- In a store with a card: one button becomes the card → barcode activity with setShowWhenLocked (no unlock needed), max brightness.
- Onboarding enables HyperOS "show on lock screen" for this notification.

## 4. Categories (labels)
- Labels: Social, Chat, School/Work, Shops, Video, Music, Games, Tools, Finance, Browser, Other + user-created.
- Gated categories: Social, Video, Games, Shops (+ any browser except Chrome).
- Never gated by default: Chat (WhatsApp, Messenger, Discord, Phone, SMS).
- Auto-labeling: built-in package list of common apps + ApplicationInfo.category. Everything else → one onboarding screen of quick-tap labels.
- Known social apps (Instagram, TikTok, Snapchat, Facebook, Reddit, X, Threads, BeReal…) are locked to Social.
- Newly installed app (PACKAGE_ADDED): asks for a label immediately; stays gated until labeled. Labeling into a non-gated category = loosening (24 h rule).
- Notification defaults and stats are per category; each app inherits its category's rules.

## 5. Gate
Opening a gated app from ANYWHERE (launcher, notification, recents, link, share sheet) → full-screen black Gate screen:
1. Category of reason: friends / school / posting / boredom / Other: ___ (logging only, same rules for all).
2. "Why are you opening [App]?" — min 20 chars, paste disabled, rejected if exact or ~80% similar to any of my last 5 reasons. My last 3 reasons for this app shown dimmed above the field.
3. Duration buttons from that category's session options; hide options exceeding remaining budget.
4. "Open" disabled until all valid AND countdown finished (10 s default, 20 s if set by quiz).
5. Cancel = home.

If locked (cooldown / blocked hours / budget used / focus mode): Lock screen showing why + time remaining.
NO override of any kind.

## 6. Sessions
- Wall-clock timer; keeps running if I leave the app. Run timers inside the foreground service (don't rely on exact alarms).
- Warning before end: 1 min (Games: 5 min).
- At 0: go home immediately → "Time's up" → intent check: "Did you do what you wrote?" Yes / Partly / No (logged).
- Close any picture-in-picture window of a gated app when its session ends or it's locked.
- Grayscale while any gated app is in foreground (Settings.Secure daltonizer, monochromacy), restored on exit. Needs WRITE_SECURE_SETTINGS — run the adb grant yourself. Re-apply on every gated app open.

## 7. Per-app modes
Screen detection = AccessibilityService reading the window/view tree.
FAIL CLOSED: any screen inside a gated app that can't be identified counts as metered, and Settings shows "detector broken: [App]".

Instagram (default "chats free"):
- Free: DM inbox, chat threads, camera for sending in DMs.
- Metered (Social budget): home feed, Reels, Explore, stories, profiles, reels/posts opened from DMs. Swiping to the next reel = Reels.

Snapchat (user choice; default "chats free"):
- Free: chats + camera. Metered: Stories, Spotlight.
- Or "whole app" mode. Switching to "chats free" = loosening.

YouTube (Video budget):
- Shorts BLOCKED everywhere (home shelf, links, player) → immediately back out.
- Unknown screens count toward the Video budget.

General:
- "Chats free" option only exists for apps with a detector. All other gated apps: whole app.
- Inline Reply from a notification must NOT trigger the gate. Tapping the notification DOES.

Chrome: read the URL bar; editable domain list mapped to categories:
- instagram.com, tiktok.com, facebook.com, reddit.com, x.com, twitter.com → Social
- youtube.com → Video; /shorts/ URLs blocked
- temu.com, shein.com, aliexpress.com, etc. → Shops
- Same gate + budgets.
- Any browser other than Chrome = gated whole app.

## 8. Limits
User picks from options. Loosening waits 24 h. Ceilings can never be exceeded.

| Category | Daily budget options (min) | Default | Ceiling | Session options (min) |
|----------|----------------------------|---------|---------|-----------------------|
| Social   | 15 / 30 / 45 / 60 / 90     | 45      | 90      | 5 / 10 / 15           |
| Video    | 30 / 45 / 60 / 90 / 120    | 60      | 120     | 5 / 10 / 15           |
| Games    | 30 / 45 / 60 / 90          | 45      | 90      | 15 / 30 / 45          |
| Shops    | 10 / 15 / 30               | 15      | 30      | 5 / 10                |

- After the baseline week, each budget = 70% of that category's baseline daily average, rounded to the nearest option, within the ceiling.
- Budgets reset at midnight.
- Cooldown after a session: 1× / 2× / 3× session length, min 30 min. Default 2×. Shared across apps within a category; separate per category.
- Escalation per session that day: off / ×1.25 / ×1.5 / ×2. Default off.
- Weekly auto-tighten (Social): off / −5 / −10 min per week. Default −5. Stops at a floor (default 20 min) OR when my 7-day average total phone time is under my target — whichever comes first.
- Blocked hours (no gated app opens): user-defined windows (days + times). Defaults: 23:00–08:00 daily, Mon–Fri 08:00–15:00. Apply to all gated categories.
- Grayscale: on/off per app, default on.

## 9. Focus mode
- 25 or 50 min. All gated apps locked; Chat notifications → digest. Calls, SMS, alarms, one-time codes still come through.
- Cannot be ended early.
- Start from home "focus" text or Settings.

## 10. Notification filter (NotificationListenerService)
- Whitelist mode: only chosen apps/categories make sound; everything else → digest.
- Settings lists every app that has ever notified me. Tap → its categories, auto-discovered from Android notification channels (nothing hard-coded). Each = Ping / Digest / Drop.
- Extra split for Instagram & Snapchat: typed message vs snap vs shared reel/post. Built from notification texts logged on-device during the baseline week.
- Always Ping, unchangeable: calls, SMS, alarms, one-time codes (4–8 digit code + "code"/"kodas" etc.).
- Contacts/apps marked "never filter" in the quiz always ping (match sender name in chat notifications).
- Defaults:
  - Chat → Ping.
  - Social: messages/snaps Ping; likes/comments/follows Digest; suggestions/live/"X posted" Drop.
  - Shops: orders/delivery Ping; promotions/sales/coins Drop.
  - Games → Drop. Video → Digest. Not whitelisted → Digest.
- The ping happens before a listener can dismiss, so: onboarding mutes filtered apps' channels in system settings; my app re-posts allowed notifications with sound, keeping the original tap intent and Reply (RemoteInput) actions.
- Digest times: user adds/removes (default 20:00). Digest screen also reachable from home. Includes "N reels sent to you" counts.

## 11. Loyalty cards
- Settings → Cards: scan the physical card with the camera (ZXing) or type the number. Barcode format auto-detected (EAN-13, Code 128, QR…). Link card to a brand.
- Store locations: at BUILD time, download an OpenStreetMap (Overpass) export of Vilnius shops with brand tags (supermarkets, convenience, pharmacies, etc.) and bundle it as an asset. Include a script to refresh it.
- "Save this place" button for missing stores (saves current location to a brand).
- Geofencing via Play Services:
  - ~100 m radius, enter-and-dwell (~1 min).
  - Register the ≤90 nearest stores that have linked cards; refresh on significant location change.
  - Needs ACCESS_BACKGROUND_LOCATION ("Allow all the time").
- In store: card line on home + card button in lock-screen notification. Barcode shown full-screen at max brightness.
- App-only cards (rotating codes): button opens the store's app with a "checkout pass": no gate, only while inside that store's geofence, auto-closed after 3 min, logged.
- Location never leaves the phone.

## 12. Onboarding quiz (8 screens, ~3 min)
- Every question has "Other: ___". A custom answer gets a follow-up: which rule should it create (block a feed / earlier night block / send to digest / suggest focus mode / just note it).
- Every answer immediately shows the rule it created. Summary screen of all rules at the end (incl. editable school hours).

Questions:
1. Hours per day you think you spend on your phone → shown next to the real number after baseline.
2. Hours per day you want → target (home + lock screen line, tightening stop condition).
3. What do you want to improve: sleep / focus / sport / time with friends / mood / a project / Other → each picked goal gets a 1–5 rating in the Sunday review.
4. What pulls you most (multi-select):
   - Endless feeds → that category's budget set to the lowest option.
   - Likes & comments → those notification categories Drop + reminder screen to hide like counts in Instagram.
   - Streaks → keep or let them die (die = streak notifications Drop).
   - Stories → story notifications → Digest.
   - Waiting for replies → checklist to turn off read receipts where apps allow.
5. When do you scroll most (multi-select):
   - In bed → night block starts 30 min earlier.
   - Right after waking → night block ends 1 h later.
   - School breaks → confirm school block hours.
   - While studying → "focus" shortcut on home.
   - When bored → 20 s countdown.
6. What do you open the phone for, then end up scrolling:
   - Messages → "chats free" modes on.
   - A video → Video budget to the lowest option.
   - Checking something → reason min 30 chars.
   - Nothing specific → 20 s countdown.
7. Who/what should never be filtered → whitelist (apps + contacts).
8. Self-check: the 10-item Smartphone Addiction Scale – Short Version (Kwon et al., 2013, PLOS ONE, open access), 6-point scale.
   - Optional on day 1, required every 4 weeks.
   - Trend line in the review. Labeled "personal trend, not a diagnosis".

- Quiz can be retaken; any looser result waits 24 h.
- Setup screens after the quiz: label unlabeled apps, pick home apps, pick lock-screen quick apps, add cards (optional), permissions walkthrough.

## 13. Baseline week
- 7 days: track everything. Gate shows and logs reasons, but no budgets/cooldowns/blocked hours.
- Log Instagram/Snapchat notification texts on-device. Verify all detectors on my real apps.
- At the end: show my guess vs reality, set budgets (section 8), activate rules, arm lockdown.
- Option to skip the baseline → defaults apply immediately, lockdown arms 48 h after setup.

## 14. Stats & review
- Total today = own service tracking screen-on time (SCREEN_ON/OFF, USER_PRESENT) — works without Usage access.
- Per-app breakdown via UsageStatsManager (Usage access).
- Sunday 20:00 full-screen review (must scroll to the end to dismiss):
  - Minutes per category and app per day, session count.
  - All reasons grouped by category, intent-check answers.
  - Goal ratings (ask this week's 1–5 for each goal).
  - Every rule change made this week.
  - Guess vs real, reels-sent counts, self-check trend.

## 15. Enforcement
- AccessibilityService on TYPE_WINDOW_STATE_CHANGED (+ content changes where needed for in-app screens).
  - Scope: gated apps, Chrome, Settings, HyperOS Security app, package installer.
  - Throttle event handling (battery).
- Gated app without a valid session → GLOBAL_ACTION_HOME → Gate or Lock screen.
- Session expiry enforced by the service, not the launcher UI.
- Foreground service = the lock-screen notification. Declare a foregroundServiceType (required on Android 14+).
- BOOT_COMPLETED restores all state.
- Clock protection: store last-seen time; if system time jumps backwards, keep counting from the latest known time.

## 16. Lockdown (protect the off switch)
- Arms when the baseline week ends (or 48 h after setup if skipped).
- Service immediately goes home if any of these open:
  - Accessibility settings, Notification access, Usage access.
  - This app's App info page (force stop / uninstall / clear data). Force stop permanently kills the accessibility service, so this block is mandatory.
  - The uninstall dialog for this app.
  - Battery / autostart settings for this app in the HyperOS Security app.
- Detect by package + window title/text in English AND Lithuanian.
- Before arming: write RECOVERY.md (safe mode steps for Redmi Note 13 Pro on HyperOS 3 + adb commands to disable the service / uninstall) and make me test it.

## 17. 24 h rule
- Every setting is classified as tightening or loosening.
- Loosening → pending 24 h with visible countdown; I can cancel. Tightening → instant. Ceilings can never be exceeded.
- Loosening includes: bigger budget, longer session option, shorter cooldown, lower escalation, removing/shrinking blocked hours, moving an app out of a gated category, labeling a new app non-gated, switching to "chats free", turning off grayscale, disabling lockdown, quiz retake with softer results.
- Notification and digest settings apply instantly.

## 18. Permissions & onboarding walkthrough (deep-link each step)
Install ONLY via adb — this avoids Android's "Restricted settings" block on Accessibility and Notification access for sideloaded apps. If the "Restricted setting" dialog still appears, walk me through App info → ⋮ → Allow restricted settings.

Manifest permissions: POST_NOTIFICATIONS, ACCESS_FINE_LOCATION, ACCESS_BACKGROUND_LOCATION, SYSTEM_ALERT_WINDOW, QUERY_ALL_PACKAGES, PACKAGE_USAGE_STATS, RECEIVE_BOOT_COMPLETED, CAMERA, FOREGROUND_SERVICE (+ type).

Walkthrough order:
1. Default launcher.
2. Accessibility service.
3. Notification access.
4. Usage access.
5. Notifications permission.
6. Location → "Allow all the time".
7. Display over other apps.
8. Xiaomi "Display pop-up windows while running in the background" (required so the Gate can open over other apps).
9. HyperOS Autostart ON.
10. Battery saver "No restrictions".
11. Lock app in recents.
12. Show notifications on lock screen.
13. Mute filtered apps' notification channels.
14. WRITE_SECURE_SETTINGS via adb (run it yourself).

## 19. Process
Reference code (study patterns, don't copy wholesale):
- Olauncher — github.com/tanujnotes/Olauncher (launcher)
- FeedLock — github.com/caasion/FeedLock (Instagram screen detection)
- Unhook — github.com/rogerantony-dev/unhook (accessibility scoping, gotchas)
- Catima — github.com/CatimaLoyalty/Android (loyalty cards)

My phone is connected via USB (USB debugging + Install via USB + USB debugging (Security settings) on). Use adb to install and `uiautomator dump` to map the REAL screen structure of Instagram, Snapchat, YouTube, Chrome, Settings and the HyperOS Security app. Don't guess view IDs.

Build in this order. Install and verify each step on the phone before the next:
1. Launcher UI (home, drawer, search, lock-screen notification)
2. Categories + labeling
3. Gate, sessions, limits, blocked hours, grayscale
4. Accessibility enforcement + per-app detectors + Chrome
5. Notification filter + digest
6. Stats + weekly review
7. Quiz + baseline week
8. Focus mode
9. Loyalty cards + geofencing
10. Loophole fixes (new apps, PiP, other browsers)
11. 24 h rule
12. Lockdown (last)

Rules while building:
- Do NOT stop for approval. Only stop if something is impossible on Android 16/HyperOS 3, or I must do something physical on the phone (tell me exact taps).
- HyperOS shows an install confirmation on the phone — tell me when to tap Install.
- If "USB debugging (Security settings)" switched itself off (SecurityException on grants), tell me to turn it back on.
- Re-read SPEC.md whenever unsure. Keep TESTING.md (checklist of everything verified) and RECOVERY.md.
- When finished: remind me to turn off "Stay awake while charging" (AMOLED burn-in risk).
- At the end, report: what works, what's fragile (likely to break on app updates), and the recovery steps.
- Keep code simple and commented.
