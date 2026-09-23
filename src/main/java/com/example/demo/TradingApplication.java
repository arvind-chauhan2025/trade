package com.example.demo;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.OptionContract;
import com.example.demo.dto.TickSnapshot;
import com.example.demo.service.AngelOneAuthService;
import com.example.demo.service.AngelOneMarketDataService;
import com.example.demo.service.NiftyOptionSelector;
import com.example.demo.service.NiftySpotPriceService;
import com.example.demo.service.PremiumReferenceService;
import com.example.demo.service.ScripMasterService;
import com.example.demo.service.FixedItmSelectionService;
import com.example.demo.service.GreeksCacheService;
import com.example.demo.service.TickPersistenceService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Orchestrates Step 1 of the NIFTY live options trading analytics backend:
 * fetch the NIFTY spot price, resolve the nearest expiry and ATM CE contract
 * from the Scrip Master, log in to Angel One, and start streaming live LTP
 * ticks for both instruments over SmartStream.
 * <p>
 * The pipeline is scheduled to start automatically at market open (9:15 AM
 * IST) and stop at market close (3:30 PM IST) on trading weekdays. Every
 * trading day therefore starts from that morning's own spot — ATM/ITM
 * selections are never restored/pinned from a previous day. On top of that,
 * {@link #reselectAtmItmIfWindowElapsed()} re-runs the same ATM/ITM selection
 * every {@code angelone.atm-itm-reselect-minutes} (default 30) minutes during
 * the day using the latest live spot, so strikes track the market intraday
 * while staying fixed for the rest of each window.
 */
@Component
public class TradingApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TradingApplication.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final NiftySpotPriceService niftySpotPriceService;
    private final ScripMasterService scripMasterService;
    private final NiftyOptionSelector niftyOptionSelector;
    private final AngelOneAuthService angelOneAuthService;
    private final AngelOneMarketDataService angelOneMarketDataService;
    private final AngelOneProperties properties;
    private final FixedItmSelectionService fixedItmSelectionService;
    private final GreeksCacheService greeksCacheService;
    private final PremiumReferenceService premiumReferenceService;

    @Value("${trading.run-on-startup:false}")
    private boolean OnStartup;

    private final AtomicBoolean pipelineActive = new AtomicBoolean(false);
    private volatile LocalDate trackedExpiry;
    private volatile Map<String, OptionContract> trackedContracts = Map.of();
    /** Contracts for the currently tracked expiry only, cached at pipeline start so each 30-minute
     * re-selection doesn't need to re-download/re-filter the full Scrip Master. */
    private volatile List<OptionContract> trackedExpiryContracts = List.of();
    /** When the ATM/ITM strikes were last (re-)selected; drives the 30-minute rolling window. */
    private volatile Instant lastAtmItmSelectionAt;

    public TradingApplication(NiftySpotPriceService niftySpotPriceService,
                               ScripMasterService scripMasterService,
                               NiftyOptionSelector niftyOptionSelector,
                               AngelOneAuthService angelOneAuthService,
                               AngelOneMarketDataService angelOneMarketDataService,
                               AngelOneProperties properties,
                               FixedItmSelectionService fixedItmSelectionService,
                               GreeksCacheService greeksCacheService,
                               PremiumReferenceService premiumReferenceService) {
        this.niftySpotPriceService = niftySpotPriceService;
        this.scripMasterService = scripMasterService;
        this.niftyOptionSelector = niftyOptionSelector;
        this.angelOneAuthService = angelOneAuthService;
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.properties = properties;
        this.fixedItmSelectionService = fixedItmSelectionService;
        this.greeksCacheService = greeksCacheService;
        this.premiumReferenceService = premiumReferenceService;
    }

    /**
     * When {@code trading.run-on-startup=true}, invokes {@link #startPipeline()} once
     * immediately at application boot — useful for testing outside the 9:15 AM–3:30 PM
     * IST schedule. No-op (default) otherwise.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (OnStartup) {
            log.info("trading.run-on-startup=true; starting pipeline immediately for testing");
            startPipeline();
        }
    }

    /** Starts the NIFTY streaming pipeline every trading weekday at 9:15 AM IST (market open). Always
     * (re)computes ATM/FIXED ITM CE/PE from that morning's own spot price — never restored from a
     * previous day — so the "morning snapshot" baseline is fresh every trading day. */
    //@Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    @Scheduled(cron = "0 05 21 * * MON-FRI", zone = "Asia/Kolkata")
    public void startPipeline() {
        try {
            double spot = niftySpotPriceService.getNiftySpotPrice().ltp();
            log.info("NIFTY spot price: {}", spot);

            List<OptionContract> allContracts = scripMasterService.getNiftyOptions();
            FixedItmSelectionService.Selection fixed = fixedItmSelectionService.select(
                    allContracts, spot, LocalDate.now(IST));
            List<OptionContract> expiryContracts = allContracts.stream()
                    .filter(c -> c.expiry().equals(fixed.expiry())).toList();
            log.info("Selected NIFTY expiry: {}", fixed.expiry());
            log.info("FIXED ITM CE: symbol={} token={} strike={} expiry={}",
                    fixed.ce().symbol(), fixed.ce().token(), fixed.ce().strike(), fixed.expiry());
            log.info("FIXED ITM PE: symbol={} token={} strike={} expiry={}",
                    fixed.pe().symbol(), fixed.pe().token(), fixed.pe().strike(), fixed.expiry());

            OptionContract atmCe = niftyOptionSelector.selectAtmCe(
                    spot, expiryContracts, properties.getStrikeStep());
            log.info("ATM CE selected: symbol={} token={} strike={} expiry={}",
                    atmCe.symbol(), atmCe.token(), atmCe.strike(), atmCe.expiry());

            OptionContract atmPe = niftyOptionSelector.selectAtmPe(
                    spot, expiryContracts, properties.getStrikeStep());
            log.info("ATM PE selected: symbol={} token={} strike={} expiry={}",
                    atmPe.symbol(), atmPe.token(), atmPe.strike(), atmPe.expiry());

            OptionContract niftyFut = scripMasterService.getNearestNiftyFuture();
            log.info("NIFTY FUT selected: symbol={} token={} expiry={}",
                    niftyFut.symbol(), niftyFut.token(), niftyFut.expiry());

            String jwtToken = angelOneAuthService.getJwtToken();
            String feedToken = angelOneAuthService.getFeedToken();

            trackedExpiry = fixed.expiry();
            trackedExpiryContracts = expiryContracts;
            trackedContracts = Map.of(
                    "ATM CE", atmCe, "ATM PE", atmPe,
                    "FIXED ITM CE", fixed.ce(), "FIXED ITM PE", fixed.pe());
            lastAtmItmSelectionAt = Instant.now();

            try {
                greeksCacheService.refresh(trackedExpiry, trackedContracts);
            } catch (Exception ex) {
                log.warn("Failed to fetch Option Greeks; Delta will be unavailable for this session", ex);
            }

            angelOneMarketDataService.start(jwtToken, feedToken, atmCe, atmPe, fixed.ce(), fixed.pe(), niftyFut);
            pipelineActive.set(true);
            log.info("SmartStream subscription initiated for NIFTY, ATM CE/PE, fixed ITM CE/PE and NIFTY FUT");
        } catch (Exception ex) {
            log.error("Failed to bootstrap NIFTY trading pipeline", ex);
        }
    }

    /** Refreshes the cached Option Greeks/Delta every 30 seconds while the pipeline is active. */
    @Scheduled(fixedRate = 30_000)
    public void refreshGreeks() {
        if (!pipelineActive.get()) {
            return;
        }
        LocalDate expiry = trackedExpiry;
        Map<String, OptionContract> contracts = trackedContracts;
        if (expiry == null || contracts.isEmpty()) {
            return;
        }
        try {
            greeksCacheService.refresh(expiry, contracts);
        } catch (Exception ex) {
            log.warn("Failed to refresh Option Greeks", ex);
        }
    }

    /**
     * Checked every minute; once {@code angelone.atm-itm-reselect-minutes} (default 30) has elapsed
     * since the last ATM/ITM selection, re-runs ATM CE/PE and FIXED ITM CE/PE selection against the
     * latest live spot price. Strikes are held fixed for the rest of the current window and only change
     * here, at the window boundary — never on every tick. If any leg's strike actually changed, the
     * SmartStream subscription is atomically swapped to the new tokens, Greeks are refreshed for the new
     * contracts, and the premium reference's rolling baseline is invalidated (via
     * {@link PremiumReferenceService#resetRollingReference()}) so a stale strike's captured premium (e.g.
     * ATM 23400) is never compared against a different, now-current strike's premium (e.g. ATM 23550).
     */
    @Scheduled(fixedRate = 60_000)
    public void reselectAtmItmIfWindowElapsed() {
        if (!pipelineActive.get()) {
            return;
        }
        Instant last = lastAtmItmSelectionAt;
        Duration window = Duration.ofMinutes(Math.max(1, properties.getAtmItmReselectMinutes()));
        if (last == null || Duration.between(last, Instant.now()).compareTo(window) < 0) {
            return;
        }

        List<OptionContract> expiryContracts = trackedExpiryContracts;
        LocalDate expiry = trackedExpiry;
        if (expiryContracts.isEmpty() || expiry == null) {
            return;
        }

        try {
            double spot = currentSpotForReselection();

            OptionContract newAtmCe = niftyOptionSelector.selectAtmCe(spot, expiryContracts, properties.getStrikeStep());
            OptionContract newAtmPe = niftyOptionSelector.selectAtmPe(spot, expiryContracts, properties.getStrikeStep());
            FixedItmSelectionService.Selection newItm = fixedItmSelectionService.select(expiryContracts, spot, LocalDate.now(IST));

            Map<String, OptionContract> previous = trackedContracts;
            boolean changed = !sameContract(previous.get("ATM CE"), newAtmCe)
                    || !sameContract(previous.get("ATM PE"), newAtmPe)
                    || !sameContract(previous.get("FIXED ITM CE"), newItm.ce())
                    || !sameContract(previous.get("FIXED ITM PE"), newItm.pe());

            if (changed) {
                log.info("30-min rolling window elapsed (spot={}); re-selecting ATM/ITM: ATM CE {}->{}, ATM PE {}->{}, FIXED ITM CE {}->{}, FIXED ITM PE {}->{}",
                        spot,
                        strikeOf(previous.get("ATM CE")), newAtmCe.strike(),
                        strikeOf(previous.get("ATM PE")), newAtmPe.strike(),
                        strikeOf(previous.get("FIXED ITM CE")), newItm.ce().strike(),
                        strikeOf(previous.get("FIXED ITM PE")), newItm.pe().strike());

                angelOneMarketDataService.updateSelection(newAtmCe, newAtmPe, newItm.ce(), newItm.pe());
                trackedContracts = Map.of(
                        "ATM CE", newAtmCe, "ATM PE", newAtmPe,
                        "FIXED ITM CE", newItm.ce(), "FIXED ITM PE", newItm.pe());
                try {
                    greeksCacheService.refresh(expiry, trackedContracts);
                } catch (Exception ex) {
                    log.warn("Failed to refresh Option Greeks after rolling ATM/ITM re-selection", ex);
                }
                premiumReferenceService.resetRollingReference();
            } else {
                log.debug("30-min rolling window elapsed (spot={}); ATM/ITM strikes unchanged", spot);
            }
        } catch (Exception ex) {
            log.error("Failed rolling ATM/ITM re-selection; keeping previously selected strikes", ex);
        } finally {
            lastAtmItmSelectionAt = Instant.now();
        }
    }

    /** Prefers the latest live tick's NIFTY price (no extra REST call); falls back to the Market Quote
     * REST API only if no complete tick snapshot has arrived yet. */
    private double currentSpotForReselection() {
        TickSnapshot latest = TickPersistenceService.getLatestCompleteSnapshot();
        if (latest != null && latest.nifty() != null) {
            return latest.nifty();
        }
        return niftySpotPriceService.getNiftySpotPrice().ltp();
    }

    private boolean sameContract(OptionContract a, OptionContract b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.token().equals(b.token());
    }

    private Object strikeOf(OptionContract contract) {
        return contract != null ? contract.strike() : null;
    }

    /** Stops the NIFTY streaming pipeline every trading weekday at 3:30 PM IST (market close). */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void stopPipeline() {
        try {
            pipelineActive.set(false);
            angelOneMarketDataService.stop();
            log.info("SmartStream subscription stopped for market close");
        } catch (Exception ex) {
            log.error("Failed to stop NIFTY trading pipeline", ex);
        }
    }
}

