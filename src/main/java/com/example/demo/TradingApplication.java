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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Orchestrates Step 1 of the NIFTY live options trading analytics backend:
 * fetch the NIFTY spot price, resolve the nearest expiry and ATM CE contract
 * from the Scrip Master, log in to Angel One, and start streaming live LTP
 * ticks for both instruments over SmartStream.
 */
@Component
public class TradingApplication implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TradingApplication.class);

    private final NiftySpotPriceService niftySpotPriceService;
    private final ScripMasterService scripMasterService;
    private final NiftyOptionSelector niftyOptionSelector;
    private final AngelOneAuthService angelOneAuthService;
    private final AngelOneMarketDataService angelOneMarketDataService;
    private final AngelOneProperties properties;
    private final FixedItmSelectionService fixedItmSelectionService;
    private final GreeksCacheService greeksCacheService;

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

    @Override
    public void run(ApplicationArguments args) {
        try {
            double spot = niftySpotPriceService.getNiftySpotPrice().ltp();
            log.info("NIFTY spot price: {}", spot);

            List<OptionContract> allContracts = scripMasterService.getNiftyOptions();
            FixedItmSelectionService.Selection fixed = fixedItmSelectionService.getOrSelect(
                    allContracts, spot, LocalDate.now(ZoneId.of("Asia/Kolkata")));
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

            try {
                greeksCacheService.refresh(fixed.expiry(), Map.of(
                        "ATM CE", atmCe, "ATM PE", atmPe,
                        "FIXED ITM CE", fixed.ce(), "FIXED ITM PE", fixed.pe()));
            } catch (Exception ex) {
                log.warn("Failed to fetch Option Greeks; Delta will be unavailable for this session", ex);
            }

            angelOneMarketDataService.start(jwtToken, feedToken, atmCe, atmPe, fixed.ce(), fixed.pe());
            log.info("SmartStream subscription initiated for NIFTY, ATM CE/PE and fixed ITM CE/PE");
        } catch (Exception ex) {
            log.error("Failed to bootstrap NIFTY trading pipeline", ex);
        }
    }
}

