# Authenticated Media Stream Analyzer

Java desktop utility for studying authenticated media-delivery workflows in environments you administer or are explicitly authorized to test.

The project provides a compact interface for:

- maintaining an authenticated browser session;
- resolving embedded-player endpoints;
- inspecting DASH manifest metadata;
- diagnosing transport and session behavior;
- tracking multiple analysis jobs with per-item status and progress.

## Responsible use

This project is intended for interoperability research, diagnostics, and analysis of systems and content for which you have explicit authorization. It does not grant access to protected resources and must not be used to bypass access controls, usage policies, or third-party rights.

## Requirements

- Java 17 or newer
- Maven 3.9 or newer
- Microsoft Edge installed on the system
- FFmpeg available in `PATH`

## Build

```text
mvn clean package
```

## Run

```text
mvn exec:java
```

The application uses the system installation of Microsoft Edge. Keep both Edge and the project dependencies up to date.

## Architecture

The codebase separates browser-session handling, stream analysis, media processing, naming, progress reporting, and the Swing user interface into focused components. This keeps the project easier to inspect, test, and maintain.

## Security notes

- Authentication is completed in a real browser window; credentials are not embedded in the source code.
- Session data is stored locally in a dedicated browser profile and should never be committed.
- Logs avoid exposing sensitive request data.
- Certificate verification remains enabled.

## Troubleshooting

If the browser integration stops working after an Edge update, update the Playwright dependency and rebuild the project.

If an authenticated session expires, repeat the normal sign-in flow. Access denials should be treated as authoritative and investigated with the system owner.
