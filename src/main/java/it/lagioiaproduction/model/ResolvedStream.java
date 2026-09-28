package it.lagioiaproduction.model;

public record ResolvedStream(
        String originalUrl,
        String pageTitle,
        String embedUrl,
        String manifestUrl,
        // Header HTTP (separati da CRLF) da inviare a ffmpeg: il manifest richiede il token x-spopactoken.
        String requestHeaders
) {
}