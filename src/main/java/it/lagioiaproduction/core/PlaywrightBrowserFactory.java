package it.lagioiaproduction.core;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import java.util.Map;

/**
 * Punto unico di creazione di Playwright e del browser.
 * <p>
 * L'applicazione usa sempre il browser installato nel sistema (Microsoft Edge, canale "msedge"),
 * che si aggiorna da solo. Per restare compatibili con le versioni recenti di Edge va mantenuta
 * aggiornata la dipendenza Playwright nel pom.xml (Dependabot apre le PR di aggiornamento).
 */
public final class PlaywrightBrowserFactory {
    public static final String BROWSER_CHANNEL = "msedge";
    public static final String BROWSER_DISPLAY_NAME = "Microsoft Edge";

    private PlaywrightBrowserFactory() {
    }

    public static Playwright createPlaywright() {
        // Usiamo Edge di sistema: evitiamo che Playwright scarichi i propri browser al primo avvio.
        return Playwright.create(new Playwright.CreateOptions()
                .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
    }

    public static Browser launch(Playwright playwright, boolean headless, double slowMoMs) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setChannel(BROWSER_CHANNEL)
                .setHeadless(headless);
        if (slowMoMs > 0) {
            options.setSlowMo(slowMoMs);
        }

        try {
            return playwright.chromium().launch(options);
        } catch (PlaywrightException ex) {
            throw new IllegalStateException(
                    "Impossibile avviare " + BROWSER_DISPLAY_NAME + ". Verifica che sia installato e aggiornato "
                            + "(edge://settings/help), poi riprova.",
                    ex
            );
        }
    }

    public static Browser launch(Playwright playwright, boolean headless) {
        return launch(playwright, headless, 0);
    }
}
