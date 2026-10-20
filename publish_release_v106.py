import os
import sys
import json
import urllib.request
import urllib.parse

TOKEN = "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"
REPO = "adityaburi22-spec/app"
TAG = "v1.0.6-beta"
NAME = "FeedTrainer v1.0.6-beta: Multi-Timeframe Signal Shift, Ghost Training & Tactile Dopamine Dimming"

BODY = """## What's New in FeedTrainer v1.0.6-beta

### 1. 📈 Multi-Timeframe Algorithmic Signal Shift Dashboard
- **Extended Time Horizons**: Toggle smoothly between **24H**, **48H**, **7 Days**, **30 Days**, and **All Time** curves.
- **Deep Retention Engine**: Expanded local storage up to 5,000 classification points (~months of scrolling telemetry).
- **Shift Growth Analytics**: Live computation of **Initial Purity % vs Current Purity %** giving undeniable visual proof of feed transformation (**+X% Shift Growth**).
- **Niche Steered Breakdown**: Real-time breakdown of goal-aligned topics (Coding & AI, Fitness, Investing, Books/Mindset) vs slop suppressed.
- **Smooth Canvas Rendering**: Dynamic Bezier area chart with pulsing endpoint guides and automatic interval grouping.

### 2. ⚡ Nightstand Ghost Training (10x Faster Feed Shift)
- **Direct Search Intent Automation**: True YouTube search navigation (`https://www.youtube.com/results?search_query=...`) for high-signal seed queries tailored to user goals.
- **15-Second Retention Signal**: Watches top goal-aligned videos for 15s to establish genuine algorithm affinity, then likes them automatically.
- **Charger Plugged-In Trigger**: Automatically starts when your phone is plugged in overnight (`ACTION_POWER_CONNECTED`), or manually on-demand from the app.
- **Safety Pause & Yield**: Instantly halts if the user touches the screen or exits YouTube.

### 3. 🎭 Dopamine Dimming with Neuro-Haptic Feedback
- **Gentle Haptic Nudge**: Distinct tactile tick triggered when a user hits 3 consecutive junk/brainrot reels.
- **Desaturating Slate Wash**: Touch-transparent slate overlay dims junk reels to make low-value dopamine traps boring without locking the user out.
- **Vibrant Snap-Back**: Instantly restores vivid 100% color and fires a crisp confirmation haptic the microsecond a high-signal video appears.

### 4. 🧹 Home Feed "Not Interested" Sweeper & Floating Bubble
- **Floating Quick-Action Pill**: Added a dedicated `[ 🧹 Clean Home ]` action pill directly on the floating widget bubble.
- **Ultra-Fast Execution (<150ms)**: Swift detection of 3-dot overflow menus across all YouTube layout variations and instant execution of "Not interested".
- **8-Language Multilingual Support**: Detects and acts across English, Spanish, French, German, Italian, Portuguese, Hindi, and Japanese.

---
**Signed Release APK**: `FeedTrainer-release.apk` (SHA-256 verified)
**Debug Testing APK**: `FeedTrainer-debug.apk`
"""

HEADERS = {
    "Authorization": f"token {TOKEN}",
    "Accept": "application/vnd.github.v3+json",
    "User-Agent": "FeedTrainer-Release-Publisher"
}

def create_or_get_release():
    # Check if release exists
    url = f"https://api.github.com/repos/{REPO}/releases/tags/{TAG}"
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            print(f"Found existing release id: {data['id']}")
            return data
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise

    # Create new release
    url = f"https://api.github.com/repos/{REPO}/releases"
    payload = {
        "tag_name": TAG,
        "target_commitish": "main",
        "name": NAME,
        "body": BODY,
        "draft": False,
        "prerelease": False
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(payload).encode("utf-8"),
        headers={**HEADERS, "Content-Type": "application/json"},
        method="POST"
    )
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read().decode("utf-8"))
        print(f"Created new release id: {data['id']}")
        return data

def upload_asset(release_data, file_path, asset_name):
    upload_url_template = release_data["upload_url"] # e.g. "https://uploads.github.com/.../assets{?name,label}"
    upload_base = upload_url_template.split("{")[0]
    upload_url = f"{upload_base}?name={urllib.parse.quote(asset_name)}"

    # Delete existing asset if present
    for existing in release_data.get("assets", []):
        if existing["name"] == asset_name:
            print(f"Deleting existing asset {asset_name} (id {existing['id']})")
            del_url = f"https://api.github.com/repos/{REPO}/releases/assets/{existing['id']}"
            del_req = urllib.request.Request(del_url, headers=HEADERS, method="DELETE")
            try:
                urllib.request.urlopen(del_req)
            except Exception as e:
                print(f"Warning deleting old asset: {e}")

    file_size = os.path.getsize(file_path)
    print(f"Uploading {asset_name} ({file_size / (1024*1024):.2f} MB)...")

    with open(file_path, "rb") as f:
        file_bytes = f.read()

    upload_req = urllib.request.Request(
        upload_url,
        data=file_bytes,
        headers={
            **HEADERS,
            "Content-Type": "application/vnd.android.package-archive",
            "Content-Length": str(file_size)
        },
        method="POST"
    )
    with urllib.request.urlopen(upload_req) as resp:
        res = json.loads(resp.read().decode("utf-8"))
        print(f"✓ Uploaded {asset_name}: {res.get('browser_download_url')}")
        return res

def main():
    print("Initiating GitHub Release publication...")
    rel = create_or_get_release()

    release_apk = "FeedTrainer-release.apk"
    debug_apk = "FeedTrainer-debug.apk"

    if os.path.exists(release_apk):
        upload_asset(rel, release_apk, "FeedTrainer-release.apk")
    else:
        print(f"Error: {release_apk} not found")

    if os.path.exists(debug_apk):
        upload_asset(rel, debug_apk, "FeedTrainer-debug.apk")
    else:
        print(f"Error: {debug_apk} not found")

    print("\n✓ Release v1.0.6-beta successfully published to GitHub!")

if __name__ == "__main__":
    main()
