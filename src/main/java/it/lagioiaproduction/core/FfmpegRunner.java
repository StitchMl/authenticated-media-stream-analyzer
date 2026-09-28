package it.lagioiaproduction.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FfmpegRunner {
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("Duration:\\s*(\\d{2}:\\d{2}:\\d{2}\\.\\d{2})");

    private static final Pattern TIME_PATTERN =
            Pattern.compile("time=(\\d{2}:\\d{2}:\\d{2}\\.\\d{2})");

    private static final Pattern SPEED_PATTERN =
            Pattern.compile("speed=\\s*([0-9.]+)x");

    public record ProgressUpdate(double fraction, String detail) {
    }

    public void download(String manifestUrl, String requestHeaders, Path outputFile,
                         Consumer<ProgressUpdate> progressConsumer)
            throws IOException, InterruptedException {

        List<String> command = new ArrayList<>(List.of(
                "ffmpeg",
                "-y",
                "-nostdin",
                "-hide_banner",
                "-loglevel", "info",
                "-rw_timeout", "30000000"
        ));
        if (requestHeaders != null && !requestHeaders.isBlank()) {
            command.add("-headers");
            command.add(requestHeaders);
        }
        command.addAll(List.of(
                "-i", manifestUrl,
                "-c", "copy",
                outputFile.toAbsolutePath().toString()
        ));

        run(command, outputFile, progressConsumer);
    }

    public void muxLocal(Path video, Path audio, Path outputFile,
                         Consumer<ProgressUpdate> progressConsumer) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("ffmpeg", "-y", "-nostdin", "-hide_banner",
                "-i", video.toAbsolutePath().toString()));
        if (audio != null) command.addAll(List.of("-i", audio.toAbsolutePath().toString(),
                "-map", "0:v:0", "-map", "1:a:0"));
        command.addAll(List.of("-c", "copy", "-movflags", "+faststart", outputFile.toAbsolutePath().toString()));
        run(command, outputFile, progressConsumer);
    }

    private void run(List<String> command, Path outputFile, Consumer<ProgressUpdate> progressConsumer)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);

        pb.redirectErrorStream(true);
        Process process = pb.start();

        Double totalSeconds = null;
        String failureReason = null;
        List<String> ffmpegLog = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }

                String cleanLine = redactLog(line.trim());

                String detectedReason = diagnoseFailure(cleanLine);
                if (detectedReason != null) {
                    failureReason = detectedReason;
                }
                ffmpegLog.add(cleanLine);
                if (ffmpegLog.size() > 100) {
                    ffmpegLog.remove(0);
                }

                if (totalSeconds == null) {
                    Matcher durationMatcher = DURATION_PATTERN.matcher(line);
                    if (durationMatcher.find()) {
                        totalSeconds = parseTimestampToSeconds(durationMatcher.group(1));
                    }
                }

                Matcher timeMatcher = TIME_PATTERN.matcher(line);
                if (!timeMatcher.find()) {
                    continue;
                }

                double currentSeconds = parseTimestampToSeconds(timeMatcher.group(1));
                double fraction = totalSeconds != null && totalSeconds > 0
                        ? Math.min(1.0, Math.max(0.0, currentSeconds / totalSeconds))
                        : 0.0;

                Double speed = parseSpeed(line);
                String detail = buildDetail(currentSeconds, totalSeconds, speed);

                notifyProgress(progressConsumer, new ProgressUpdate(fraction, detail));
            }
        }

        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String fullLog = String.join(System.lineSeparator(), ffmpegLog);

            Path logFile = outputFile.resolveSibling(
                    outputFile.getFileName().toString() + ".ffmpeg.log"
            );

            java.nio.file.Files.writeString(
                    logFile,
                    fullLog,
                    StandardCharsets.UTF_8
            );

            throw new IllegalStateException(
                    (failureReason == null ? "Download FFmpeg fallito" : failureReason)
                            + " (exit code "
                            + exitCode
                            + ") - log: "
                            + logFile.toAbsolutePath()
            );
        }

        notifyProgress(progressConsumer, new ProgressUpdate(1.0, "Download completato."));
    }

    static String redactLog(String line) {
        return line.replaceAll("(?i)(https?://[^\\s?]+)\\?[^\\s]*", "$1?[redacted]")
                .replaceAll("(?i)((?:authorization|cookie|x-spopactoken):).*", "$1 [redacted]");
    }

    static String diagnoseFailure(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("401 unauthorized") || lower.contains("403 forbidden")) {
            return "Accesso negato al manifest o ai segmenti: verifica login e permessi";
        }
        if (lower.contains("certificate verify failed") || lower.contains("tls handshake failed")
                || lower.contains("ssl handshake failed")) {
            return "Connessione HTTPS/TLS fallita: verifica certificati, proxy e versione di FFmpeg";
        }
        if (lower.contains("protocol not found") || lower.contains("not on whitelist")) {
            return "Protocollo non supportato dalla build di FFmpeg: installa una build con HTTPS/TLS";
        }
        if (lower.contains("contentprotection") || lower.contains("decryption")
                || lower.contains("encrypted") || lower.contains("encryption")) {
            return "Flusso video cifrato non decifrabile da questa configurazione: usa il download ufficiale se disponibile";
        }
        return null;
    }

    private void notifyProgress(Consumer<ProgressUpdate> progressConsumer, ProgressUpdate update) {
        if (progressConsumer != null) {
            progressConsumer.accept(update);
        }
    }

    private Double parseSpeed(String line) {
        Matcher speedMatcher = SPEED_PATTERN.matcher(line);
        if (!speedMatcher.find()) {
            return null;
        }

        try {
            return Double.parseDouble(speedMatcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String buildDetail(double currentSeconds, Double totalSeconds, Double speed) {
        StringBuilder builder = new StringBuilder();
        builder.append(formatSeconds(currentSeconds));

        if (totalSeconds != null && totalSeconds > 0) {
            builder.append(" / ").append(formatSeconds(totalSeconds));
        }

        if (speed != null && speed > 0) {
            builder.append(" • ").append(String.format(java.util.Locale.US, "%.2fx", speed));
        }

        if (totalSeconds != null && totalSeconds > 0 && speed != null && speed > 0 && currentSeconds < totalSeconds) {
            double remainingSeconds = (totalSeconds - currentSeconds) / speed;
            builder.append(" • ETA ").append(formatSeconds(remainingSeconds));
        }

        return builder.toString();
    }

    private double parseTimestampToSeconds(String timestamp) {
        String[] hms = timestamp.split(":");
        if (hms.length != 3) {
            return 0.0;
        }

        double hours = Double.parseDouble(hms[0]);
        double minutes = Double.parseDouble(hms[1]);
        double seconds = Double.parseDouble(hms[2]);

        return hours * 3600.0 + minutes * 60.0 + seconds;
    }

    private String formatSeconds(double totalSeconds) {
        int seconds = Math.max(0, (int) Math.round(totalSeconds));
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int secs = seconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }
}