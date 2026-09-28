package it.lagioiaproduction.model;

public record ResolvedStream(
        String originalUrl,
        String pageTitle,
        String embedUrl,
        String manifestUrl,
        // Header HTTP (separati da CRLF) da inviare a ffmpeg: il manifest richiede il token x-spopactoken.
        String requestHeaders,
        String dashManifest
) {
    public ResolvedStream(String originalUrl, String pageTitle, String embedUrl, String manifestUrl, String requestHeaders) {
        this(originalUrl, pageTitle, embedUrl, manifestUrl, requestHeaders, null);
    }

    public boolean encrypted() {
        return dashManifest != null;
    }
}
