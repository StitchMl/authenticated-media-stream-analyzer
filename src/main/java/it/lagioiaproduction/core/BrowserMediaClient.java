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
    async ({urls, headers, text}) => {
      const sleep = ms =>
        new Promise(resolve => setTimeout(resolve, ms));

      return await Promise.all(urls.map(async url => {
        for (let attempt = 0; attempt < 6; attempt++) {
          try {
            const controller = new AbortController();
            const timer = setTimeout(
              () => controller.abort(), 30000
            );

            let response, body;

            try {
              response = await fetch(url, {
                headers,
                credentials:
                  Object.keys(headers).length
                    ? 'same-origin'
                    : 'include',
                cache: 'no-store',
                signal: controller.signal
              });

              if (response.ok) {
                body = text
                  ? await response.text()
                  : new Uint8Array(await response.arrayBuffer());
              }
            } finally {
              clearTimeout(timer);
            }

            if (response.ok) {
              if (text) {
                return {status: response.status, body};
              }

              let binary = '';
              for (let i = 0; i < body.length; i += 8192) {
                binary += String.fromCharCode(
                  ...body.subarray(i, i + 8192)
                );
              }

              return {
                status: response.status,
                body: btoa(binary)
              };
            }

            const status = response.status;

            if (status !== 429 && status < 500 &&
                !(status === 401 && attempt === 0)) {
              return {status};
            }

            if (attempt === 5) {
              return {status};
            }

            // Retry-After può essere espresso
            // in secondi o come data HTTP.
            const retryAfter =
              response.headers.get('Retry-After');

            let delay = Math.min(
              60000,
              1000 * Math.pow(2, attempt)
            );

            if (retryAfter) {
              const seconds = Number(retryAfter);
              const parsed = Number.isFinite(seconds)
                ? seconds * 1000
                : Date.parse(retryAfter) - Date.now();

              if (Number.isFinite(parsed)) {
                delay = Math.max(delay, parsed);
              }
            }

            if (status === 429) {
              delay = Math.min(
                300000,
                Math.max(0, delay)
              );
            }

            await sleep(delay);

          } catch (e) {
            if (attempt === 5) {
              return {status: 0};
            }

            await sleep(
              Math.min(30000, 1000 * Math.pow(2, attempt))
            );
          }
        }
      }));
    }
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

            if (status < 200 || status >= 300) {
                int index = results.indexOf(result);

                String resourceUrl = urls.get(index);

                String resourceType;

                if (resourceUrl.contains("videomanifest")) {
                    resourceType = "Manifest DASH";
                } else if (resourceUrl.contains("key")) {
                    resourceType = "Possibile chiave DASH";
                } else {
                    resourceType = "Risorsa media/segmento";
                }

                throw new IllegalStateException(
                        "HTTP " + status +
                                " durante il download di: " +
                                resourceType +
                                " (elemento batch " + index + ")"
                );
            }
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
