package example.client;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class BackendClient {
    private final OkHttpClient client;
    private final String baseUrl;
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public BackendClient() {
        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();
        this.baseUrl = "https://nort.onrender.com";
    }

    // ─────────────────────────────────────────────
    // INTERN 1 — Market Data
    // ─────────────────────────────────────────────

    public String getTrendingMarkets() {
        return fetch(baseUrl + "/markets?limit=10&sort_by=volume");
    }

    public String getMarkets() {
        return fetch(baseUrl + "/markets?limit=50");
    }

    // ─────────────────────────────────────────────
    // INTERN 2 — Signals Engine
    // ─────────────────────────────────────────────

    public String getSignals() {
        return fetch(baseUrl + "/signals?top=20");
    }

    // ─────────────────────────────────────────────
    // INTERN 1 — Market Detail (single market)
    // GET /market/{id} → question, odds, volume, category, expiry
    // ─────────────────────────────────────────────

    public String getMarketDetail(String marketId) {
        return fetch(baseUrl + "/market/" + marketId);
    }

    // ─────────────────────────────────────────────
    // INTERN 3 — OpenClaw AI Agent
    // POST /agent/advice               → free advice (2-sentence + recommendation)
    // POST /agent/advice (premium:true) → full structured advice (x402 gated)
    // ─────────────────────────────────────────────

    /**
     * Free advice — returns a brief summary + BUY YES / BUY NO / WAIT.
     * No USDC deduction. Calls POST /agent/advice with premium:false.
     */
    public String getFreeAdvice(String marketId, long chatId) {
        String json = String.format(
            "{\"market_id\":\"%s\", \"telegram_id\":\"%d\", \"premium\":false}",
            marketId, chatId
        );
        return post(baseUrl + "/agent/advice", json);
    }

    /**
     * Premium advice — full structured output gated by x402 USDC payment.
     * Deducts 0.10 USDC via CDP SDK on Base before unlocking response.
     * Calls POST /agent/advice with premium:true.
     */
    public String getPremiumAdvice(String marketId, long chatId) {
        String json = String.format(
            "{\"market_id\":\"%s\", \"telegram_id\":\"%d\", \"premium\":true}",
            marketId, chatId
        );
        return post(baseUrl + "/agent/advice", json);
    }

    // ─────────────────────────────────────────────
    // INTERN 4 — Account & Setup (Onboarding)
    // POST /wallet/connect → register user + create CDP wallet
    // POST /wallet/authorize → store ERC-20 spending allowance
    // PATCH /permissions → update trading mode
    // ─────────────────────────────────────────────

    /**
     * Registers a new user and triggers CDP wallet creation.
     * Called on /start for first-time users.
     */
    public String registerUser(long chatId, String username) {
        String json = String.format(
            "{\"telegram_id\":\"%d\", \"username\":\"%s\"}",
            chatId, username != null ? username : "user_" + chatId
        );
        return post(baseUrl + "/wallet/connect", json);
    }

    /**
     * Stores the user's ERC-20 USDC spending allowance on the backend.
     * Called on /authorize <max_usdc>.
     */
    public String authorizeSpending(long chatId, double maxUsdc) {
        String json = String.format(
            "{\"telegram_id\":\"%d\", \"max_usdc\":%.2f}",
            chatId, maxUsdc
        );
        return post(baseUrl + "/wallet/authorize", json);
    }

    /**
     * Updates the user's trading mode (PAPER or REAL) on the backend.
     * Called on /mode <PAPER|REAL>.
     */
    public String setTradingMode(long chatId, String mode) {
        String json = String.format(
            "{\"telegram_id\":\"%d\", \"trade_mode\":\"%s\"}",
            chatId, mode
        );
        return post(baseUrl + "/permissions", json);
    }

    // ─────────────────────────────────────────────
    // INTERN 4 — x402 Payment Verification
    // POST /agent/x402/verify → verify payment proof
    // ─────────────────────────────────────────────

    public String verifyPayment(String proof, long chatId) {
        String json = String.format(
            "{\"proof\":\"%s\", \"user_id\":\"%d\"}",
            proof, chatId
        );
        return post(baseUrl + "/agent/x402/verify", json);
    }

    // ─────────────────────────────────────────────
    // INTERN 5 — Paper Trading + Wallet
    // ─────────────────────────────────────────────

    /**
     * GET /wallet/summary — balance, net P&L, trade count, open positions.
     * Used by /wallet and /portfolio.
     */
    public String getWalletSummary(long chatId) {
        return fetch(baseUrl + "/wallet/summary?telegram_user_id=" + chatId);
    }

    /**
     * POST /topup — initiates M-Pesa STK push for the given KES amount.
     * Backend converts KES → USDC via live FX and triggers Daraja API.
     */
    public String initiateTopup(long chatId, double amountKes) {
        String json = String.format(
            "{\"telegram_user_id\":%d, \"amount_kes\":%.2f}",
            chatId, amountKes
        );
        return post(baseUrl + "/topup", json);
    }

    /**
     * GET /trade/history?telegram_user_id={id}&status=OPEN
     * Returns all open positions for the user.
     * Used by /positions.
     */
    public String getOpenPositions(long chatId) {
        return fetch(baseUrl + "/trade/history?telegram_user_id=" + chatId + "&status=OPEN");
    }

    /**
     * POST /papertrade — place a paper trade in PAPER mode.
     * POST /trade/real — place a real on-chain trade in REAL mode.
     * The correct endpoint is selected by the bot based on session mode.
     */
    public String placePaperTrade(long chatId, String marketId, String outcome, double amount) {
        // Fetch live price from market detail to get real price_per_share
        // For now use 0.5 as default; the backend will validate
        double pricePerShare = 0.5;
        double shares = amount / pricePerShare;
        String json = String.format(
            "{\"telegram_user_id\":%d,\"market_id\":\"%s\",\"outcome\":\"%s\",\"shares\":%.4f,\"price_per_share\":%.4f}",
            chatId, marketId, outcome.toUpperCase(), shares, pricePerShare
        );
        return post(baseUrl + "/papertrade", json);
    }

    public String placeRealTrade(long chatId, String marketId, String outcome, double amountUsdc) {
        String json = String.format(
            "{\"telegram_user_id\":%d,\"market_id\":\"%s\",\"outcome\":\"%s\",\"amount_usdc\":%.4f}",
            chatId, marketId, outcome.toUpperCase(), amountUsdc
        );
        return post(baseUrl + "/trade/real", json);
    }

    /**
     * POST /trade/sell/{trade_id} — close an open position early.
     * P&L is calculated at close using live odds and stored in DB.
     * Used by /close.
     */
    public String closePosition(long chatId, String tradeId) {
        String json = String.format("{\"telegram_user_id\":%d}", chatId);
        return post(baseUrl + "/trade/sell/" + tradeId, json);
    }

    // ─────────────────────────────────────────────
    // INTERN 5 — Social & Copy Trading
    // GET  /leaderboard              → top traders by P&L + win rate
    // POST /copytrading/follow       → subscribe to copy a leader
    // DELETE /copytrading/follow/{username} → stop copying a leader
    // GET  /copytrading/followers    → who is copying the current user
    // ─────────────────────────────────────────────

    /**
     * Returns the leaderboard — top traders ranked by portfolio value,
     * net P&L, win rate, and badge. Used by /leaders.
     */
    public String getLeaderboard() {
        return fetch(baseUrl + "/leaderboard");
    }

    /**
     * Subscribes to copy-trade a leader at the given allocation percentage.
     * pct = percentage of the follower's balance to mirror per trade.
     * Called by /copy <username> <pct>.
     */
    public String followLeader(long chatId, String leaderUsername, double pct) {
        String json = String.format(
            "{\"telegram_user_id\":%d, \"leader_username\":\"%s\", \"allocation_pct\":%.2f}",
            chatId, leaderUsername, pct
        );
        return post(baseUrl + "/copytrading/follow", json);
    }

    /**
     * Unsubscribes from copy-trading a leader.
     * Existing open positions are NOT affected — they remain open.
     * Called by /uncopy <username>.
     */
    public String unfollowLeader(long chatId, String leaderUsername) {
        String json = String.format(
            "{\"telegram_user_id\":%d, \"leader_username\":\"%s\"}",
            chatId, leaderUsername
        );
        return post(baseUrl + "/copytrading/unfollow", json);
    }

    /**
     * Returns the list of users currently copy-trading the current user —
     * their display names, allocation percentages, and total capital.
     * Called by /myfollowers.
     */
    public String getMyFollowers(long chatId) {
        return fetch(baseUrl + "/copytrading/followers?telegram_user_id=" + chatId);
    }

    // ─────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────

    private String fetch(String url) {
        Request request = new Request.Builder().url(url).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return "Error " + response.code();
            return response.body().string();
        } catch (IOException e) {
            return "Connection failed.";
        }
    }

    private String post(String url, String jsonBody) {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder().url(url).post(body).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return "Error " + response.code();
            return response.body().string();
        } catch (IOException e) {
            return "Connection failed.";
        }
    }
}
