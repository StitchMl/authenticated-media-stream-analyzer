# Teams Stream Lecture Downloader

Desktop application in Java for downloading Microsoft Stream / SharePoint lecture recordings that you are already authorized to access.

The application automates:
- Microsoft login session persistence
- Stream/SharePoint embed resolution
- DASH manifest interception
- direct media download through [`ffmpeg`](https://www.ffmpeg.org/download.html)
- multi-download queue management with per-file progress UI

> Use this tool only for content you are authorized to access and download.

---

## Prerequisites

Before using the application, make sure the following software is installed on your system:

### 1. Microsoft Edge
The application always drives the **Microsoft Edge** installed on your system (Playwright channel `msedge`).
Edge updates itself automatically; keep it up to date from `edge://settings/help`.

### 2. FFmpeg
This application relies on [`ffmpeg`](https://www.ffmpeg.org/download.html) to download and save video streams.

Install FFmpeg first and make sure it is available in your system `PATH`.

You can verify the installation with:

```bash
ffmpeg -version
```

---

## Features

- Modern desktop UI built with Swing + FlatLaf
- Persistent Microsoft session (`state.json`) after the first login
- Multiple links management with one box per recording
- Add / remove links dynamically
- Parallel downloads
- Automatic file naming based on course and lecture number
- Per-file progress tracking with status and ETA
- Download through `ffmpeg` without re-encoding (`-c copy`)

---

## How it works

Given a Microsoft Stream / SharePoint recording URL, the application:

1. opens the page with an authenticated browser session
2. retrieves the embed page URL
3. intercepts the `videomanifest` request
4. preserves the complete signed DASH URL and checks the authenticated manifest
5. downloads supported DASH video with `ffmpeg`

Generated filenames follow the pattern:

```text
<COURSE_CODE> - Lecture <N>.mp4
````

---

## Requirements

* Java 17+
* Maven 3.9+
* Microsoft Edge (installed system-wide)
* `ffmpeg` installed and available in `PATH`
---

## Tech stack

* **Java 17**
* **Swing**
* **FlatLaf**
* **Playwright for Java**
* **ffmpeg**

---

## Project structure

```text
src/main/java/it/lagioiaproduction/
├─ app/
│  └─ TeamsLectureDownloaderApp.java
├─ ui/
│  ├─ MainFrame.java
│  ├─ theme/
│  │  ├─ AppColors.java
│  │  └─ AppTheme.java
│  ├─ components/
│  │  ├─ BadgeLabel.java
│  │  ├─ HintTextArea.java
│  │  ├─ LinkItemPanel.java
│  │  ├─ ModernButton.java
│  │  ├─ ProgressItemPanel.java
│  │  ├─ RoundedPanel.java
│  │  └─ ScrollableContentPanel.java
│  └─ sections/
│     ├─ HeaderSection.java
│     ├─ InputSection.java
│     ├─ ProgressSection.java
│     └─ StatsSection.java
├─ core/
│  ├─ DownloadCoordinator.java
│  ├─ FileNameGenerator.java
│  ├─ FfmpegRunner.java
│  ├─ PlaywrightBrowserFactory.java
│  ├─ StreamLoginService.java
│  └─ StreamManifestResolver.java
└─ model/
   ├─ DownloadProgress.java
   ├─ DownloadRequest.java
   ├─ DownloadSummary.java
   └─ ResolvedStream.java
```

---

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/<your-username>/teams-stream-lecture-downloader.git
cd teams-stream-lecture-downloader
```

### 2. Build the project

```bash
mvn clean package
```

No Playwright browser download is needed: the app uses the system Microsoft Edge.

### 3. Build the Windows installer (optional)

Requires the JDK `jpackage` tool and [WiX Toolset 3.x](https://github.com/wixtoolset/wix3/releases) in `PATH`:

```powershell
.uild-exe.ps1
```

The installer is written to `target/dist/`.

---

## Run the application

### From Maven

```bash
mvn exec:java -Dexec.mainClass="it.lagioiaproduction.app.TeamsLectureDownloaderApp"
```

### From the packaged JAR

```bash
java -jar target/teams-stream-lecture-downloader-1.0.6.jar
```

---

## Usage

### 1. Save your Microsoft session

Click **Login Microsoft** and complete the authentication flow in the Edge window
(answer **Yes** to "Stay signed in?"). The window closes by itself once the login is really completed.

Login and downloads share a dedicated, persistent Edge profile stored in:

```text
%LOCALAPPDATA%\TeamsStreamLectureDownloader\edge-profile
```

so the Microsoft session behaves like in a normal browser (cookies, tokens, Windows single sign-on).

### 2. Add one or more recording links

Use the **+ Nuovo link** button to add separate input boxes and paste one URL per box.

### 3. Choose output folder

Select the destination directory where videos will be saved.

### 4. Set parallel downloads

Choose how many files should be processed at the same time.

### 5. Start download

Click **Scarica video**.

---

## Output naming

The application tries to infer:

* the course acronym from the SharePoint site slug
* the lecture number from the page title

---

## Authentication

Authentication is not handled through embedded credentials in the source code.

Instead, the application:

* opens a real browser window
* lets the user complete Microsoft login and MFA
* keeps the session in a dedicated local Edge profile

This approach is safer and more robust than hardcoding credentials.

### Important

Do **not** commit this file:

```text
edge-profile/ and playwright/.auth/state.json
```

---

## Progress tracking

For each file, the UI shows:

* current status
* progress bar
* estimated progress percentage
* elapsed / total time
* download speed
* ETA when available

Progress information is parsed from `ffmpeg` output.

---

## Troubleshooting

### `ffmpeg` not found

Make sure `ffmpeg` is installed and accessible from the system `PATH`.

Check with:

```bash
ffmpeg -version
```

### Browser closes immediately / `TargetClosedError`

Edge is updated automatically, so an old Playwright version may no longer be able to drive it.
The Playwright version is defined by the `playwright.version` property in `pom.xml` and is kept
up to date by Dependabot (`.github/dependabot.yml`). Update it, rebuild, and make sure Edge is up to date.

### Session expired

If downloads stop working because authentication is no longer valid:

the app stops with "Sessione Microsoft non valida o scaduta": click **Login Microsoft** again.
If the problem persists, delete `%LOCALAPPDATA%\TeamsStreamLectureDownloader\edge-profile` and log in again.

### "Non ha accesso a questa registrazione"

The account used for the login cannot open that recording: check the link or log in with the right account.

---

## Development notes

Main responsibilities are split as follows:

* `DownloadCoordinator`: application orchestration
* `PlaywrightBrowserFactory`: single place where Playwright and Microsoft Edge are launched
* `StreamLoginService`: Microsoft login and auth state persistence
* `StreamManifestResolver`: embed extraction and manifest interception
* `FfmpegRunner`: media download and progress parsing
* `FileNameGenerator`: output naming strategy
* `ui/*`: application interface

This separation keeps the project readable and easier to maintain.

### HTTPS and encrypted video

HTTPS transport is supported by FFmpeg builds with HTTPS/TLS enabled. The app forwards
captured authentication, origin, referer and user-agent headers and applies a 30-second
network I/O timeout. It preserves signed URL parameters.

DASH SEA AES-128-CBC is supported for static recordings with one Period,
an explicit fixed IV, HTTP key delivery and SegmentTemplate/SegmentTimeline addressing.
The authenticated Edge page fetches keys with the captured `x-spopactoken` and media
with browser cookies, matching the player. Sensitive requests bypass browser cache and
certificate verification remains enabled;
Java decrypts each full segment using AES-CBC with PKCS#7 padding. Initialization segments
are validated as MP4 and decrypted when SharePoint also encrypts them; clear initialization
segments remain unchanged. FFmpeg combines the highest-bandwidth video representation and audio
without re-encoding. Signed URL parameters are preserved.

Keys stay in memory and are cleared after use. Temporary decrypted tracks are removed on
success and failure. Browser downloads are serialized to protect the persistent Edge profile;
up to four segment requests run concurrently within a recording. Other encryption schemes,
key rotation, live streams and unsupported manifest layouts produce explicit errors.
Signed URLs and session headers are renewed through the player after a media HTTP 401,
with a bounded retry count and a check that representation and timeline still match.
Persistent key or media access denials are honored; no certificate checks are disabled.

HTTP 401/403, TLS failures and unsupported protocols are reported separately.
Failure logs mask URL query strings and sensitive HTTP headers.
