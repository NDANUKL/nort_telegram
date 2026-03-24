package example;

/**
 * Holds per-user state that needs to persist across messages within a session.
 *
 * Stored in a ConcurrentHashMap keyed by Telegram chatId in Bot.java.
 * State is in-memory only — it resets if the bot restarts, which is fine for v1.
 */
public class UserSession {

    public enum TradingMode { PAPER, REAL }

    // Whether the user has completed /start onboarding
    private boolean registered = false;

    // Whether the user has run /authorize and set a spending allowance
    private boolean authorized = false;

    // The max USDC spending allowance the user set via /authorize
    private double maxUsdc = 0.0;

    // Current trading mode — PAPER by default, REAL requires explicit /mode REAL
    private TradingMode mode = TradingMode.PAPER;

    // Last market ID the user interacted with (used for context in follow-up commands)
    private String lastMarketId = null;

    // ── Getters ──────────────────────────────────────────────────────────────

    public boolean isRegistered()    { return registered; }
    public boolean isAuthorized()    { return authorized; }
    public double  getMaxUsdc()      { return maxUsdc; }
    public TradingMode getMode()     { return mode; }
    public String  getLastMarketId() { return lastMarketId; }

    // ── Setters ──────────────────────────────────────────────────────────────

    public void setRegistered(boolean registered)    { this.registered = registered; }
    public void setAuthorized(boolean authorized)    { this.authorized = authorized; }
    public void setMaxUsdc(double maxUsdc)           { this.maxUsdc = maxUsdc; }
    public void setMode(TradingMode mode)            { this.mode = mode; }
    public void setLastMarketId(String lastMarketId) { this.lastMarketId = lastMarketId; }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isRealMode() { return mode == TradingMode.REAL; }

    public String modeLabel() { return mode == TradingMode.REAL ? "REAL 🔴" : "PAPER 🟢"; }
}
