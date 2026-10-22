# Redraft: UI, Settings, Themes, Donations and Release Prep

**Where this fits:** Phase 1 (the viewer) is done. Phase 2 (selecting and editing text) is the core of the project. This plan is a time-boxed detour between them, so the app looks and feels like a real product and is ready to ship to the Orion Store. Put it in `docs/` next to `ROADMAP.md`.

**Rule for this detour:** only build things that work today. The editing features don't exist yet, so don't add settings or UI for them.

---

## 1. Current state (from the repo, `main` at the merge of PR #1)

- Jetpack Compose + Material 3, Navigation Compose with three routes: `home`, `viewer`, `overview` (`RedraftNavHost.kt`).
- Theme is set up in `MainActivity.kt`: system light/dark, with dynamic color on Android 12+. On older devices it falls back to the default Material purple, so there's no Redraft look yet.
- `themes.xml` uses `android:Theme.Material.Light.NoActionBar` as its parent, so the launch window is always light. In dark mode this causes a white flash before Compose draws.
- One DataStore exists (for recents). `RecentFilesRepository.kt` documents a past crash from creating a second DataStore for the same file, so any new settings store must follow the same pattern: a single `preferencesDataStore` delegate per file.
- `applicationId` and `namespace` are both `com.redraft`. `versionName` is `1.0`, `versionCode` is `1`.
- No signing config, no `INTERNET` permission in the manifest, MIT `LICENSE` present.
- Top bar on Home is a bare `CenterAlignedTopAppBar` with no actions, so there is nowhere to reach settings yet.

---

## 2. Work packages (in this order)

### A. Theme system (do first, everything else builds on it)

- [ ] Create `ui/theme/Theme.kt` with a `RedraftTheme(themeMode, dynamicColor, amoled)` composable, and move the color-scheme logic out of `MainActivity`.
- [ ] `ThemeMode` enum: `SYSTEM`, `LIGHT`, `DARK`.
- [ ] Choose a brand seed color and generate static light and dark color schemes, so devices below Android 12 (and users who turn dynamic color off) still get a Redraft look.
- [ ] Optional AMOLED mode: true-black background in dark theme.
- [ ] Add `Type.kt` with a small, deliberate typography (don't over-design).
- [ ] Fix the launch flash: change the XML theme parent to a DayNight variant and add `values-night/themes.xml`, so the window background matches before Compose draws.
- [ ] Check system bar icon colors in all modes (you already use `enableEdgeToEdge()`).

### B. Settings storage

- [ ] `data/SettingsRepository.kt` using its own DataStore file (`settings`), via a single top-level `preferencesDataStore` delegate (same pattern as recents).
- [ ] Keys: theme mode, dynamic color on/off, AMOLED on/off, plus only the viewer options you actually implement (see C).
- [ ] Expose settings as a `StateFlow` through a `SettingsViewModel`. `MainActivity` collects it to build the theme, so changes apply instantly.
- [ ] Keep the logic Android-free where possible and unit-test it, like the 88 existing tests.

### C. Settings screen

- [ ] New route `settings`; add a gear icon to the Home top bar.
- [ ] **Appearance:** theme mode, dynamic color (shown only on Android 12+), AMOLED black.
- [ ] **Viewer:** only options that work now, for example keep screen on while reading and whether to reopen at the last page. Skip anything you can't test.
- [ ] **Storage:** clear recents; clear bitmap and thumbnail caches (you already have the cache classes).
- [ ] **About** and **Support** entries at the bottom.

### D. About screen

- [ ] App name, version (`BuildConfig.VERSION_NAME`), one-line description.
- [ ] Honest status line: "Viewer today; true content editing is in development."
- [ ] Links: source code (GitHub), issue tracker, license (MIT).
- [ ] **Open-source notices:** list each dependency and its license (PDFium, pdfiumandroid, Jetpack/Compose, and so on). Verify each license yourself; don't copy from memory.
- [ ] Privacy statement: works offline, files stay on the device, no analytics, no tracking. Only claim this while it stays true (see section 3).

### E. Donations ("Support Redraft")

- [ ] A card in Settings (and a link on About) with a short, honest message. No pop-ups or nagging.
- [ ] Use external links only: GitHub Sponsors, Ko-fi, or Buy Me a Coffee, and optionally a local option such as a mobile-money or payment-link page for supporters in Ghana. Open them with `ACTION_VIEW` (or a Custom Tab). No billing library, and no `INTERNET` permission is needed.
- [ ] Keep all URLs in one `SupportLinks.kt` so they're easy to change.
- [ ] Never lock features behind donating.
- [ ] Before shipping this to any store other than Orion (for example Google Play), check that store's payment and donation rules first.

### F. Home and viewer polish (pick what's cheap, skip the rest)

- [ ] Home: empty state when there are no recents (icon plus short message); show file size or last-opened date in recents.
- [ ] Viewer: page indicator ("3 / 12"), a reset-zoom or fit-width button, tap to hide and show the bars.
- [ ] Consistent spacing, touch targets of at least 48dp, and content descriptions on all icons.
- [ ] Optional later: a reading "night mode" that inverts page colors. Skip it for now, because it interacts with rendering and later editing.

### G. App identity

- [ ] Final app icon: adaptive icon plus a monochrome layer for themed icons on Android 13+.
- [ ] Decide the splash look (the default splash is fine to start).

### H. Release prep (Orion Store)

- [ ] **Decide the `applicationId` now.** `com.redraft` is generic and may collide with someone else's. Once you publish, changing it creates a different app and breaks updates for existing users. Something tied to you, such as `com.<yourname>.redraft`, is safer. (`namespace` can stay as it is.)
- [ ] **Release signing:** create a release keystore, back it up in two places, and never commit it. Read the passwords from `local.properties` or environment variables. Orion checks developer signatures, so losing the key or switching keys will block updates for your users.
- [ ] **Test the release build, not just debug.** R8 shrinking is enabled for release. Install the release APK and confirm that opening, rendering, zoom, and the overview all work. Add keep rules if PDFium/JNI classes get stripped.
- [ ] **ABIs:** you ship `arm64-v8a` and `x86_64` only, so devices that are 32-bit only can't install. Check whether `pdfiumandroid` provides `armeabi-v7a`, and decide deliberately.
- [ ] **Versioning:** increment `versionCode` on every release and use semantic `versionName` (for example `0.1.0`). Tag releases in git as `v0.1.0`.
- [ ] **GitHub Release:** attach the signed APK, with a short changelog and the SHA-256 checksum.
- [ ] **Repo hygiene:** screenshots in the README, a clear "what works / what doesn't yet" section (already there), `LICENSE` (present), and issue templates.
- [ ] **Submission:** Orion's own README says to submit through the Orion client or its repository submission form. Read their current submission rules before applying. Third-party sources say its apps are downloaded straight from the developer's releases and that submissions are reviewed manually for safety and open-source status; confirm that this still applies.

---

## 3. Guardrails

- Keep the app free of the `INTERNET` permission for as long as possible. It is a strong privacy story, and it keeps the About page's claims honest. If you ever add update checks or crash reporting, update the privacy text in the same change.
- No setting without a working feature behind it.
- Don't let this detour grow. Time-box it, then return to Phase 2 (selecting and editing text), which is what makes Redraft different.

---

## 4. Definition of done

- [ ] Theme switching works instantly and survives restarting the app; no white flash on launch in dark mode.
- [ ] Settings, About, and Support screens reachable from Home, with working links.
- [ ] Release APK builds, is signed with your own key, and passes the manual test checklist on a real device.
- [ ] GitHub Release `v0.1.0` published with the APK; Orion submission sent.
- [ ] Unit tests still pass (`./gradlew :app:testDebugUnitTest`) and new settings logic has tests.

---

## 5. Using GSD for this

Ask GSD to add this as a phase between Phase 1 and Phase 2 (run `/gsd-help` for the command that inserts a phase). Point it at this file. Keep sequential execution and review the plan before it runs.
