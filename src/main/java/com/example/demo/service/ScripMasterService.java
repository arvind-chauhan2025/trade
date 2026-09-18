package com.example.demo.service;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.OptionContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
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
 * extracts NIFTY index option (OPTIDX) contracts traded on the NFO segment.
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
            .build();
    private final ReentrantLock lock = new ReentrantLock();

    private volatile List<OptionContract> cachedNiftyOptions;
    private volatile long cachedAtMillis;
    private static final long CACHE_TTL_MILLIS = 60L * 60 * 1000; // refresh at most hourly

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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getScripMasterUrl()))
                .timeout(Duration.ofMinutes(8))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Scrip Master download failed with HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to download Angel One Scrip Master: " + ex.getMessage(), ex);
        }
    }
}




