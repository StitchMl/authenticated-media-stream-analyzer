package it.lagioiaproduction.core;

import it.lagioiaproduction.model.DownloadProgress;
import it.lagioiaproduction.model.DownloadRequest;
import it.lagioiaproduction.model.DownloadSummary;
import it.lagioiaproduction.model.ResolvedStream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

public class DownloadCoordinator {
    private static final int MAX_RESOLVE_PARALLEL = 1;

    // Pausa minima tra due resolve consecutivi, anche se il precedente è andato bene.
    private static final long RESOLVE_MIN_INTERVAL_MS = 8_000L;

    // Pausa extra se un resolve fallisce completamente dopo i retry.
    private static final long RESOLVE_FAILURE_COOLDOWN_MS = 25_000L;

    private final StreamLoginService loginService;
    private final StreamManifestResolver manifestResolver;
    private final FileNameGenerator fileNameGenerator;
    private final FfmpegRunner ffmpegRunner;
    private final Semaphore resolveSemaphore = new Semaphore(MAX_RESOLVE_PARALLEL, true);

    private final Object resolvePacingLock = new Object();
    private long lastResolveFinishedAt = 0L;

    public DownloadCoordinator(
            StreamLoginService loginService,
            StreamManifestResolver manifestResolver,
            FileNameGenerator fileNameGenerator,
            FfmpegRunner ffmpegRunner
    ) {
        this.loginService = loginService;
        this.manifestResolver = manifestResolver;
        this.fileNameGenerator = fileNameGenerator;
        this.ffmpegRunner = ffmpegRunner;
    }

    public boolean hasAuthState() {
        return loginService.hasAuthState();
    }

    public void loginAndSaveState(Consumer<String> statusConsumer) throws Exception {
        loginService.loginAndSaveState(statusConsumer);
    }

    public DownloadSummary downloadMultiple(
            List<String> urls,
            Path outputDir,
            int maxParallel,
            Consumer<String> statusConsumer,
            Consumer<DownloadProgress> progressConsumer
    ) throws Exception {
        if (!loginService.hasAuthState()) {
            throw new IllegalStateException("Sessione non trovata. Premi prima 'Login Microsoft'.");
        }

        List<String> distinctUrls = urls == null
                ? List.of()
                : urls.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();

        if (distinctUrls.isEmpty()) {
            throw new IllegalStateException("Nessun URL da scaricare.");
        }

        Files.createDirectories(outputDir);

        int total = distinctUrls.size();
        int poolSize = Math.max(1, Math.min(maxParallel, total));

        notifyStatus(statusConsumer, "Download totali: " + total);
        notifyStatus(statusConsumer, "Download paralleli ffmpeg: " + poolSize);
        notifyStatus(statusConsumer, "Risoluzione browser limitata a " + MAX_RESOLVE_PARALLEL + " thread.");
        notifyStatus(statusConsumer, "Intervallo minimo tra resolve: " + (RESOLVE_MIN_INTERVAL_MS / 1000) + "s.");

        for (int i = 0; i < total; i++) {
            int index = i + 1;
            notifyProgress(progressConsumer, new DownloadProgress(
                    index,
                    total,
                    "File " + index,
                    "In coda",
                    0.0,
                    true,
                    "In attesa di uno slot libero..."
            ));
        }

        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        List<Future<Void>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < total; i++) {
                int index = i + 1;
                String url = distinctUrls.get(i);

                DownloadRequest request = new DownloadRequest(url, outputDir, index, total);

                futures.add(executor.submit(() -> {
                    downloadSingle(request, statusConsumer, progressConsumer);
                    return null;
                }));
            }

            int success = 0;
            int failed = 0;
            List<String> errors = new ArrayList<>();

            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                    success++;
                } catch (ExecutionException ex) {
                    failed++;
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    errors.add("- File " + (i + 1) + ": " + rootCauseMessage(cause));
                }
            }

            return new DownloadSummary(total, success, failed, errors);
        } finally {
            executor.shutdownNow();
        }
    }

    public void downloadSingle(
            DownloadRequest request,
            Consumer<String> statusConsumer,
            Consumer<DownloadProgress> progressConsumer
    ) throws Exception {
        if (!loginService.hasAuthState()) {
            throw new IllegalStateException("Sessione non trovata. Premi prima 'Login Microsoft'.");
        }

        Files.createDirectories(request.outputDirectory());

        notifyProgress(progressConsumer, new DownloadProgress(
                request.index(),
                request.total(),
                "File " + request.index(),
                "In attesa di risoluzione",
                0.0,
                true,
                "Attendo lo slot browser per recuperare embed e manifest..."
        ));

        ResolvedStream resolved;
        boolean acquired = false;

        try {
            resolveSemaphore.acquire();
            acquired = true;

            waitBeforeNextResolve(request, statusConsumer, progressConsumer);

            notifyStatus(statusConsumer, "Risoluzione link " + request.index() + "/" + request.total() + "...");
            notifyProgress(progressConsumer, new DownloadProgress(
                    request.index(),
                    request.total(),
                    "File " + request.index(),
                    "Risoluzione link",
                    0.0,
                    true,
                    "Recupero embed e manifest DASH..."
            ));

            resolved = manifestResolver.resolveWithRetry(
                    request.streamUrl(),
                    loginService.getAuthStatePath(),
                    statusConsumer
            );

            markResolveFinished();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Download interrotto mentre attendeva la fase di risoluzione.", ex);
        } catch (Exception ex) {
            markResolveFinished();

            notifyProgress(progressConsumer, new DownloadProgress(
                    request.index(),
                    request.total(),
                    "File " + request.index(),
                    "Errore",
                    0.0,
                    false,
                    rootCauseMessage(ex)
            ));

            applyFailureCooldown(request, statusConsumer, progressConsumer);
            throw ex;
        } finally {
            if (acquired) {
                resolveSemaphore.release();
            }
        }

        String baseFileName = fileNameGenerator.buildBaseFileName(
                request.streamUrl(),
                resolved.pageTitle()
        );

        Path outputFile = fileNameGenerator.reserveUniquePath(
                request.outputDirectory(),
                baseFileName,
                ".mp4"
        );

        String displayName = outputFile.getFileName().toString();

        notifyStatus(statusConsumer, "Avvio download: " + displayName);
        notifyProgress(progressConsumer, new DownloadProgress(
                request.index(),
                request.total(),
                displayName,
                "Preparazione file",
                0.0,
                true,
                "Connessione a ffmpeg..."
        ));

        try {
            ffmpegRunner.download(resolved.manifestUrl(), outputFile, update ->
                    notifyProgress(progressConsumer, new DownloadProgress(
                            request.index(),
                            request.total(),
                            displayName,
                            "Download in corso",
                            update.fraction(),
                            false,
                            update.detail()
                    )));
        } catch (Exception ex) {
            Files.deleteIfExists(outputFile);

            notifyProgress(progressConsumer, new DownloadProgress(
                    request.index(),
                    request.total(),
                    displayName,
                    "Errore",
                    0.0,
                    false,
                    rootCauseMessage(ex)
            ));

            throw ex;
        }

        notifyProgress(progressConsumer, new DownloadProgress(
                request.index(),
                request.total(),
                displayName,
                "Completato",
                1.0,
                false,
                "File salvato in: " + outputFile.toAbsolutePath()
        ));

        notifyStatus(statusConsumer, "Completato: " + displayName);
    }

    private void waitBeforeNextResolve(
            DownloadRequest request,
            Consumer<String> statusConsumer,
            Consumer<DownloadProgress> progressConsumer
    ) throws InterruptedException {
        long waitMs;

        synchronized (resolvePacingLock) {
            long now = System.currentTimeMillis();
            long earliestNextResolve = lastResolveFinishedAt + RESOLVE_MIN_INTERVAL_MS;
            waitMs = Math.max(0L, earliestNextResolve - now);
        }

        if (waitMs <= 0) {
            return;
        }

        long waitSeconds = Math.max(1L, waitMs / 1000L);

        notifyStatus(statusConsumer, "Pausa tecnica prima del resolve successivo (" + waitSeconds + "s)...");
        notifyProgress(progressConsumer, new DownloadProgress(
                request.index(),
                request.total(),
                "File " + request.index(),
                "Attesa tecnica",
                0.0,
                true,
                "Attendo " + waitSeconds + "s per evitare throttling SharePoint/Stream..."
        ));

        Thread.sleep(waitMs);
    }

    private void applyFailureCooldown(
            DownloadRequest request,
            Consumer<String> statusConsumer,
            Consumer<DownloadProgress> progressConsumer
    ) {
        long waitSeconds = Math.max(1L, RESOLVE_FAILURE_COOLDOWN_MS / 1000L);

        notifyStatus(statusConsumer, "Cooldown dopo errore resolve (" + waitSeconds + "s)...");
        notifyProgress(progressConsumer, new DownloadProgress(
                request.index(),
                request.total(),
                "File " + request.index(),
                "Cooldown",
                0.0,
                true,
                "Pausa di sicurezza dopo errore per evitare ulteriori blocchi temporanei..."
        ));

        try {
            Thread.sleep(RESOLVE_FAILURE_COOLDOWN_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void markResolveFinished() {
        synchronized (resolvePacingLock) {
            lastResolveFinishedAt = System.currentTimeMillis();
        }
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }

    private void notifyStatus(Consumer<String> statusConsumer, String message) {
        if (statusConsumer != null) {
            statusConsumer.accept(message);
        }
    }

    private void notifyProgress(Consumer<DownloadProgress> progressConsumer, DownloadProgress progress) {
        if (progressConsumer != null) {
            progressConsumer.accept(progress);
        }
    }
}