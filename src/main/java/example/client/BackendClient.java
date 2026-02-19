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
        // Updated with longer timeouts because AI agents (OpenClaw) take time to process
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS) // OpenClaw gets 60 seconds to respond
                .build();

        // Point this to where your team's FastAPI is running
        this.baseUrl = "http://localhost:8000";
    }

    public String getTrendingMarkets() {
        Request request = new Request.Builder()
                .url(baseUrl + "/markets/trending") // Intern 1's endpoint
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return "Error: Backend unreachable.";
            return response.body().string();
        } catch (IOException e) {
            return "Connection failed: " + e.getMessage();
        }
    }

    public String getSignals() {
        // Intern 2's endpoint
        Request request = new Request.Builder()
                .url(baseUrl + "/signals?top=20")
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful() ? response.body().string() : "⚠️ Signals engine offline.";
        } catch (IOException e) {
            return "❌ Connection Error: " + e.getMessage();
        }
    }

    /**
     * Fixed the error: This method was missing!
     * Calls Intern 3's OpenClaw AI Advice endpoint.
     */
    public String getAIAdvice(String marketId) {
        Request request = new Request.Builder()
                .url(baseUrl + "/agent/advice?market_id=" + marketId)
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) return "⚠️ AI Agent busy or market ID invalid.";
            return response.body().string();
        } catch (IOException e) {
            return "❌ Connection to OpenClaw failed.";
        }
    }

    public String placePaperTrade(long chatId, String marketId, String side, double amount) {
        // Intern 5's endpoint
        // Using string formatting to build the JSON body
        String json = String.format("{\"user_id\":\"%d\", \"market_id\":\"%s\", \"side\":\"%s\", \"amount\":%f}",
                chatId, marketId, side, amount);

        RequestBody body = RequestBody.create(json, JSON);
        Request request = new Request.Builder()
                .url(baseUrl + "/papertrade")
                .post(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful() ? response.body().string() : "⚠️ Trade rejected by backend.";
        } catch (IOException e) {
            return "❌ Trade failed: Connection error.";
        }
    }
}