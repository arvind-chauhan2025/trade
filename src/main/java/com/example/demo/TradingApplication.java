package com.example.demo;

import com.example.demo.config.AngelOneProperties;
import com.example.demo.dto.OptionContract;
import com.example.demo.service.AngelOneAuthService;
import com.example.demo.service.AngelOneMarketDataService;
import com.example.demo.service.NiftyOptionSelector;
import com.example.demo.service.NiftySpotPriceService;
import com.example.demo.service.ScripMasterService;
import com.example.demo.service.FixedItmSelectionService;
import com.example.demo.service.GreeksCacheService;
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
 * IST) and stop at market close (3:30 PM IST) on trading weekdays.
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

    @Value("${trading.run-on-startup:false}")
    private boolean runOnStartup;

    private final AtomicBoolean pipelineActive = new AtomicBoolean(false);
    private volatile LocalDate trackedExpiry;
    private volatile Map<String, OptionContract> trackedContracts = Map.of();

    public TradingApplication(NiftySpotPriceService niftySpotPriceService,
                               ScripMasterService scripMasterService,
                               NiftyOptionSelector niftyOptionSelector,
                               AngelOneAuthService angelOneAuthService,
                               AngelOneMarketDataService angelOneMarketDataService,
                               AngelOneProperties properties,
                               FixedItmSelectionService fixedItmSelectionService,
                               GreeksCacheService greeksCacheService) {
        this.niftySpotPriceService = niftySpotPriceService;
        this.scripMasterService = scripMasterService;
        this.niftyOptionSelector = niftyOptionSelector;
        this.angelOneAuthService = angelOneAuthService;
        this.angelOneMarketDataService = angelOneMarketDataService;
        this.properties = properties;
        this.fixedItmSelectionService = fixedItmSelectionService;
        this.greeksCacheService = greeksCacheService;
    }

    /**
     * When {@code trading.run-on-startup=true}, invokes {@link #startPipeline()} once
     * immediately at application boot — useful for testing outside the 9:15 AM–3:30 PM
     * IST schedule. No-op (default) otherwise.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (runOnStartup) {
            log.info("trading.run-on-startup=true; starting pipeline immediately for testing");
            startPipeline();
        }
    }

    /** Starts the NIFTY streaming pipeline every trading weekday at 9:15 AM IST (market open). */
    @Scheduled(cron = "0 15 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void startPipeline() {
        try {
            double spot = niftySpotPriceService.getNiftySpotPrice().ltp();
            log.info("NIFTY spot price: {}", spot);

            List<OptionContract> allContracts = scripMasterService.getNiftyOptions();
            FixedItmSelectionService.Selection fixed = fixedItmSelectionService.getOrSelect(
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

            String jwtToken = angelOneAuthService.getJwtToken();
            String feedToken = angelOneAuthService.getFeedToken();

            trackedExpiry = fixed.expiry();
            trackedContracts = Map.of(
                    "ATM CE", atmCe, "ATM PE", atmPe,
                    "FIXED ITM CE", fixed.ce(), "FIXED ITM PE", fixed.pe());

            try {
                greeksCacheService.refresh(trackedExpiry, trackedContracts);
            } catch (Exception ex) {
                log.warn("Failed to fetch Option Greeks; Delta will be unavailable for this session", ex);
            }

            angelOneMarketDataService.start(jwtToken, feedToken, atmCe, atmPe, fixed.ce(), fixed.pe());
            pipelineActive.set(true);
            log.info("SmartStream subscription initiated for NIFTY, ATM CE/PE and fixed ITM CE/PE");
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


