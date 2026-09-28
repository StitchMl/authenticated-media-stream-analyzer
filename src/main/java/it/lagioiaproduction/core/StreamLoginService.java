package it.lagioiaproduction.core;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.Cookie;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

public class StreamLoginService {
    private static final Path AUTH_STATE = PlaywrightBrowserFactory.APP_DATA_DIR
            .resolve("playwright").resolve(".auth").resolve("state.json");
    private static final long LOGIN_TIMEOUT_MS = 300_000;
    private static final double POLL_INTERVAL_MS = 1_000;

    // Cookie impostati da Microsoft Entra ID solo dopo un'autenticazione completata (password/MFA).
    private static final List<String> AUTH_COOKIES = List.of("ESTSAUTH", "ESTSAUTHPERSISTENT");

    public boolean hasAuthState() {
        return Files.exists(AUTH_STATE);
    }

    public Path getAuthStatePath() {
        return AUTH_STATE;
    }

    public void loginAndSaveState(Consumer<String> logger) throws Exception {
        Files.createDirectories(AUTH_STATE.getParent());
        // Una sessione precedente non valida non deve risultare "attiva" se il nuovo login fallisce.
        Files.deleteIfExists(AUTH_STATE);

        try (Playwright playwright = PlaywrightBrowserFactory.createPlaywright();
             BrowserContext context = PlaywrightBrowserFactory.launchPersistent(playwright, false, 150)) {

            Page page = PlaywrightBrowserFactory.firstPage(context);

            log(logger, "Apro " + PlaywrightBrowserFactory.BROWSER_DISPLAY_NAME + " per il login...");
            page.navigate("https://login.microsoftonline.com/");
            log(logger, "Completa login ed eventuale MFA nel browser (scegli \"Sì\" su \"Rimanere connessi?\").");

            waitUntilLoginCompleted(context, page);

            log(logger, "Login rilevato, salvo la sessione...");
            context.storageState(new BrowserContext.StorageStateOptions().setPath(AUTH_STATE));
            log(logger, "Sessione salvata nel profilo: " + PlaywrightBrowserFactory.PROFILE_DIR.toAbsolutePath());
        }
    }

    private void waitUntilLoginCompleted(BrowserContext context, Page page) {
        long deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            try {
                // Il login è completo solo quando Entra ID ha emesso il cookie di sessione
                // e il browser ha lasciato le pagine di login (es. dopo "Rimanere connessi?").
                if (hasAuthCookie(context) && !PlaywrightBrowserFactory.isMicrosoftLoginUrl(page.url())) {
                    return;
                }
                page.waitForTimeout(POLL_INTERVAL_MS);
            } catch (PlaywrightException ex) {
                if (page.isClosed()) {
                    throw new IllegalStateException("Finestra del browser chiusa prima di completare il login.", ex);
                }
                throw ex;
            }
        }

        throw new IllegalStateException("Login non completato entro " + (LOGIN_TIMEOUT_MS / 60_000) + " minuti.");
    }

    private boolean hasAuthCookie(BrowserContext context) {
        for (Cookie cookie : context.cookies("https://login.microsoftonline.com")) {
            if (AUTH_COOKIES.contains(cookie.name)) {
                return true;
            }
        }
        return false;
    }

    private void log(Consumer<String> logger, String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }
}
