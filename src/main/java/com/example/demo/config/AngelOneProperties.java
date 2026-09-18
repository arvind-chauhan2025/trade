package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for connecting to Angel One's SmartAPI.
 * <p>
 * Populate these in application.properties, e.g.:
 * <pre>
 * angelone.api-key=YOUR_API_KEY
 * angelone.client-code=YOUR_CLIENT_CODE
 * angelone.password=YOUR_PIN
 * angelone.totp-secret=YOUR_TOTP_BASE32_SECRET
 * </pre>
 */
@ConfigurationProperties(prefix = "angelone")
public class AngelOneProperties {

    /** API key generated from the SmartAPI developer portal. */
    private String apiKey;

    /** Angel One client / login code. */
    private String clientCode;

    /** Trading PIN / password for the client code. */
    private String password;

    /** Base32 secret used to generate TOTP codes (from SmartAPI profile). */
    private String totpSecret;

    /** Base URL of the SmartAPI, override for testing if needed. */
    private String baseUrl = "https://apiconnect.angelbroking.com";

    /** Local IP reported to Angel One (required header). */
    private String localIp = "127.0.0.1";

    /** Public IP reported to Angel One (required header). */
    private String publicIp = "127.0.0.1";

    /** MAC address reported to Angel One (required header). */
    private String macAddress = "00:00:00:00:00:00";

    /** Symbol token for NIFTY 50 index used for the Market Quote REST API (NSE index segment). */
    private String niftyQuoteToken = "99926000";

    /** Symbol token for NIFTY 50 index used for SmartStream WebSocket subscription (NSE_CM segment). */
    private String niftyToken = "26000";

    /** URL of the Angel One Scrip Master (instrument dump) JSON file. */
    private String scripMasterUrl = "https://margincalculator.angelone.in/OpenAPI_File/files/OpenAPIScripMaster.json";

    /** Strike price interval used to round the NIFTY spot price to the nearest ATM strike. */
    private int strikeStep = 50;

    private int itmDepth = 1;
    private String fixedItmStatePath = "data/fixed-itm.json";
    private int tickHistorySize = 500;

    public int getTickHistorySize() {
        return tickHistorySize;
    }

    public void setTickHistorySize(int tickHistorySize) {
        this.tickHistorySize = tickHistorySize;
    }

    public int getItmDepth() {
        return itmDepth;
    }

    public void setItmDepth(int itmDepth) {
        this.itmDepth = itmDepth;
    }

    public String getFixedItmStatePath() {
        return fixedItmStatePath;
    }

    public void setFixedItmStatePath(String fixedItmStatePath) {
        this.fixedItmStatePath = fixedItmStatePath;
    }

    public String getNiftyQuoteToken() {
        return niftyQuoteToken;
    }

    public void setNiftyQuoteToken(String niftyQuoteToken) {
        this.niftyQuoteToken = niftyQuoteToken;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getClientCode() {
        return clientCode;
    }

    public void setClientCode(String clientCode) {
        this.clientCode = clientCode;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getTotpSecret() {
        return totpSecret;
    }

    public void setTotpSecret(String totpSecret) {
        this.totpSecret = totpSecret;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getLocalIp() {
        return localIp;
    }

    public void setLocalIp(String localIp) {
        this.localIp = localIp;
    }

    public String getPublicIp() {
        return publicIp;
    }

    public void setPublicIp(String publicIp) {
        this.publicIp = publicIp;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public String getScripMasterUrl() {
        return scripMasterUrl;
    }

    public void setScripMasterUrl(String scripMasterUrl) {
        this.scripMasterUrl = scripMasterUrl;
    }

    public int getStrikeStep() {
        return strikeStep;
    }

    public void setStrikeStep(int strikeStep) {
        this.strikeStep = strikeStep;
    }

    public String getNiftyToken() {
        return niftyToken;
    }

    public void setNiftyToken(String niftyToken) {
        this.niftyToken = niftyToken;
    }
}

