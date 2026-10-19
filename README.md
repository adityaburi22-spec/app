# FeedTrainer — Dopamine-Friendly Feed Steering

FeedTrainer solves reels and short-form video addiction not by blocking them cold-turkey (which crashes user dopamine and leads to app uninstallation), but by actively training and steering recommendation algorithms toward the user's life goals (coding, AI, science, fitness, investing, deep work, books).

Uses **on-device Android Accessibility** to read visible reel captions on Instagram Reels and YouTube Shorts, classifies them against the user's chosen life goals via an **on-device INT8 all-MiniLM-L6-v2 ONNX embedding model**, and executes **paced, rate-limited, user-opted-in Like actions** to steer the feed.

---

## Key Architecture & Features

### 1. On-Device AI Embedding & Classifier (Brain)
- **Model**: `model.int8.onnx` (22.9 MB quantized all-MiniLM-L6-v2) + `tokenizer.json` loaded via ONNX Runtime Android & HuggingFace DJL tokenizers.
- **Classification**: Embeds caption text into 384-dimensional vector space and calculates cosine similarity against user's target goal vectors embedded at onboarding.
- **Fail-safe Fallback**: In case of model initialization delays, seamlessly falls back to `MockLocalFilter` dynamic keyword matching engine.

### 2. Capture & Steering Engine (Capture & Actions)
- **Accessibility Service**: `FeedTrainerService` listens to window content changes in Instagram (`com.instagram.android`) and YouTube (`com.google.android.youtube`).
- **Resilient Node Extraction**: Centralized ID lookup (`clips_caption`, `reel_description`, etc.) with full text tree traversal fallback (`collectVisibleText`).
- **Ethical & Paced Action Execution**:
  - Daily cap enforced (default 40 actions/day).
  - Minimum spacing throttle (3500ms between actions).
  - Clear user-facing toasts on every action.
  - Automatic detection of like buttons via resource IDs, content description, and clickable text.

### 3. Chinese OEM & Aggressive Battery Killer Resistance (Persistence)
- **KeepAlive Foreground Service**: `KeepAliveService` runs as an ongoing low-importance foreground service (`specialUse` foregroundServiceType for Android 14+ / API 34).
- **Battery Optimization Exemption**: Automatically requests `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- **Manufacturer-Specific Anti-Kill Helper**: `OemKeepAliveHelper` automatically identifies device manufacturer (Vivo, Xiaomi, Oppo, Realme, OnePlus, Samsung, Huawei, Infinix, Tecno) and opens device-specific autostart and background power management settings.
- **Boot & Unlock Receiver**: `BootReceiver` restarts `KeepAliveService` on device boot (`BOOT_COMPLETED`), package update (`MY_PACKAGE_REPLACED`), and screen unlock (`USER_PRESENT`).

### 4. Supabase SaaS Authentication & Subscription Sync
- **Backend**: Supabase Auth + REST API (`https://ddsvhkhbxlochsrmntwa.supabase.co`).
- **Features**:
  - Sign Up and Sign In via email & password.
  - User goals sync to Supabase `user_metadata` without modifying or disrupting existing website tables (`payments`, `affiliates`, `subscriptions`).
  - Active subscription verification from the `subscriptions` table.
  - Guest mode for instantaneous offline testing.

### 5. Interactive Dashboard & In-App AI Sandbox
- **Real-time Metrics**: Live counters for Reels Scanned, Reels Steered (Liked), and Daily Cap progress.
- **Interactive AI Simulator**: Allows user to input any caption (e.g. "How to build neural networks" vs "Crazy prank gone wrong") and test the on-device AI cosine classification in real time.
- **Live Activity Log**: Displays the last 15 steered reels with platform badge, creator, caption snippet, and matched goal.
- **Custom Goal Manager**: Easy toggle of preset goals or adding custom goals.

---

## Build Artifacts & Testing

- **Android Studio Project**: Located in `android_studio_project/`
- **Debug APK (Signed & Ready to Install)**:
  `android_studio_project/app/build/outputs/apk/debug/app-debug.apk` (97.5 MB)
- **Release APK**:
  `android_studio_project/app/build/outputs/apk/release/app-release-unsigned.apk` (95.0 MB)
- **Unit Tests**:
  `./gradlew test` -> 100% tests passed (`FeedTrainerLogicTest`).
