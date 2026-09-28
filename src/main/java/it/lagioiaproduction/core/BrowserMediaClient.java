package it.lagioiaproduction.core;

import com.microsoft.playwright.Page;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Uses Edge's certificate store and the authenticated page's network stack. */
final class BrowserMediaClient {
    private BrowserMediaClient() { }

    static final class UnauthorizedMediaException extends StreamManifestResolver.NonRetryableResolveException {
        UnauthorizedMediaException() {
            super("Accesso negato alla risorsa media (HTTP 401): verifica login e permessi.", null);
        }
    }

    private static final String FETCH = """
        async ({urls, headers, text}) => await Promise.all(urls.map(async url => {
          for (let attempt = 0; attempt < 3; attempt++) {
            try {
              const controller = new AbortController();
              const timer = setTimeout(() => controller.abort(), 30000);
              let response, body;
              try {
                response = await fetch(url, {headers, credentials: Object.keys(headers).length ? 'same-origin' : 'include', cache: 'no-store', signal: controller.signal});
                if (response.ok) body = text ? await response.text() : new Uint8Array(await response.arrayBuffer());
              } finally { clearTimeout(timer); }
              if (response.ok) {
                if (text) return {status: response.status, body};
                let binary = '';
                for (let i = 0; i < body.length; i += 8192)
                  binary += String.fromCharCode(...body.subarray(i, i + 8192));
                return {status: response.status, body: btoa(binary)};
              }
              // Some media CDN responses transiently reject a signed request. Retry it once
              // with exactly the same session; persistent denials are returned to the caller.
              if (response.status === 401 && attempt === 0) {
                await new Promise(resolve => setTimeout(resolve, 1000));
                continue;
              }
              if (response.status !== 429 && response.status < 500) return {status: response.status};
              if (attempt === 2) return {status: response.status};
            } catch (e) {
              if (attempt === 2) return {status: 0};
            }
            await new Promise(resolve => setTimeout(resolve, 1000 * (attempt + 1)));
          }
        }))
        """;

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fetch(Page page, List<String> urls, Map<String, String> headers, boolean text) {
        // Origin, Referer and User-Agent are supplied by the browser, not spoofed by fetch.
        Map<String, String> tokenHeaders = headers.containsKey("x-spopactoken")
                ? Map.of("x-spopactoken", headers.get("x-spopactoken")) : Map.of();
        List<Map<String, Object>> results;
        try {
            results = (List<Map<String, Object>>) page.evaluate(FETCH,
                    Map.of("urls", urls, "headers", tokenHeaders, "text", text));
        } catch (com.microsoft.playwright.PlaywrightException ex) {
            // Do not propagate Playwright's call log, which may include signed URLs or credentials.
            throw new IllegalStateException("Richiesta media nel browser fallita o interrotta.");
        }
        for (var result : results) {
            int status = ((Number) result.get("status")).intValue();
            if (status == 401) throw new UnauthorizedMediaException();
            if (status == 403) throw new StreamManifestResolver.NonRetryableResolveException(
                    "Accesso negato a manifest, chiave o segmenti (HTTP " + status + "): verifica login e permessi.", null);
            if (status < 200 || status >= 300) throw new IllegalStateException(
                    status == 0 ? "Connessione media fallita nel browser (rete, TLS o CORS)."
                            : "Richiesta media fallita: HTTP " + status);
        }
        return results;
    }

    static String text(Page page, String url, Map<String, String> headers) {
        return (String) fetch(page, List.of(url), headers, true).get(0).get("body");
    }

    static List<byte[]> bytes(Page page, List<String> urls, Map<String, String> headers) {
        return fetch(page, urls, headers, false).stream()
                .map(result -> Base64.getDecoder().decode((String) result.get("body"))).toList();
    }
}
