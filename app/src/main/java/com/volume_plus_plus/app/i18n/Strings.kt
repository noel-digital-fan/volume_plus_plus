package com.volume_plus_plus.app.i18n

/**
 * Every user-facing string in the app, with its **English** text as the default.
 *
 * This class is both the contract and the English translation: a translation is an `object` that
 * extends it and overrides the keys it has text for. Anything it doesn't override keeps the English
 * default, which is what makes English the guaranteed fallback — per key, not just per language — so
 * a half-finished translation ships safely and a newly added key never leaves a blank on screen.
 *
 * ### For contributors
 * - **Translating**: don't touch this file. Copy the keys you want into `StringsXx.kt` and override
 *   them there. See `docs/TRANSLATING.md`.
 * - **Adding a string**: add it here with its English text and nothing else — every language picks
 *   up the English default until someone translates it.
 * - Strings that take a value are **functions**, so word order stays the translator's choice.
 *   Prefer that over string concatenation at the call site, which can't be reordered.
 * - `Volume++`, `Shizuku` and `Android` are product names: leave them as-is inside translated text.
 *
 * Reading a string: `strings()` inside a `@Composable`, [Localization.strings] anywhere else.
 */
open class Strings {

    // ── shared vocabulary ───────────────────────────────────────────────────────────────────────

    open val cancel get() = "Cancel"
    open val save get() = "Save"
    open val done get() = "Done"
    open val notDone get() = "Not done"
    open val back get() = "Back"
    open val dismiss get() = "Dismiss"
    open val selected get() = "Selected"
    open val settings get() = "Settings"
    open val tryAgain get() = "Try again"
    open val install get() = "Install"
    open val grant get() = "Grant"
    open val openSettings get() = "Open settings"
    open val off get() = "Off"

    /** A percentage as shown next to a slider, e.g. `40%`. */
    open fun percent(value: Int) = "$value%"

    // ── theme picker ────────────────────────────────────────────────────────────────────────────

    open val theme get() = "Theme"
    open val themeLight get() = "Light"
    open val themeDark get() = "Dark"

    /** The theme picker's "follow the device" option. */
    open val themeSystem get() = "System default"

    // ── language picker ─────────────────────────────────────────────────────────────────────────

    open val language get() = "Language"

    /** The language picker's "follow the device" option — the default until the user overrides it. */
    open val languageSystem get() = "System default"

    // ── bottom navigation ───────────────────────────────────────────────────────────────────────

    open val tabVolume get() = "Volume"
    open val tabMixing get() = "Mixing"
    open val tabOverlay get() = "Overlay"

    // ── Volume tab ──────────────────────────────────────────────────────────────────────────────

    open val volumeTitle get() = "Volume"
    open val volumeSubtitle get() = "Control each sound channel"

    open val streamMedia get() = "Media"
    open val streamCall get() = "Call"
    open val streamRing get() = "Ring"
    open val streamNotification get() = "Notification"
    open val streamAlarm get() = "Alarm"

    /** Snackbar shown when Do Not Disturb refuses a ring/notification volume change. */
    open val dndBlocking get() = "Do Not Disturb is blocking this. Grant access to change it."

    // ── Mixing tab ──────────────────────────────────────────────────────────────────────────────

    open val mixingTitle get() = "Audio mixing"
    open val mixingSubtitle get() = "Let two or more apps play sound at the same time"
    open val mixingSearchApps get() = "Search apps"
    open val mixingHideSystemApps get() = "Hide system apps"

    /** Snackbar when an app's mixing switch couldn't be applied. [app] is the app's own name. */
    open fun mixingCouldntUpdate(app: String) = "Couldn't update $app"

    open val mixingWarning get() =
        "With audio mixing on, some apps may freeze, replay ads, or lose their pause and/or resume " +
            "controls. If an app misbehaves, turn its switch off to go back to normal."

    // Shown over the greyed-out page while Android's own volume panel is in charge.
    open val mixingDisabledTitle get() = "Audio mixing is disabled"
    open val mixingDisabledBody get() =
        "To use audio mixing, turn off \"Use system volume control\" in the Overlay settings."
    open val mixingGoToOverlaySettings get() = "Go to Overlay settings"

    // ── Mixing tab: setup checklist ─────────────────────────────────────────────────────────────

    open val setupIntroRooted get() =
        "Audio mixing needs a privileged helper. This device looks rooted, so root mode is the " +
            "quick way in — or finish the three Shizuku steps below."
    open val setupIntroShizuku get() =
        "Audio mixing runs through Shizuku. Finish these three steps and it unlocks."

    /** A checklist row's heading, e.g. `2. Shizuku service running`. */
    open fun setupStep(number: Int, title: String) = "$number. $title"

    open val setupShizukuInstalled get() = "Shizuku installed"
    open val setupShizukuNotInstalled get() = "Shizuku not installed"
    open val setupShizukuInstallDetail get() =
        "It runs the privileged helper Volume++ needs to change audio focus."

    open val setupServiceRunning get() = "Shizuku service running"
    open val setupServiceNotRunning get() = "Shizuku service not running"
    open val setupServiceStartDetail get() =
        "Start it from inside Shizuku, over wireless debugging or ADB."
    open val setupServerUnusableDetail get() =
        "A leftover Shizuku service from an older install is still running, which is why Shizuku " +
            "says it isn't. Stop it and start Shizuku again."
    open val setupRestartShizuku get() = "Restart Shizuku"
    open val setupSetUpNow get() = "Set up now"

    open val setupAccessGranted get() = "Access granted to Volume++"
    open val setupGrantAccessTitle get() = "Grant access to Volume++"
    open val setupAccessDetail get() = "Let Volume++ control other apps' audio focus."
    open val setupConnectFailedDetail get() = "Shizuku didn't start its privileged service."
    open val setupConnectingDetail get() = "Starting Shizuku's privileged service…"
    open val setupGrantAccess get() = "Grant access"

    // ── Mixing tab: root mode ───────────────────────────────────────────────────────────────────

    open val rootShortcutTitle get() = "Rooted device?"
    open val rootShortcutBody get() = "Skip Shizuku entirely — grant root once and mixing unlocks."
    open val rootUse get() = "Use root mode"
    open val rootRunning get() = "Audio mixing is running through root."
    open val rootGranting get() = "Granting root access…"
    open val rootGrantingDetail get() = "Approve the superuser prompt to finish."
    open val rootRefused get() = "Root access refused"
    open val rootRefusedDetail get() =
        "Your superuser manager turned the request down. Allow Volume++ there, then try again."
    open val rootHelperFailed get() = "Root helper didn't start"
    open val rootHelperFailedDetail get() =
        "Root was granted, but the privileged helper never came back."
    open val rootUseShizukuInstead get() = "Use Shizuku instead"

    // ── Overlay tab ─────────────────────────────────────────────────────────────────────────────

    open val overlayTitle get() = "Overlay"
    open val overlaySubtitle get() = "Replace the system volume panel"
    open val overlayIntro get() =
        "Press the volume keys anywhere to open Volume++'s own panel with a slider for each app " +
            "that's playing. Needs the three permissions below. Per-app sliders also require " +
            "Shizuku running and Android 13+."

    open val overlayStepDrawOver get() = "Draw over other apps"
    open val overlayStepAccessibility get() = "Enable the accessibility service"
    open val overlayStepAccessibilityDetail get() =
        "Volume++ overlay — needed to catch the volume keys."
    open val overlayStepDnd get() = "Allow Do Not Disturb access"
    open val overlayStepDndDetail get() =
        "Needed to switch the ringer to vibrate/silent from the overlay."

    open val overlaySystemPanelInUse get() =
        "Android's own volume panel is in use — the overlay stays off."
    open val overlayReady get() = "Ready — press a volume key to try it."
    open val overlayIncomplete get() = "Complete all three steps above to activate the overlay."

    open val overlayUseSystemPanel get() = "Use system volume control"
    open val overlayUseSystemPanelDetail get() =
        "Leave the volume keys to Android's built-in panel instead of the overlay."

    open val overlayStyle get() = "Style"
    open val overlayEdit get() = "Edit"

    /** Offered only for the Android 9–15 styles — the 7–8 panel has no Settings button. */
    open val overlaySettingsOpensApp get() = "Settings button opens Volume++"
    open val overlaySettingsOpensAppDetail get() =
        "The panel's SETTINGS / SEE MORE button opens Volume++ instead of Android's sound " +
            "settings."

    open val overlayMotion get() = "Motion"
    open val overlayMotionInfo get() =
        "These settings scale the overlay's easing while you hold the volume key. Leave both at " +
            "100% to keep the current behavior, or nudge them if you want the panel to catch up " +
            "faster or settle more softly."
    open val overlayHoldFollowSpeed get() = "Hold follow speed"
    open val overlayHoldSettleSpeed get() = "Hold settle speed"

    open val overlayHaptics get() = "Haptics"
    open val overlayHapticsInfo get() =
        "Optional tap feedback for repeated volume steps. The intensity slider keeps the same " +
            "default feel, but you can make it lighter or stronger if you want."
    open val overlayStepHaptics get() = "Step haptics while holding"
    open val overlayStepHapticsDetail get() = "Light tap feedback on each repeated volume step."
    open val overlayHapticIntensity get() = "Haptic intensity"

    open val overlayPreview get() = "Preview"
    open val overlayGrantToPreview get() = "Grant overlay to preview"

    /** The app name + version line at the foot of the Overlay tab, e.g. `Volume++ 1.1.0`. */
    open fun appVersion(appName: String, version: String) = "$appName $version"

    // ── Overlay tab: per-style edit hub ─────────────────────────────────────────────────────────

    /** Edit-hub heading. [style] is an overlay style's name, e.g. `Android 15`. */
    open fun editStyleTitle(style: String) = "Edit $style"

    open fun editStyleIntro(style: String) =
        "Customise this style on its own. Position and colours are edited separately, and only " +
            "$style is changed."

    open val editPosition get() = "Edit position"
    open val editPositionHint get() =
        "The real panel opens on top of your screen — drag it exactly where you want, then Save or " +
            "Cancel from the floating bar."
    open val editColours get() = "Edit colours"
    open val editColoursHint get() =
        "The panel opens on top of your screen — tap a part of it (or a swatch), dial the colour, " +
            "then Save or Cancel."

    open val editWhichLayout get() = "Which layout?"
    open val editWhichLayoutBody get() =
        "Portrait and landscape are positioned separately. The screen rotates to the layout you " +
            "pick so you position the panel exactly as it'll appear."

    open val orientationPortrait get() = "Portrait"
    open val orientationLandscape get() = "Landscape"

    // ── on-screen live editor ───────────────────────────────────────────────────────────────────

    open val liveEditPositionHint get() = "Drag the panel, or type X / Y (dp)"
    open val liveEditResetPosition get() = "Reset position"
    open val liveEditColourHint get() = "Tap the panel or a swatch, then dial or type a colour"
    open val liveEditUseDefault get() = "Use default"
    open val liveEditPickFromScreen get() = "Pick from screen"

    /** Editor bar's component switch — short forms, so they fit a floating bar. */
    open val liveEditComponentMain get() = "Main"
    open val liveEditComponentExpanded get() = "Expanded"
    open val liveEditComponentOutput get() = "Media output"

    /** Full component names, as used outside the cramped editor bar. */
    open val panelComponentMain get() = "Main panel"
    open val panelComponentExpanded get() = "Expanded panel"
    open val panelComponentOutput get() = "Media output"

    // ── editable colour swatches ────────────────────────────────────────────────────────────────

    open val colourBackground get() = "Background"
    open val colourProgress get() = "Slider / progress"
    open val colourTrack get() = "Slider track"
    open val colourIcon get() = "Icons"
    open val colourAccent get() = "Accent / buttons"
    open val colourText get() = "Text"
    open val colourSecondary get() = "Secondary surface"
    open val colourMediaIcon get() = "Media note icon"
    open val colourModeIcon get() = "Active mode icon"
    open val colourOverflow get() = "Three-dot button"
    open val colourDot get() = "Volume dot"
    open val colourOutputSurface get() = "Output card"
    open val colourDoneBg get() = "Done button"
    open val colourDoneText get() = "Done text"
    open val colourTitle get() = "Title"

    // The Android 15 media-output picker's own palette.
    open val colourOutputCard get() = "Card background"
    open val colourOutputSlider get() = "Slider fill"
    open val colourOutputSliderTrack get() = "Slider track"
    open val colourOutputIcon get() = "Icon"
    open val colourOutputText get() = "Text"
    open val colourOutputDot get() = "Volume dot"
    open val colourOutputConnect get() = "Connect surface"
    open val colourOutputDone get() = "Done button"
    open val colourOutputDoneText get() = "Done text"

    // ── screen eyedropper ───────────────────────────────────────────────────────────────────────

    open val eyedropperPick get() = "Pick colour"
    open val eyedropperUseColour get() = "Use colour"

    /** Loupe hint while browsing for a colour. [hex] is already formatted, e.g. `#3B5BA9`. */
    open fun eyedropperDragToPick(hex: String) = "Drag to pick  ·  $hex"

    open val eyedropperNeedsPermission get() =
        "Screen capture permission is needed to pick a colour"
    open val eyedropperCaptureFailed get() = "Couldn't capture the screen"
    open val eyedropperBlocked get() =
        "That screen blocks screenshots, so there's nothing to pick from"

    // The foreground-service notification the eyedropper is driven from.
    open val eyedropperChannelName get() = "Colour picker"
    open val eyedropperChannelDescription get() =
        "Shown while the screen colour picker is waiting for you to pick."
    open val eyedropperNotificationTitle get() = "Picking a colour"
    open val eyedropperNotificationText get() =
        "Open the app or page you want a colour from, then tap Pick colour."

    // ── the overlay panel itself ────────────────────────────────────────────────────────────────
    // These reproduce the stock Android volume panel. Android's own wording per release is the best
    // reference for a translation: matching it is what makes the skins look right.

    /** Expanded-sheet title on Android 13+. */
    open val panelTitleSoundVibration get() = "Sound & vibration"

    /** Expanded-sheet title on Android 9 and 10. */
    open val panelTitleVolume get() = "Volume"

    /** Expanded-sheet title on Android 11 and 12. */
    open val panelTitleSound get() = "Sound"

    open val panelRowMedia get() = "Media volume"
    open val panelRowCall get() = "Call volume"
    open val panelRowRing get() = "Ring volume"
    open val panelRowNotification get() = "Notification volume"

    /** Short form, used only on the Android 15 sheet's greyed-out Notification row. */
    open val panelRowNotificationShort get() = "Notification"
    open val panelRowRingNotification get() = "Ring & notification volume"
    open val panelRowAlarm get() = "Alarm volume"

    /** Why the Notification row is inert while the ringer is muted. */
    open val panelNotificationUnavailable get() = "Unavailable because ring is muted"

    /** Footer buttons. The all-caps forms are the older skins' styling — capitalise as your
     *  language would, or leave them lowercase if all-caps reads badly in it. */
    open val panelSeeMore get() = "SEE MORE"
    open val panelDoneCaps get() = "DONE"
    open val panelSettings get() = "Settings"
    open val panelDone get() = "Done"

    // The Android 7–8 skin's Do Not Disturb footer.
    open val panelAlarmsOnly get() = "Alarms only"
    open val panelAlarmsOnlyDetail get() = "Until you turn off Do Not Disturb"
    open val panelTurnOffNow get() = "TURN OFF NOW"

    // The Android 15 skin's media-output card and picker.
    open val panelAudioWillPlayOn get() = "Audio will play on"
    open val panelConnectADevice get() = "Connect a device"
    open val outputThisPhone get() = "This phone"
    open val outputWiredHeadphones get() = "Wired headphones"
    open val outputUsbHeadphones get() = "USB headphones"
    open val outputHeadphones get() = "Headphones"
}

/** English — [Strings] exactly as written, and the fallback every other language inherits from. */
object EnglishStrings : Strings()
