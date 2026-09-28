package it.lagioiaproduction.core;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Punto unico di creazione di Playwright e del browser.
 * <p>
 * L'applicazione usa sempre il browser installato nel sistema (Microsoft Edge, canale "msedge"),
 * che si aggiorna da solo. Per restare compatibili con le versioni recenti di Edge va mantenuta
 * aggiornata la dipendenza Playwright nel pom.xml (Dependabot apre le PR di aggiornamento).
 * <p>
 * Login e download condividono lo stesso profilo Edge persistente, così la sessione Microsoft
 * (cookie, token, "Rimanere connessi") si comporta come in un normale browser.
 */
public final class PlaywrightBrowserFactory {
    public static final String BROWSER_CHANNEL = "msedge";
    public static final String BROWSER_DISPLAY_NAME = "Microsoft Edge";

    public static final Path APP_DATA_DIR = resolveAppDataDir();
    public static final Path PROFILE_DIR = APP_DATA_DIR.resolve("edge-profile");

    private PlaywrightBrowserFactory() {
    }

    public static Playwright createPlaywright() {
        // Usiamo Edge di sistema: evitiamo che Playwright scarichi i propri browser al primo avvio.
        return Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
    }

    public static BrowserContext launchPersistent(Playwright playwright, boolean headless, double slowMoMs) {
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setChannel(BROWSER_CHANNEL)
                .setHeadless(headless);
        if (slowMoMs > 0) {
            options.setSlowMo(slowMoMs);
        }

        try {
            Files.createDirectories(PROFILE_DIR);
            return playwright.chromium().launchPersistentContext(PROFILE_DIR, options);
        } catch (PlaywrightException ex) {
            throw new IllegalStateException(
                    "Impossibile avviare " + BROWSER_DISPLAY_NAME + ". Verifica che sia installato e aggiornato "
                            + "(edge://settings/help) e che non ci sia un'altra operazione dell'app in corso, poi riprova.",
                    ex
            );
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Impossibile creare il profilo browser in " + PROFILE_DIR, ex);
        }
    }

    public static BrowserContext launchPersistent(Playwright playwright, boolean headless) {
        return launchPersistent(playwright, headless, 0);
    }

    /** Il contesto persistente apre già una scheda: riusiamola invece di lasciarla vuota. */
    public static Page firstPage(BrowserContext context) {
        return context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
    }

    public static boolean isMicrosoftLoginUrl(String url) {
        if (url == null) {
            return false;
        }
        return url.contains("login.microsoftonline.com")
                || url.contains("login.microsoft.com")
                || url.contains("login.live.com")
                || url.contains("login.windows.net");
    }

    private static Path resolveAppDataDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Paths.get(localAppData, "TeamsStreamLectureDownloader");
        }

        return Paths.get(System.getProperty("user.home"), ".teams-stream-lecture-downloader");
    }
}
