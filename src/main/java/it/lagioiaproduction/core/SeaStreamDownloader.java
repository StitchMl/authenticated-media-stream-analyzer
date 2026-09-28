package it.lagioiaproduction.core;

import com.microsoft.playwright.*;
import it.lagioiaproduction.model.ResolvedStream;
import java.net.URI;
import java.nio.file.*;
import java.io.OutputStream;
import java.util.*;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.w3c.dom.*;

/** DASH SEA full-segment AES-CBC, with keys obtained through the authorized browser session. */
public final class SeaStreamDownloader {
    record Track(String type, String initialization, List<String> segments, String keyUri, byte[] iv, String layout) { }

    public void download(ResolvedStream resolved, Path output, FfmpegRunner ffmpeg,
                         Consumer<FfmpegRunner.ProgressUpdate> progress) throws Exception {
        Path temporary = Files.createTempDirectory(output.toAbsolutePath().getParent(), ".sea-");
        List<Path> tracks = new ArrayList<>();
        try {
            try (Playwright playwright = PlaywrightBrowserFactory.createPlaywright();
                 BrowserContext context = PlaywrightBrowserFactory.launchPersistent(playwright, true)) {
                Page page = PlaywrightBrowserFactory.firstPage(context);
                // Obtain fresh signed URLs and session token after any wait for the browser slot.
                Response response = openManifest(page, resolved.embedUrl());
                Map<String, String> headers = response.request().allHeaders();
                List<Track> plan = parse(response.text(), URI.create(response.url()));
                int refreshes = 0;
                int total = plan.stream().mapToInt(t -> t.segments.size()).sum();
                int done = 0;
                for (Track track : plan) {
                    Path file = temporary.resolve(track.type + ".mp4");
                    tracks.add(file);
                    byte[] key = BrowserMediaClient.bytes(page, List.of(track.keyUri), headers).get(0);
                    try {
                        if (key.length != 16) throw new IllegalStateException("Chiave DASH SEA non valida: attesi 16 byte.");
                        try (OutputStream stream = Files.newOutputStream(file)) {
                            // SharePoint may encrypt initialization too, unlike standard DASH SEA.
                            byte[] initialization = BrowserMediaClient.bytes(page, List.of(track.initialization), Map.of()).get(0);
                            stream.write(decodeInitialization(initialization, key, track.iv));
                            for (int start = 0; start < track.segments.size(); start += 4) {
                                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Download interrotto.");
                                List<String> batch = track.segments.subList(start, Math.min(start + 4, track.segments.size()));
                                List<byte[]> segmentData;
                                try {
                                    segmentData = BrowserMediaClient.bytes(page, batch, Map.of());
                                } catch (BrowserMediaClient.UnauthorizedMediaException failure) {
                                    if (++refreshes > 10) throw failure;
                                    // Refresh signed URLs and session headers through the same authenticated player.
                                    // The server must authorize the new manifest, key and segment requests.
                                    Response fresh = openManifest(page, resolved.embedUrl());
                                    headers = fresh.request().allHeaders();
                                    Track previous = track;
                                    Track updated = parse(fresh.text(), URI.create(fresh.url())).stream()
                                            .filter(t -> t.type.equals(previous.type)).findFirst()
                                            .orElseThrow(() -> unsupported("traccia cambiata durante il rinnovo"));
                                    if (!updated.layout.equals(previous.layout))
                                        throw unsupported("timeline o rappresentazione cambiate durante il rinnovo");
                                    Arrays.fill(key, (byte) 0);
                                    key = BrowserMediaClient.bytes(page, List.of(updated.keyUri), headers).get(0);
                                    if (key.length != 16) throw new IllegalStateException("Chiave DASH SEA non valida.");
                                    track = updated;
                                    batch = track.segments.subList(start, Math.min(start + 4, track.segments.size()));
                                    segmentData = BrowserMediaClient.bytes(page, batch, Map.of());
                                }
                                for (byte[] segment : segmentData) {
                                    stream.write(decrypt(segment, key, track.iv));
                                    done++;
                                }
                                if (progress != null) progress.accept(new FfmpegRunner.ProgressUpdate(
                                        0.95 * done / total, "Scaricamento e decifratura: " + done + " / " + total + " segmenti"));
                            }
                        }
                    } finally {
                        Arrays.fill(key, (byte) 0);
                    }
                }
            }
            Path video = temporary.resolve("video.mp4");
            Path audio = temporary.resolve("audio.mp4");
            if (progress != null) progress.accept(new FfmpegRunner.ProgressUpdate(0.95, "Unione audio e video..."));
            ffmpeg.muxLocal(video, Files.exists(audio) ? audio : null, output,
                    update -> { if (progress != null) progress.accept(new FfmpegRunner.ProgressUpdate(
                            0.95 + 0.05 * update.fraction(), update.detail())); });
        } finally {
            // Delete only the exact files created by this operation, without recursive shell deletion.
            for (Path file : tracks) Files.deleteIfExists(file);
            Files.deleteIfExists(temporary);
        }
    }

    private static Response openManifest(Page page, String embedUrl) {
        Response response = page.waitForResponse(r -> r.url().contains("videomanifest?")
                        && r.url().contains("format=dash"),
                new Page.WaitForResponseOptions().setTimeout(45_000), () -> page.navigate(embedUrl,
                        new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(45_000)));
        if (!response.ok()) throw new IllegalStateException("Manifest DASH: HTTP " + response.status());
        return response;
    }

    static byte[] decodeInitialization(byte[] data, byte[] key, byte[] iv) throws Exception {
        if (validInitialization(data)) return data;
        byte[] plain = decrypt(data, key, iv);
        if (!validInitialization(plain)) throw new IllegalStateException("Intestazione MP4 non valida dopo la decifratura.");
        return plain;
    }

    private static boolean validInitialization(byte[] data) {
        boolean ftyp = false;
        boolean moov = false;
        int offset = 0;
        while (offset + 8 <= data.length) {
            long length = Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(data, offset, 4).getInt());
            int header = 8;
            if (length == 1) {
                if (offset + 16 > data.length) return false;
                length = java.nio.ByteBuffer.wrap(data, offset + 8, 8).getLong();
                header = 16;
            } else if (length == 0) length = data.length - offset;
            if (length < header || length > data.length - offset) return false;
            String type = new String(data, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            ftyp |= "ftyp".equals(type);
            moov |= "moov".equals(type);
            offset += (int) length;
        }
        return offset == data.length && ftyp && moov;
    }

    static byte[] decrypt(byte[] segment, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        try {
            return cipher.doFinal(segment);
        } catch (javax.crypto.BadPaddingException | javax.crypto.IllegalBlockSizeException ex) {
            throw new IllegalStateException("Decifratura DASH SEA fallita: chiave, IV o segmento non validi.");
        }
    }

    static List<Track> parse(String xml, URI manifestUri) {
        DashManifestValidator.validate(xml);
        Document document = DashManifestValidator.parse(xml);
        Element mpd = document.getDocumentElement();
        if (!"static".equals(mpd.getAttribute("type")) || document.getElementsByTagNameNS("*", "Period").getLength() != 1)
            throw unsupported("sono supportate registrazioni statiche con un solo Period");
        Element period = (Element) document.getElementsByTagNameNS("*", "Period").item(0);
        URI base = base(base(manifestUri, mpd), period);
        List<Track> result = new ArrayList<>();
        Set<String> types = new HashSet<>();
        for (Element adaptation : children(period, "AdaptationSet")) {
            String type = adaptation.getAttribute("contentType");
            if (type.isBlank()) type = adaptation.getAttribute("mimeType").split("/")[0];
            if (!List.of("audio", "video").contains(type)) continue;
            if (!types.add(type)) throw unsupported("più tracce dello stesso tipo");
            Element representation = children(adaptation, "Representation").stream()
                    .max(Comparator.comparingLong(e -> number(e, "bandwidth", 0))).orElseThrow(() -> unsupported("Representation assente"));
            URI trackBase = base(base(base, adaptation), representation);
            Element protection = child(representation, "ContentProtection");
            if (protection == null) protection = child(adaptation, "ContentProtection");
            if (protection == null) throw unsupported("tracce miste cifrate e in chiaro");
            Element crypto = (Element) protection.getElementsByTagNameNS("urn:mpeg:dash:schema:sea:2012", "CryptoPeriod").item(0);
            byte[] iv = HexFormat.of().parseHex(crypto.getAttribute("IV").substring(2));
            String keyUri = http(trackBase.resolve(crypto.getAttribute("keyUriTemplate")));
            Element template = child(representation, "SegmentTemplate");
            if (template == null) template = child(adaptation, "SegmentTemplate");
            if (template == null || template.getAttribute("media").isBlank() || template.getAttribute("initialization").isBlank())
                throw unsupported("SegmentTemplate assente o incompleto");
            Element timeline = child(template, "SegmentTimeline");
            if (timeline == null) throw unsupported("SegmentTimeline assente");
            List<String> segments = new ArrayList<>();
            StringBuilder layout = new StringBuilder(representation.getAttribute("id")).append(':');
            long time = 0;
            long sequence = number(template, "startNumber", 1);
            for (Element entry : children(timeline, "S")) {
                time = number(entry, "t", time);
                long duration = number(entry, "d", 0);
                long repeat = number(entry, "r", 0);
                if (duration <= 0 || repeat < 0 || repeat > 100000) throw unsupported("timeline non supportata");
                for (long i = 0; i <= repeat; i++) {
                    if (segments.size() >= 100000) throw unsupported("troppi segmenti");
                    segments.add(http(trackBase.resolve(expand(template.getAttribute("media"), representation, time, sequence))));
                    layout.append(time).append('/').append(duration).append(';');
                    time = Math.addExact(time, duration);
                    sequence++;
                }
            }
            if (segments.isEmpty()) throw unsupported("timeline vuota");
            String initialization = http(trackBase.resolve(expand(template.getAttribute("initialization"), representation, 0, 0)));
            result.add(new Track(type, initialization, List.copyOf(segments), keyUri, iv, layout.toString()));
        }
        if (!types.contains("video")) throw unsupported("traccia video assente");
        return result;
    }

    private static String expand(String template, Element representation, long time, long number) {
        String expanded = template.replace("$$", "\u0001").replace("$RepresentationID$", representation.getAttribute("id"))
                .replace("$Bandwidth$", representation.getAttribute("bandwidth"))
                .replace("$Time$", Long.toString(time)).replace("$Number$", Long.toString(number));
        if (expanded.contains("$")) throw unsupported("placeholder SegmentTemplate non supportato");
        return expanded.replace("\u0001", "$");
    }

    private static URI base(URI parent, Element element) {
        Element url = child(element, "BaseURL");
        return url == null ? parent : parent.resolve(url.getTextContent().trim());
    }

    private static String http(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
            throw unsupported("URL media o chiave non HTTPS");
        return uri.toString();
    }

    private static long number(Element element, String name, long fallback) {
        return element.hasAttribute(name) ? Long.parseLong(element.getAttribute(name)) : fallback;
    }

    private static Element child(Element parent, String name) {
        return children(parent, name).stream().findFirst().orElse(null);
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> list = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
            if (node instanceof Element element && name.equals(element.getLocalName())) list.add(element);
        return list;
    }

    private static IllegalStateException unsupported(String detail) {
        return new StreamManifestResolver.NonRetryableResolveException("DASH SEA: " + detail + ".", null);
    }
}
