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
        return fetch(baseUrl + "/markets?limit=10&sort_by=volume&category=crypto"); // Top 10 crypto markets sorted by volume
    }

    public String getMarkets() {
        return fetch(baseUrl + "/markets?limit=50");
    }

    // ─────────────────────────────────────────────
    // INTERN 2 — Signals Engine
    // ─────────────────────────────────────────────

    public String getSignals() {
        return fetch(baseUrl + "/signals?top=10");
    }

    // ─────────────────────────────────────────────
    // INTERN 3 — OpenClaw AI Agent
    // POST /agent/advice          → free AI advice
    // POST /agent/advice (premium)→ premium advice (x402 gated)
    // ─────────────────────────────────────────────

    public String getAIAdvice(String marketId) {
        String json = String.format("{\"market_id\":\"%s\",\"telegram_id\":null}", marketId);
        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(baseUrl + "/agent/advice")
                .post(body)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return "Error " + response.code();
            return response.body().string();
        } catch (IOException e) {
            return "Connection failed.";
        }
    }

    public String getPremiumAdvice(String marketId, long chatId) {
        String json = String.format(
            "{\"market_id\":\"%s\", \"telegram_id\":\"%d\", \"premium\":true}",
            marketId, chatId
        );
        return post(baseUrl + "/agent/advice", json);
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

    public String placePaperTrade(long chatId, String marketId, String side, double amount) {
        double pricePerShare = 0.5;
        double shares = amount / pricePerShare;
        String outcome = side.toUpperCase();
        String json = String.format(
            "{\"telegram_user_id\":%d,\"market_id\":\"%s\",\"outcome\":\"%s\",\"shares\":%.2f,\"price_per_share\":%.2f}",
            chatId, marketId, outcome, shares, pricePerShare
        );
        return post(baseUrl + "/papertrade", json);
    }

    public String getWalletSummary(long chatId) {
        return fetch(baseUrl + "/wallet/summary?telegram_user_id=" + chatId);
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
