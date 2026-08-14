# Translating Volume++

Every user-facing string in the app lives in one place:

```
app/src/main/java/com/volume_plus_plus/app/i18n/
├── Strings.kt        ← the key list + the English text (the source of truth)
├── StringsEs.kt      ← Español
├── StringsPt.kt      ← Português
├── StringsFr.kt      ← Français
├── Language.kt       ← the registry of shipped languages
├── Labels.kt         ← display names for the app's enums
└── Localization.kt   ← how the app reads the current translation
```

There are no strings scattered through the screens, and (almost) none in `res/values/`. If you want to
translate the app, `Strings.kt` is the only file you need to read and a single new file is the only
thing you need to write.

## Fixing or improving an existing language

Open the `StringsXx.kt` for it and edit the text. That's the whole job — no XML, no key lists to keep
in sync, no build files.

## Adding a new language

Two steps.

### 1. Create the translation file

Copy `StringsEs.kt` to `StringsDe.kt` (using German as the example), rename the object, and replace
the text:

```kotlin
package com.volume_plus_plus.app.i18n

/** Deutsch — German. */
object GermanStrings : Strings() {

    override val cancel get() = "Abbrechen"
    override val save get() = "Speichern"
    override val volumeTitle get() = "Lautstärke"
    // ...
}
```

**You do not have to translate everything.** Every key you leave out keeps its English text, so a
file with twenty keys in it is a perfectly good first pull request. Translate the rest whenever.

### 2. Register it

Add one line to the `Language` enum in `Language.kt`:

```kotlin
enum class Language(val code: String, val endonym: String, val strings: Strings) {
    ENGLISH("en", "English", EnglishStrings),
    SPANISH("es", "Español", SpanishStrings),
    PORTUGUESE("pt", "Português", PortugueseStrings),
    FRENCH("fr", "Français", FrenchStrings),
    GERMAN("de", "Deutsch", GermanStrings),   // ← new
}
```

- `code` is the [ISO 639-1](https://en.wikipedia.org/wiki/List_of_ISO_639_language_codes) code. It is
  what the app matches the device's locale against, so `de-AT` and `de-CH` both find `de`.
- `endonym` is the language's name **in that language** — `Deutsch`, not `German`. The picker shows
  it untranslated, because the person hunting for it is the person who reads that language.

That's it. The language picker, the automatic system-language detection and the fallback chain all
read this enum, so nothing else needs touching.

## Adding a new string (for code contributors)

Add it to `Strings.kt` with its English text and stop there. Every language inherits the English
default until someone translates it, so adding a key never breaks a build or leaves a blank on
screen.

Strings that interpolate a value are **functions**, not concatenation at the call site, so that
translators can move the value where their grammar wants it:

```kotlin
// in Strings.kt
open fun mixingCouldntUpdate(app: String) = "Couldn't update $app"

// in StringsFr.kt
override fun mixingCouldntUpdate(app: String) = "Impossible de mettre à jour $app"
```

Read strings with `strings()` inside a `@Composable`, and with `Localization.strings` anywhere else
(the overlay's Views, the services, notifications).

## Conventions

- **Don't translate product names**: `Volume++`, `Shizuku`, `Android`, and Android version numbers
  ("Android 15") stay as they are, including inside a translated sentence.
- **The volume panel imitates Android's own.** For the `panel*` and `output*` keys — `Sound &
  vibration`, `Media volume`, `Audio will play on`, `This phone` — the best reference is what your
  language's own Android build says in its volume panel. Matching it is what makes the skins look
  right.
- **All-caps keys** (`panelSeeMore`, `panelDoneCaps`, `panelTurnOffNow`) reproduce the older skins'
  styling. Capitalise as your language would, or leave them in normal case if all-caps reads badly.
- **The endonym is never translated.** `Language.endonym` is the one string that must stay in its own
  language in every translation.
- **`percent` and `appVersion` are pure formatting** — no words in them — so the shipped translations
  leave both inherited. Override `percent` if your language puts a space before the sign
  (`40 %` rather than `40%`); it is used both in the app and in the overlay's slider pill, whose
  reserved width is measured from `percent(100)`, so a wider form is handled.

## The three exceptions in `res/values/`

`app_name` and the accessibility service's label and description are rendered by **Android**, not by
us — the launcher draws one, and system Settings draws the other two by reading them out of the APK.
There is no way to hand the OS an in-app override, so those three are translated the platform's way,
in `res/values-es/`, `res/values-pt/` and `res/values-fr/`.

The practical consequence: they follow the **device** language, not the in-app language setting. If
you set the app to Español on an English phone, the app is Spanish but its entry in system Settings
stays English. That's a platform limitation, not a bug.

## Configuration

`config/AppConfig.kt` holds the app-wide defaults — which theme and language a fresh install starts
on, which sets the pickers offer, and the app version. `config/AppSettings.kt` holds the user's own
choices and persists them. Neither needs editing to add a language.
