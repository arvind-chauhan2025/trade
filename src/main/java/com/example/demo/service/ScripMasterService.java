package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.OptionContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Downloads and parses the Angel One Scrip Master (instrument dump) and
 * extracts NIFTY index option (OPTIDX) and future (FUTIDX) contracts traded
 * on the NFO segment.
 */
@Service
public class ScripMasterService {

    private static final Logger log = LoggerFactory.getLogger(ScripMasterService.class);
    private static final DateTimeFormatter EXPIRY_FORMAT = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("ddMMMyyyy")
            .toFormatter(Locale.ENGLISH);
    private static final DateTimeFormatter CACHE_FILE_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Path CACHE_DIR = Path.of("data");

    private final AngelOneProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMinutes(8))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final int DOWNLOAD_MAX_ATTEMPTS = 40;
    private static final int DOWNLOAD_MAX_NO_PROGRESS_ATTEMPTS = 6;
    private static final Duration DOWNLOAD_RETRY_DELAY = Duration.ofSeconds(2);
    private static final Duration DOWNLOAD_MAX_RETRY_DELAY = Duration.ofSeconds(15);
    private final ReentrantLock lock = new ReentrantLock();

    private volatile List<OptionContract> cachedNiftyOptions;
    private volatile long cachedAtMillis;
    private static final long CACHE_TTL_MILLIS = 60L * 60 * 1000; // refresh at most hourly

    private volatile List<OptionContract> cachedNiftyFutures;
    private volatile long cachedFuturesAtMillis;

    public ScripMasterService(AngelOneProperties properties) {
        this.properties = properties;
    }

    /** Holder for the nearest expiry and its corresponding list of NIFTY option contracts. */
    public record NiftyExpiryContracts(LocalDate expiry, List<OptionContract> contracts) {
    }

    /**
     * Downloads (or reuses the cached) scrip master, filters NIFTY index options,
     * and returns the contracts belonging to the nearest (soonest, non-expired) expiry.
     */
    public NiftyExpiryContracts getNiftyOptionsForNearestExpiry() {
        List<OptionContract> niftyOptions = getNiftyOptions();

        LocalDate today = LocalDate.now();
        LocalDate nearestExpiry = niftyOptions.stream()
                .map(OptionContract::expiry)
                .filter(expiry -> !expiry.isBefore(today))
                .min(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalStateException("No upcoming NIFTY option expiry found in Scrip Master"));

        List<OptionContract> contracts = niftyOptions.stream()
                .filter(c -> c.expiry().equals(nearestExpiry))
                .toList();

        log.info("Nearest NIFTY expiry resolved: {} with {} contracts", nearestExpiry, contracts.size());
        return new NiftyExpiryContracts(nearestExpiry, contracts);
    }

    /**
     * Returns the nearest (soonest, non-expired) NIFTY index future (FUTIDX) contract
     * from the Scrip Master.
     */
    public OptionContract getNearestNiftyFuture() {
        List<OptionContract> futures = getNiftyFutures();

        LocalDate today = LocalDate.now();
        return futures.stream()
                .filter(f -> !f.expiry().isBefore(today))
                .min((a, b) -> a.expiry().compareTo(b.expiry()))
                .orElseThrow(() -> new IllegalStateException("No upcoming NIFTY future expiry found in Scrip Master"));
    }

    /** Returns all NIFTY OPTIDX contracts from the Scrip Master, downloading/parsing if not already cached. */
    public List<OptionContract> getNiftyOptions() {
        if (cachedNiftyOptions != null && (System.currentTimeMillis() - cachedAtMillis) < CACHE_TTL_MILLIS) {
            return cachedNiftyOptions;
        }
        lock.lock();
        try {
            if (cachedNiftyOptions != null && (System.currentTimeMillis() - cachedAtMillis) < CACHE_TTL_MILLIS) {
                return cachedNiftyOptions;
            }
            cachedNiftyOptions = downloadAndParse();
            cachedAtMillis = System.currentTimeMillis();
            return cachedNiftyOptions;
        } finally {
            lock.unlock();
        }
    }

    /** Returns all NIFTY FUTIDX contracts from the Scrip Master, downloading/parsing if not already cached. */
    public List<OptionContract> getNiftyFutures() {
        if (cachedNiftyFutures != null && (System.currentTimeMillis() - cachedFuturesAtMillis) < CACHE_TTL_MILLIS) {
            return cachedNiftyFutures;
        }
        lock.lock();
        try {
            if (cachedNiftyFutures != null && (System.currentTimeMillis() - cachedFuturesAtMillis) < CACHE_TTL_MILLIS) {
                return cachedNiftyFutures;
            }
            cachedNiftyFutures = downloadAndParseFutures();
            cachedFuturesAtMillis = System.currentTimeMillis();
            return cachedNiftyFutures;
        } finally {
            lock.unlock();
        }
    }

    private List<OptionContract> downloadAndParse() {
        String body = readFromCacheOrDownload();

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Angel One Scrip Master: " + ex.getMessage(), ex);
        }

        if (!root.isArray()) {
            throw new IllegalStateException("Unexpected Scrip Master format: expected a JSON array");
        }

        List<OptionContract> options = new ArrayList<>();
        for (JsonNode node : root) {
            String exchSeg = node.path("exch_seg").asText("");
            String instrumentType = node.path("instrumenttype").asText("");
            String name = node.path("name").asText("");

            if (!"NFO".equalsIgnoreCase(exchSeg) || !"OPTIDX".equalsIgnoreCase(instrumentType) || !"NIFTY".equalsIgnoreCase(name)) {
                continue;
            }

            String symbol = node.path("symbol").asText("");
            String expiryRaw = node.path("expiry").asText("");
            if (expiryRaw.isBlank()) {
                continue;
            }

            LocalDate expiry;
            try {
                expiry = LocalDate.parse(expiryRaw, EXPIRY_FORMAT);
            } catch (Exception ex) {
                log.warn("Skipping contract {} with unparsable expiry '{}'", symbol, expiryRaw);
                continue;
            }

            // Strike is stored multiplied by 100 in the Scrip Master.
            double strike = node.path("strike").asDouble(0) / 100.0;

            String optionType;
            if (symbol.toUpperCase(Locale.ENGLISH).endsWith("CE")) {
                optionType = "CE";
            } else if (symbol.toUpperCase(Locale.ENGLISH).endsWith("PE")) {
                optionType = "PE";
            } else {
                continue;
            }

            int lotSize;
            try {
                lotSize = (int) Double.parseDouble(node.path("lotsize").asText("0"));
            } catch (NumberFormatException ex) {
                lotSize = 0;
            }

            options.add(new OptionContract(
                    node.path("token").asText(""),
                    symbol,
                    name,
                    exchSeg,
                    instrumentType,
                    expiry,
                    strike,
                    optionType,
                    lotSize
            ));
        }

        if (options.isEmpty()) {
            throw new IllegalStateException("No NIFTY OPTIDX contracts found in Scrip Master");
        }

        log.info("Parsed {} NIFTY OPTIDX contracts from Scrip Master", options.size());
        return options;
    }

    private List<OptionContract> downloadAndParseFutures() {
        String body = readFromCacheOrDownload();

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Angel One Scrip Master: " + ex.getMessage(), ex);
        }

        if (!root.isArray()) {
            throw new IllegalStateException("Unexpected Scrip Master format: expected a JSON array");
        }

        List<OptionContract> futures = new ArrayList<>();
        for (JsonNode node : root) {
            String exchSeg = node.path("exch_seg").asText("");
            String instrumentType = node.path("instrumenttype").asText("");
            String name = node.path("name").asText("");

            if (!"NFO".equalsIgnoreCase(exchSeg) || !"FUTIDX".equalsIgnoreCase(instrumentType) || !"NIFTY".equalsIgnoreCase(name)) {
                continue;
            }

            String symbol = node.path("symbol").asText("");
            String expiryRaw = node.path("expiry").asText("");
            if (expiryRaw.isBlank()) {
                continue;
            }

            LocalDate expiry;
            try {
                expiry = LocalDate.parse(expiryRaw, EXPIRY_FORMAT);
            } catch (Exception ex) {
                log.warn("Skipping future {} with unparsable expiry '{}'", symbol, expiryRaw);
                continue;
            }

            int lotSize;
            try {
                lotSize = (int) Double.parseDouble(node.path("lotsize").asText("0"));
            } catch (NumberFormatException ex) {
                lotSize = 0;
            }

            futures.add(new OptionContract(
                    node.path("token").asText(""),
                    symbol,
                    name,
                    exchSeg,
                    instrumentType,
                    expiry,
                    0.0,
                    "FUT",
                    lotSize
            ));
        }

        if (futures.isEmpty()) {
            throw new IllegalStateException("No NIFTY FUTIDX contracts found in Scrip Master");
        }

        log.info("Parsed {} NIFTY FUTIDX contracts from Scrip Master", futures.size());
        return futures;
    }

    /**
     * Returns today's cached Scrip Master response body from the local data folder,
     * downloading and persisting it first if no cache file exists yet for today.
     */
    private String readFromCacheOrDownload() {
        Path cacheFile = CACHE_DIR.resolve("scrip-master-" + LocalDate.now().format(CACHE_FILE_DATE_FORMAT) + ".json");

        if (Files.exists(cacheFile)) {
            try {
                log.info("Loading Scrip Master from local cache: {}", cacheFile.toAbsolutePath());
                return Files.readString(cacheFile, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                log.warn("Failed to read Scrip Master cache file {}, re-downloading: {}", cacheFile, ex.getMessage());
            }
        }

        String body = download();
        try {
            Files.createDirectories(CACHE_DIR);
            Files.writeString(cacheFile, body, StandardCharsets.UTF_8);
            log.info("Cached Scrip Master response to {}", cacheFile.toAbsolutePath());
        } catch (IOException ex) {
            log.warn("Failed to write Scrip Master cache file {}: {}", cacheFile, ex.getMessage());
        }
        return body;
    }

    private String download() {
        log.info("Downloading Angel One Scrip Master from {}", properties.getScripMasterUrl());

        URI uri = URI.create(properties.getScripMasterUrl());
        ByteArrayOutputStream accumulated = new ByteArrayOutputStream();
        boolean rangeRequestsSupported = true;
        Exception lastFailure = null;
        int consecutiveNoProgress = 0;
        long expectedTotalBytes = -1;
        String validator = null; // ETag (preferred) or Last-Modified, used as If-Range to detect a changed file mid-download

        for (int attempt = 1; attempt <= DOWNLOAD_MAX_ATTEMPTS; attempt++) {
            long bytesSoFar = accumulated.size();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMinutes(8))
                    .GET();
            boolean resuming = bytesSoFar > 0 && rangeRequestsSupported;
            if (resuming) {
                requestBuilder.header("Range", "bytes=" + bytesSoFar + "-");
                if (validator != null) {
                    // If the file changed since the first chunk, the server must ignore Range and resend
                    // the full body (200) instead of a stale partial chunk, so we restart cleanly below
                    // rather than silently splicing together bytes from two different file versions.
                    requestBuilder.header("If-Range", validator);
                }
            }

            try {
                HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                if (resuming && status == 200) {
                    // Server ignored the Range header (or the file changed, failing If-Range) and is
                    // sending the full body again; discard what we had and restart from scratch.
                    log.warn("Scrip Master resume request returned full HTTP 200 instead of 206 (file may have changed); restarting download from scratch");
                    rangeRequestsSupported = false;
                    accumulated.reset();
                    expectedTotalBytes = -1;
                    validator = null;
                } else if (resuming && status != 206) {
                    throw new IllegalStateException("Scrip Master resume request failed with HTTP " + status);
                } else if (!resuming && status != 200) {
                    throw new IllegalStateException("Scrip Master download failed with HTTP " + status);
                }

                if (!resuming || status == 200) {
                    expectedTotalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    validator = response.headers().firstValue("ETag")
                            .or(() -> response.headers().firstValue("Last-Modified"))
                            .orElse(null);
                }

                try (InputStream in = response.body()) {
                    in.transferTo(accumulated);
                }

                if (expectedTotalBytes >= 0 && accumulated.size() != expectedTotalBytes) {
                    // Connection closed "cleanly" from the client's point of view but short of the
                    // expected size: treat as a failed attempt and retry/resume rather than silently
                    // returning (and caching) a truncated file.
                    throw new IOException("Incomplete download: received " + accumulated.size()
                            + " of expected " + expectedTotalBytes + " bytes");
                }
                return accumulated.toString(StandardCharsets.UTF_8);
            } catch (IllegalStateException ex) {
                throw ex;
            } catch (Exception ex) {
                lastFailure = ex;
                long gained = accumulated.size() - bytesSoFar;
                consecutiveNoProgress = gained > 0 ? 0 : consecutiveNoProgress + 1;
                log.warn("Scrip Master download attempt {}/{} failed after {} bytes total (+{} this attempt): {}",
                        attempt, DOWNLOAD_MAX_ATTEMPTS, accumulated.size(), gained, ex.getMessage());

                if (consecutiveNoProgress >= DOWNLOAD_MAX_NO_PROGRESS_ATTEMPTS) {
                    throw new IllegalStateException("Failed to download Angel One Scrip Master: no progress after "
                            + consecutiveNoProgress + " consecutive attempts, got " + accumulated.size() + " bytes", ex);
                }
                if (attempt < DOWNLOAD_MAX_ATTEMPTS) {
                    try {
                        // Backoff scales with *consecutive no-progress* attempts, not the raw attempt
                        // count: an attempt that's still gaining bytes (progress toward the full file)
                        // retries almost immediately, while a truly stuck run (e.g. the server resetting
                        // the connection before any data arrives) backs off further each time.
                        long delayMillis = consecutiveNoProgress == 0
                                ? DOWNLOAD_RETRY_DELAY.toMillis()
                                : Math.min(DOWNLOAD_RETRY_DELAY.toMillis() * consecutiveNoProgress, DOWNLOAD_MAX_RETRY_DELAY.toMillis());
                        Thread.sleep(delayMillis);
                    } catch (InterruptedException interruptedEx) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Failed to download Angel One Scrip Master: interrupted during retry", interruptedEx);
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to download Angel One Scrip Master: " + lastFailure.getMessage(), lastFailure);
    }
}




