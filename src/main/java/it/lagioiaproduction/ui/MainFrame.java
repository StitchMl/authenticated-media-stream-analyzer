package it.lagioiaproduction.ui;

import it.lagioiaproduction.core.DownloadCoordinator;
import it.lagioiaproduction.model.DownloadSummary;
import it.lagioiaproduction.ui.components.ScrollableContentPanel;
import it.lagioiaproduction.ui.sections.HeaderSection;
import it.lagioiaproduction.ui.sections.InputSection;
import it.lagioiaproduction.ui.sections.ProgressSection;
import it.lagioiaproduction.ui.sections.StatsSection;
import it.lagioiaproduction.ui.theme.AppColors;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MainFrame extends JFrame {
    private final DownloadCoordinator coordinator;

    private final HeaderSection headerSection = new HeaderSection();
    private final InputSection inputSection = new InputSection();
    private final StatsSection statsSection = new StatsSection();
    private final ProgressSection progressSection = new ProgressSection();

    private boolean busy;

    public MainFrame(DownloadCoordinator coordinator) {
        super("Teams Stream Lecture Downloader");
        this.coordinator = coordinator;

        applyWindowIcon();
        buildUi();
        bindActions();
        refreshSessionState();
        updateCounters();
    }

    private void applyWindowIcon() {
        List<Image> icons = new ArrayList<>();

        addIconIfPresent(icons, "/icon/app-16.png");
        addIconIfPresent(icons, "/icon/app-32.png");
        addIconIfPresent(icons, "/icon/app-48.png");
        addIconIfPresent(icons, "/icon/app-64.png");
        addIconIfPresent(icons, "/icon/app-128.png");
        addIconIfPresent(icons, "/icon/app-256.png");

        if (!icons.isEmpty()) {
            setIconImages(icons);
            return;
        }

        URL fallbackUrl = getClass().getResource("/icon/app.png");
        if (fallbackUrl != null) {
            Image fallback = new ImageIcon(fallbackUrl).getImage();
            setIconImage(fallback);
        }
    }

    private void addIconIfPresent(List<Image> icons, String resourcePath) {
        URL url = getClass().getResource(resourcePath);
        if (url != null) {
            icons.add(new ImageIcon(url).getImage());
        }
    }

    private void buildUi() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 680));
        setSize(1180, 800);
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(18, 18));
        root.setBorder(new EmptyBorder(18, 18, 18, 18));
        root.setBackground(AppColors.APP_BG);

        root.add(headerSection, BorderLayout.NORTH);
        root.add(createScrollableMainArea(), BorderLayout.CENTER);

        setContentPane(root);
    }

    private JComponent createScrollableMainArea() {
        ScrollableContentPanel page = new ScrollableContentPanel();
        page.setLayout(new BorderLayout());
        page.setBackground(AppColors.APP_BG);
        page.setBorder(new EmptyBorder(0, 0, 8, 0));
        page.add(createMainPanel(), BorderLayout.NORTH);

        JScrollPane scrollPane = new JScrollPane(page);
        scrollPane.setBorder(null);
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setBackground(AppColors.APP_BG);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        scrollPane.getVerticalScrollBar().setBlockIncrement(120);

        return scrollPane;
    }

    private JComponent createMainPanel() {
        JPanel main = new JPanel(new BorderLayout(0, 18));
        main.setOpaque(false);
        main.add(inputSection, BorderLayout.NORTH);

        JPanel lower = new JPanel(new BorderLayout(0, 18));
        lower.setOpaque(false);
        lower.add(statsSection, BorderLayout.NORTH);
        lower.add(progressSection, BorderLayout.CENTER);

        main.add(lower, BorderLayout.CENTER);
        return main;
    }

    private void bindActions() {
        inputSection.addBrowseListener(e -> chooseOutputFolder());

        inputSection.addAddLinkListener(e -> {
            inputSection.addNewEmptyLink();
            updateCounters();
        });

        inputSection.addLoginListener(e ->
                runTask("Login", () -> coordinator.loginAndSaveState(this::updateStatus)));

        inputSection.addDownloadListener(e -> startDownloadBatch());

        inputSection.addUrlsChangedListener(this::updateCounters);
        inputSection.addParallelChangedListener(this::updateCounters);
    }

    private void chooseOutputFolder() {
        Path selected = inputSection.chooseDirectory(this);
        if (selected != null) {
            inputSection.setOutputDirectory(selected);
            inputSection.setFooterStatus("Cartella output aggiornata.");
        }
    }

    private void startDownloadBatch() {
        List<String> urls = inputSection.getUrls();
        if (urls.isEmpty()) {
            showError("Inserisci almeno un URL Stream valido.");
            return;
        }

        Path outputDir = inputSection.getOutputDirectory();
        int maxParallel = inputSection.getParallelDownloads();

        progressSection.prepareDownloads(urls.size());

        runTask("Download multiplo", () -> {
            DownloadSummary summary = coordinator.downloadMultiple(
                    urls,
                    outputDir,
                    maxParallel,
                    this::updateStatus,
                    progressSection::updateProgress
            );

            updateStatus(summary.toHumanMessage());

            if (summary.hasFailures()) {
                throw new IllegalStateException(summary.errorsAsMultilineString());
            }
        });
    }

    private void runTask(String taskName, ThrowingRunnable action) {
        setBusy(true);
        updateStatus("[" + taskName + "] avvio...");

        Thread worker = new Thread(() -> {
            try {
                action.run();
                updateStatus("[" + taskName + "] completato.");
            } catch (Exception ex) {
                updateStatus("[" + taskName + "] errore: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> showError(ex.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    refreshSessionState();
                    updateCounters();
                });
            }
        }, "worker-" + taskName.toLowerCase().replace(' ', '-'));

        worker.setDaemon(true);
        worker.start();
    }

    private void setBusy(boolean busy) {
        this.busy = busy;

        inputSection.setBusy(busy);
        inputSection.setActivityVisible(busy);

        setCursor(Cursor.getPredefinedCursor(busy ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));

        if (busy) {
            inputSection.setFooterStatus("Operazione in corso...");
        } else if (inputSection.getUrls().isEmpty()) {
            inputSection.setFooterStatus("Aggiungi almeno un link Stream per iniziare.");
        } else {
            inputSection.setFooterStatus("Pronto per il download.");
        }
    }

    private void refreshSessionState() {
        boolean hasSession = coordinator.hasAuthState();

        headerSection.setSessionSaved(hasSession);
        statsSection.setSessionSaved(hasSession);

        if (!busy) {
            inputSection.setFooterStatus(hasSession
                    ? "Pronto per il download."
                    : "Esegui il login Microsoft per salvare la sessione.");
        }
    }

    private void updateCounters() {
        int urlCount = inputSection.getUrls().size();
        int parallel = inputSection.getParallelDownloads();

        statsSection.setUrlCount(urlCount);
        statsSection.setParallelCount(parallel);

        inputSection.setDownloadButtonText(urlCount <= 1
                ? "Scarica video"
                : "Scarica " + urlCount + " video");

        inputSection.setDownloadEnabled(!busy && urlCount > 0);
    }

    private void updateStatus(String message) {
        SwingUtilities.invokeLater(() -> inputSection.setFooterStatus(message));
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Errore", JOptionPane.ERROR_MESSAGE);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}