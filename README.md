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

### 1. FFmpeg
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
4. extracts the cleaned DASH manifest URL up to `format=dash`
5. downloads the video with `ffmpeg`

Generated filenames follow the pattern:

```text
<COURSE_CODE> - Lecture <N>.mp4
````

---

## Requirements

* Java 17+
* Maven 3.9+
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

### 2. Install Playwright browsers

```bash
mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install"
```

### 3. Build the project

```bash
mvn clean package
```

---

## Run the application

### From Maven

```bash
mvn exec:java -Dexec.mainClass="it.lagioiaproduction.app.TeamsLectureDownloaderApp"
```

### From the packaged JAR

```bash
java -jar target/teams-stream-lecture-downloader-1.0.0.jar
```

---

## Usage

### 1. Save your Microsoft session

Click **Login Microsoft** and complete the authentication flow in the browser window.

The application stores the authenticated session in:

```text
playwright/.auth/state.json
```

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
* stores Playwright browser state locally

This approach is safer and more robust than hardcoding credentials.

### Important

Do **not** commit this file:

```text
playwright/.auth/state.json
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

### Session expired

If downloads stop working because authentication is no longer valid:

1. delete the saved auth state if needed
2. run the app again
3. click **Login Microsoft**

---

## Development notes

Main responsibilities are split as follows:

* `DownloadCoordinator`: application orchestration
* `StreamLoginService`: Microsoft login and auth state persistence
* `StreamManifestResolver`: embed extraction and manifest interception
* `FfmpegRunner`: media download and progress parsing
* `FileNameGenerator`: output naming strategy
* `ui/*`: application interface

This separation keeps the project readable and easier to maintain.
