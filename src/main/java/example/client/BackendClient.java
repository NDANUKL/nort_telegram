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
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .build();

        // ── FIXED: point to Windows machine IP, not localhost ──
        this.baseUrl = "http://100.117.249.117:8000";
    }

    // ─────────────────────────────────────────────
    // INTERN 1 — Market Data
    // GET /markets/        → all active markets
    // GET /markets/{id}    → single market detail
    // ─────────────────────────────────────────────

    public String getTrendingMarkets() {
        return fetch(baseUrl + "/markets");  // FIXED

    }

    public String getMarkets() {
        return fetch(baseUrl + "/markets");
    }


    // ─────────────────────────────────────────────
    // INTERN 2 — Signals Engine
    // GET /signals/?top=20 → ranked market opportunities
    // ─────────────────────────────────────────────

    public String getSignals() {
        return fetch(baseUrl + "/signals?top=20");
    }

    // ─────────────────────────────────────────────
    // INTERN 3 — OpenClaw AI Agent
    // POST /agent/advice → free AI advice
    // POST /agent/advice with premium:true → premium advice
    // ─────────────────────────────────────────────

    public String getAIAdvice(String marketId) {
        String json = String.format("{\"market_id\":\"%s\",\"telegram_id\":null,\"premium\":false}", marketId);
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(baseUrl + "/agent/advice")
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return "⚠️ AI Agent busy or market ID invalid.";
            return response.body().string();
        } catch (IOException e) {
            return "❌ Connection to OpenClaw failed.";
        }
    }

    public String getPremiumAdvice(String marketId, String paymentProof) {
        String json = String.format(
                "{\"market_id\":\"%s\", \"premium\":true}",
                marketId
        );
        return post(baseUrl + "/agent/advice", json);
    }

    // ─────────────────────────────────────────────
    // INTERN 5 — Paper Trading + Wallet
    // POST /papertrade              → place a paper trade
    // GET  /wallet/summary          → get wallet balance and trades
    // POST /trade/commit            → attach testnet receipt
    // ─────────────────────────────────────────────

    public String placePaperTrade(long chatId, String marketId, String side, double amount) {
        double pricePerShare = 0.5;
        double shares = amount / pricePerShare;
        String outcome = side.toUpperCase();
        String json = String.format(
                "{\"telegram_user_id\":\"%d\", \"market_id\":\"%s\", \"market_question\":\"Market %s\", \"outcome\":\"%s\", \"shares\":%.2f, \"price_per_share\":%.2f, \"direction\":\"BUY\"}",
                chatId, marketId, marketId, outcome, shares, pricePerShare
        );
        return post(baseUrl + "/papertrade", json);
    }

    public String getWalletSummary(long chatId) {
        return fetch(baseUrl + "/wallet/summary?telegram_user_id=" + chatId);
    }

    public String commitTrade(int tradeId) {
        String json = String.format("{\"trade_id\":%d}", tradeId);
        return post(baseUrl + "/trade/commit", json);
    }

    // ─────────────────────────────────────────────
    // INTERN 4 — x402 Payment Verification
    // POST /x402/verify → verify payment proof
    // ─────────────────────────────────────────────

    public String verifyPayment(String proof, long chatId) {
        String json = String.format(
                "{\"proof\":\"%s\", \"user_id\":\"%d\"}",
                proof, chatId
        );
        return post(baseUrl + "/x402/verify", json);
    }

    // ─────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────

    private String fetch(String url) {
        Request request = new Request.Builder()
                .url(url)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "Error " + response.code() + ": " + response.message();
            }
            return response.body().string();
        } catch (IOException e) {
            return "Connection failed: " + e.getMessage();
        }
    }

    private String post(String url, String jsonBody) {
        RequestBody body = RequestBody.create(jsonBody, JSON);
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return "Error " + response.code() + ": " + response.message();
            }
            return response.body().string();
        } catch (IOException e) {
            return "Connection failed: " + e.getMessage();
        }
    }
}