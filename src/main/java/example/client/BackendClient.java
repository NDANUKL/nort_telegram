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
        this.baseUrl = "http://100.117.249.117:8000";  // Your server IP

        // Point to Render backend
        this.baseUrl = "https://nort.onrender.com";
    }

    public String getTrendingMarkets() {
        return fetch(baseUrl + "/markets?limit=20&sort_by=volume");
    }

    public String getMarkets() {
        return fetch(baseUrl + "/markets?limit=50");
    }

    public String getSignals() {
        return fetch(baseUrl + "/signals?top=20");
    }

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
            return "❌ Connection to AI Agent failed.";
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
                "{\"telegram_user_id\":%d,\"market_id\":\"%s\",\"outcome\":\"%s\",\"shares\":%.2f,\"price_per_share\":%.2f}",
                chatId, marketId, outcome, shares, pricePerShare
        );
        return post(baseUrl + "/papertrade", json);
    }

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
