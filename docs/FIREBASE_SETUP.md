# Firebase setup (Buddy)

The real `app/google-services.json` is **gitignored**. Never commit API keys or a production Firebase config.

## Local / device builds

1. Create a Firebase project and register an Android app with package `com.screen.buddy`.
2. Enable **Firebase AI Logic** (Gemini Developer API) and **App Check**.
3. Download `google-services.json` into `app/`.
4. Or copy the placeholder and replace values:

```text
copy app\google-services.json.example app\google-services.json
```

5. For debug builds, register your App Check debug token in the Firebase console (see Android Gemini Live docs).

Until a real Firebase project is connected, the app launches but Live sessions (M5) will fail.
