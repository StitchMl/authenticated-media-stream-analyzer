package it.lagioiaproduction.core;

import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.RequestOptions;
import it.lagioiaproduction.model.ResolvedStream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamManifestResolver {
    private static final Pattern SRC_PATTERN =
            Pattern.compile("src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static final double MANIFEST_TIMEOUT_MS = 45_000;
    private static final double PAGE_READY_TIMEOUT_MS = 25_000;
    private static final double TEXTAREA_TIMEOUT_MS = 20_000;

    private static final int MAX_ATTEMPTS = 4;
    private static final long[] RETRY_DELAYS_MS = {5_000L, 15_000L, 30_000L, 45_000L};

    private static final double SSO_REDIRECT_TIMEOUT_MS = 30_000;

    private static final List<String> FORWARDED_HEADERS = List.of("x-spopactoken", "origin", "referer");

    private static final Path DIAGNOSTICS_DIR =
            PlaywrightBrowserFactory.APP_DATA_DIR.resolve("debug").resolve("resolve-failures");
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    public ResolvedStream resolveWithRetry(String streamUrl, Path authStatePath, Consumer<String> logger) throws Exception {
        Exception last = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                log(logger, "Risoluzione link: tentativo " + attempt + "/" + MAX_ATTEMPTS);
                return resolveOnce(streamUrl, authStatePath, logger);
            } catch (NonRetryableResolveException ex) {
                // Riprovare non serve: serve un intervento dell'utente (nuovo login o permessi).
                throw ex;
            } catch (Exception ex) {
                last = ex;
                log(logger, "Tentativo " + attempt + " fallito: " + rootCauseMessage(ex));

                if (attempt < MAX_ATTEMPTS) {
                    long delay = RETRY_DELAYS_MS[Math.min(attempt - 1, RETRY_DELAYS_MS.length - 1)];
                    log(logger, "Attendo " + (delay / 1000) + "s prima del nuovo tentativo...");
                    sleep(delay);
                }
            }
        }

        throw last;
    }

    private ResolvedStream resolveOnce(String streamUrl, Path authStatePath, Consumer<String> logger) {
        if (authStatePath == null || !Files.exists(authStatePath)) {
            throw new IllegalStateException("Sessione non trovata. Premi prima 'Login Microsoft'.");
        }

        try (Playwright playwright = PlaywrightBrowserFactory.createPlaywright();
             BrowserContext context = PlaywrightBrowserFactory.launchPersistent(playwright, true)) {

            Page streamPage = PlaywrightBrowserFactory.firstPage(context);
            try {
                log(logger, "Apro la pagina Stream...");
                streamPage.navigate(streamUrl);
                waitForSingleSignOn(streamPage);
                waitForBasePageReady(streamPage);
                ensureAccessGranted(streamPage);

                log(logger, "Cerco il codice di incorporamento...");
                openEmbedDialog(streamPage, logger);

                String iframeHtml = readEmbedTextarea(streamPage);
                String embedUrl = extractEmbedUrl(iframeHtml);
                log(logger, "Codice di incorporamento recuperato.");

                Page embedPage = context.newPage();

                log(logger, "Apro la pagina embed e intercetto il videomanifest...");
                Request manifestRequest = embedPage.waitForRequest(
                        request -> {
                            String url = request.url();
                            return url.contains("videomanifest?")
                                    && url.contains("format=dash");
                        },
                        new Page.WaitForRequestOptions().setTimeout(MANIFEST_TIMEOUT_MS),
                        () -> {
                            embedPage.navigate(embedUrl);
                            embedPage.waitForLoadState(LoadState.DOMCONTENTLOADED);
                        }
                );

                String manifestUrl = trimAfterFormatDash(manifestRequest.url());
                Map<String, String> authHeaders = extractAuthHeaders(manifestRequest);
                log(logger, "Manifest DASH intercettato.");

                ensureNotDownloadProtected(context, manifestUrl, authHeaders);

                return new ResolvedStream(
                        streamUrl,
                        safePageTitle(streamPage),
                        embedUrl,
                        manifestUrl,
                        toFfmpegHeaders(authHeaders)
                );
            } catch (NonRetryableResolveException ex) {
                throw ex;
            } catch (Exception ex) {
                dumpDiagnostics(streamPage, streamUrl, logger);
                throw ex;
            }
        }
    }

    private Map<String, String> extractAuthHeaders(Request manifestRequest) {
        Map<String, String> all = manifestRequest.allHeaders();
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : FORWARDED_HEADERS) {
            String value = all.get(name);
            if (value != null && !value.isBlank()) {
                headers.put(name, value);
            }
        }
        return headers;
    }

    private String toFfmpegHeaders(Map<String, String> headers) {
        StringBuilder sb = new StringBuilder();
        headers.forEach((name, value) -> sb.append(name).append(": ").append(value).append("\r\n"));
        return sb.toString();
    }

    private void ensureNotDownloadProtected(BrowserContext context, String manifestUrl, Map<String, String> headers) {
        // Le registrazioni "solo visualizzazione" hanno segmenti cifrati (DASH SEA): il proprietario
        // ha disattivato il download e l'app non lo aggira.
        APIResponse response = context.request().get(manifestUrl, requestOptionsWith(headers));
        try {
            if (response.ok() && response.text().contains("<ContentProtection")) {
                throw new NonRetryableResolveException(
                        "Registrazione protetta: il proprietario ha disattivato il download (solo visualizzazione).",
                        null);
            }
        } finally {
            response.dispose();
        }
    }

    private RequestOptions requestOptionsWith(Map<String, String> headers) {
        RequestOptions options = RequestOptions.create();
        headers.forEach(options::setHeader);
        return options;
    }

    private void waitForSingleSignOn(Page page) {
        // SharePoint passa da login.microsoftonline.com: con una sessione valida il redirect è automatico.
        try {
            page.waitForURL(
                    url -> !PlaywrightBrowserFactory.isMicrosoftLoginUrl(url),
                    new Page.WaitForURLOptions().setTimeout(SSO_REDIRECT_TIMEOUT_MS)
            );
        } catch (TimeoutError ex) {
            throw new NonRetryableResolveException(
                    "Sessione Microsoft non valida o scaduta: premi 'Login Microsoft' e completa l'accesso.", ex);
        }
    }

    private void ensureAccessGranted(Page page) {
        // Pagina SharePoint "Accesso necessario / Non hai accesso a questo elemento".
        boolean denied = page.locator("text=/Non hai accesso a questo elemento|You need access|You don't have access/i")
                .count() > 0;
        if (denied) {
            throw new NonRetryableResolveException(
                    "L'account con cui hai fatto il login non ha accesso a questa registrazione.", null);
        }
    }

    private void waitForBasePageReady(Page page) {
        page.waitForLoadState(LoadState.DOMCONTENTLOADED);
        page.locator("body").first().waitFor(new Locator.WaitForOptions().setTimeout(PAGE_READY_TIMEOUT_MS));

        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10_000));
        } catch (PlaywrightException ignored) {
            // Alcune pagine non arrivano pulitamente a NETWORKIDLE.
        }
    }

    private void openEmbedDialog(Page page, Consumer<String> logger) {
        clickFirstWithRetry(
                page,
                logger,
                "Condividi",
                "button:has-text(\"Condividi\")",
                "button:has-text(\"Share\")",
                "button[aria-label*='Condividi']",
                "button[aria-label*='Share']",
                "[title='Condividi']",
                "[title='Share']",
                "text=Condividi",
                "text=Share"
        );

        clickFirstWithRetry(
                page,
                logger,
                "Codice di incorporamento",
                "text=Codice di incorporamento",
                "text=Embed code",
                "text=Incorpora",
                "text=Embed"
        );
    }

    private void clickFirstWithRetry(Page page, Consumer<String> logger, String actionLabel, String... selectors) {
        double[] timeouts = {3_000, 6_000, 10_000};
        IllegalStateException last = null;

        for (int attempt = 1; attempt <= timeouts.length; attempt++) {
            try {
                clickFirst(page, timeouts[attempt - 1], selectors);
                return;
            } catch (IllegalStateException ex) {
                last = ex;
                log(logger,
                        actionLabel + " non trovato al tentativo " + attempt + "/" + timeouts.length +
                                " - url=" + safePageUrl(page) +
                                " - titolo=" + safePageTitle(page));

                if (attempt < timeouts.length) {
                    sleep(2_500);
                }
            }
        }

        throw last;
    }

    private void clickFirst(Page page, double timeoutMs, String... selectors) {
        PlaywrightException last = null;

        for (String selector : selectors) {
            try {
                Locator loc = page.locator(selector).first();
                loc.waitFor(new Locator.WaitForOptions().setTimeout(timeoutMs));
                loc.click(new Locator.ClickOptions().setTimeout(timeoutMs));
                return;
            } catch (PlaywrightException e) {
                last = e;
            }
        }

        throw new IllegalStateException(
                "Nessun selettore ha funzionato: " + Arrays.toString(selectors),
                last
        );
    }

    private String readEmbedTextarea(Page page) {
        Locator textarea = page.locator("textarea").first();
        textarea.waitFor(new Locator.WaitForOptions().setTimeout(TEXTAREA_TIMEOUT_MS));

        String value = textarea.inputValue();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Textarea embed non trovata o vuota.");
        }
        return value;
    }

    private String extractEmbedUrl(String iframeHtml) {
        Matcher matcher = SRC_PATTERN.matcher(iframeHtml);
        if (!matcher.find()) {
            throw new IllegalStateException("Impossibile estrarre src dall'iframe.");
        }
        return matcher.group(1).replace("&amp;", "&");
    }

    private String trimAfterFormatDash(String url) {
        String marker = "format=dash";
        int index = url.indexOf(marker);
        if (index == -1) {
            return url;
        }
        return url.substring(0, index + marker.length());
    }

    private void dumpDiagnostics(Page page, String streamUrl, Consumer<String> logger) {
        if (page == null) {
            return;
        }

        try {
            Files.createDirectories(DIAGNOSTICS_DIR);

            String stamp = LocalDateTime.now().format(TS_FORMAT);
            String baseName = sanitizeFileName(stamp + "-" + Integer.toHexString(streamUrl.hashCode()));

            Path screenshotPath = DIAGNOSTICS_DIR.resolve(baseName + ".png");
            Path htmlPath = DIAGNOSTICS_DIR.resolve(baseName + ".html");

            Files.writeString(htmlPath, page.content(), StandardCharsets.UTF_8);

            try {
                page.screenshot(new Page.ScreenshotOptions().setPath(screenshotPath));
            } catch (PlaywrightException screenshotEx) {
                log(logger, "Screenshot non disponibile: " + firstLine(screenshotEx.getMessage()));
            }

            log(logger, "Diagnostica salvata:");
            log(logger, " - Pagina attuale: " + safePageUrl(page));
            log(logger, " - Titolo pagina: " + safePageTitle(page));
            log(logger, " - Screenshot: " + screenshotPath.toAbsolutePath());
            log(logger, " - HTML: " + htmlPath.toAbsolutePath());
        } catch (Exception diagEx) {
            log(logger, "Impossibile salvare la diagnostica: " + diagEx.getMessage());
        }
    }

    private String safePageUrl(Page page) {
        try {
            String url = page.url();
            return url == null || url.isBlank() ? "<url non disponibile>" : url;
        } catch (Exception ex) {
            return "<url non disponibile>";
        }
    }

    private String safePageTitle(Page page) {
        try {
            String title = page.title();
            return title == null || title.isBlank() ? "<titolo non disponibile>" : title;
        } catch (Exception ex) {
            return "<titolo non disponibile>";
        }
    }

    private String sanitizeFileName(String input) {
        return input.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? firstLine(current.getMessage()) : current.toString();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int newline = message.indexOf('\n');
        return newline == -1 ? message : message.substring(0, newline);
    }

    static final class NonRetryableResolveException extends IllegalStateException {
        NonRetryableResolveException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Attesa interrotta durante il retry della risoluzione.", ex);
        }
    }

    private void log(Consumer<String> logger, String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }
}