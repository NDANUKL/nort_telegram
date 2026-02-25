package example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import example.client.BackendClient;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppData;
import java.util.ArrayList;
import java.util.List;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;

public class Bot extends TelegramLongPollingBot {

    // Access to the Python Backend via our Client
    private final BackendClient backend = new BackendClient();

    @Override
    public String getBotUsername() {
        return "Nort67Bot";
    }

    @Override
    public String getBotToken() {
        return System.getenv("BOT_TOKEN");
    }

    @Override
    public void onUpdateReceived(Update update) {

        // --- HANDLER 0: WEB APP DATA ---
        if (update.hasMessage() && update.getMessage().getWebAppData() != null) {
            long chatId = update.getMessage().getChatId();
            WebAppData wad = update.getMessage().getWebAppData();
            String data = wad.getData();
            sendText(chatId, "📨 Received data from Web App: " + data);
            return;
        }

        // --- HANDLER 1: TEXT COMMANDS ---
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            // Handle transaction proof reply (must be checked before command parsing)
            if (messageText.matches("[0-9a-fA-F]{64}")) {
                String txHash = messageText;
                String verifyResult = backend.verifyPayment(txHash, chatId);
                try {
                    org.json.JSONObject verifyJson = new org.json.JSONObject(verifyResult);
                    if (verifyJson.getBoolean("success")) {
                        sendText(chatId, "✅ Payment verified! Unlocking premium advice...");
                        String unlocked = backend.getPremiumAdvice("last_market_id", chatId);
                        org.json.JSONObject unlockedJson = new org.json.JSONObject(unlocked);
                        sendText(chatId, "💎 *Premium Content:*\n" + unlockedJson.getString("content"));
                    } else {
                        sendText(chatId, "❌ Payment not verified. Details: " + verifyJson.optString("reason", "Unknown error"));
                    }
                } catch (Exception e) {
                    sendText(chatId, "Verification error: " + e.getMessage() + "\nRaw: " + verifyResult);
                }
                return;
            }

            String command = messageText.split(" ")[0];

            switch (command) {
                case "/start":
                    sendMenu(chatId);
                    break;

                case "/trending":
                    sendText(chatId, "*Querying Signals Engine...*");
                    String marketData = backend.getTrendingMarkets();
                    sendText(chatId, "**Top Opportunities:**\n" + marketData);
                    break;

                case "/advice":
                    String[] adviceParts = messageText.split(" ");
                    if (adviceParts.length < 2) {
                        sendText(chatId, "Usage: /advice <market_id>\nExample: /advice 527079");
                    } else {
                        String mktId = adviceParts[1];
                        System.out.println("DEBUG /advice: requesting premium advice for market " + mktId);
                        String premiumResponse = backend.getPremiumAdvice(mktId, chatId);
                        System.out.println("DEBUG /advice: backend response = " + premiumResponse);
                        try {
                            org.json.JSONObject json = new org.json.JSONObject(premiumResponse);
                            sendText(chatId, "💎 *Premium Content:*\n" + json.getString("content"));
                        } catch (Exception e) {
                            System.out.println("DEBUG /advice: JSON parse failed, checking for 402...");
                            if (premiumResponse.contains("402") || premiumResponse.contains("PAYMENT-REQUIRED")) {
                                try {
                                    org.json.JSONObject paymentJson = new org.json.JSONObject(premiumResponse);
                                    double amount = paymentJson.getDouble("amount");
                                    String address = paymentJson.getString("address");
                                    String asset = paymentJson.getString("asset");
                                    sendText(chatId, "💎 *Premium Content Locked*\nTo unlock, send $" + amount + " " + asset + " to address: `" + address + "` on Base network.\nReply with your transaction hash.");
                                } catch (Exception ex) {
                                    System.out.println("DEBUG /advice: Failed to parse payment JSON: " + ex.getMessage());
                                    sendText(chatId, "Payment required, but could not parse payment details.\nRaw: " + premiumResponse);
                                }
                            } else {
                                System.out.println("DEBUG /advice: No 402 found, treating as error");
                                sendText(chatId, "Error: " + e.getMessage() + "\nRaw: " + premiumResponse.substring(0, Math.min(200, premiumResponse.length())));
                            }
                        }
                    }
                    break;

                case "/portfolio":
                    sendText(chatId, "📂 **Portfolio Summary (Paper Mode)**\nBalance: $1,000.00\nActive Bets: 0");
                    break;

                case "/markets":
                    sendText(chatId, "*Fetching Markets...*");
                    String rawMarkets = backend.getMarkets();
                    sendText(chatId, "**Top Markets:**\n" + rawMarkets);
                    break;

                case "/signals":
                    sendText(chatId, "*Analyzing Market Momentum...*");
                    String signals = backend.getSignals();
                    sendText(chatId, "**Top Opportunities:**\n" + signals);
                    break;

                case "/papertrade":
                    String[] parts = messageText.split(" ");
                    if (parts.length < 4) {
                        sendText(chatId, "📝 Use: `/papertrade <id> <yes/no> <amount>`");
                    } else {
                        String result = backend.placePaperTrade(chatId, parts[1], parts[2], Double.parseDouble(parts[3]));
                        sendText(chatId, "🎯 **Trade Status:** " + result);
                    }
                    break;

                default:
                    sendText(chatId, "❓ Unknown command. Tap /start to see options.");
            }
        }

        // --- HANDLER 2: BUTTON CLICKS (CALLBACK QUERIES) ---
        else if (update.hasCallbackQuery()) {
            String callData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();

            if (callData.equals("btn_trending")) {
                String response = backend.getTrendingMarkets();
                sendText(chatId, "**Current Trends:**\n" + response);
            }
            else if (callData.equals("btn_advice")) {
                sendText(chatId, "Enter the Market ID for AI analysis:");
            }
            else if (callData.equals("btn_portfolio")) {
                sendText(chatId, "📂 Loading your paper trades...");
            }
        }
    }

    // --- UI COMPONENT: MAIN MENU ---
    public void sendMenu(Long chatId) {
        SendMessage sm = SendMessage.builder()
                .chatId(chatId.toString())
                .text("**Nort67 AI Assistant**\nWelcome to the prediction market hub. Select a service:")
                .parseMode("Markdown")
                .build();

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        // Row 1: Market Analysis
        List<InlineKeyboardButton> row1 = new ArrayList<>();
        row1.add(InlineKeyboardButton.builder().text("🔥 Trending").callbackData("btn_trending").build());
        row1.add(InlineKeyboardButton.builder().text("🧠 AI Advice").callbackData("btn_advice").build());

        // Row 2: Management
        List<InlineKeyboardButton> row2 = new ArrayList<>();
        row2.add(InlineKeyboardButton.builder().text("📂 Portfolio").callbackData("btn_portfolio").build());

        rowsInline.add(row1);
        rowsInline.add(row2);  // FIXED: was row2Bot

        markupInline.setKeyboard(rowsInline);
        sm.setReplyMarkup(markupInline);

        try {
            execute(sm);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    // Return web app URL from env var `WEBAPP_URL`, fall back to BotFather short link
    private String getWebAppUrl() {
        String u = System.getenv("WEBAPP_URL");
        if (u == null || u.isEmpty()) {
            return "https://t.me/Nort67Bot/nort";
        }
        return u;
    }

    // Call Telegram Bot API to set the Chat Menu Button to a Web App (for top-right menu)
    public void setChatMenuWebApp(String webAppUrl) {
        String token = getBotToken();
        if (token == null || token.isEmpty()) {
            System.out.println("setChatMenuWebApp: BOT_TOKEN not set");
            return;
        }
        String api = String.format("https://api.telegram.org/bot%s/setChatMenuButton", token);
        String payload = String.format("{\"menu_button\":{\"type\":\"web_app\",\"text\":\"🖥️ Open Dashboard\",\"web_app\":{\"url\":\"%s\"}}}", webAppUrl);

        OkHttpClient client = new OkHttpClient();
        RequestBody body = RequestBody.create(payload, MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(api).post(body).build();

        try (Response response = client.newCall(request).execute()) {
            if (response.body() != null) {
                System.out.println("setChatMenuWebApp response: " + response.body().string());
            } else {
                System.out.println("setChatMenuWebApp: empty response");
            }
        } catch (IOException e) {
            System.out.println("setChatMenuWebApp error: " + e.getMessage());
        }
    }

    // --- HELPER: BASIC TEXT SENDER ---
    public void sendText(Long chatId, String text) {
        SendMessage sm = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("Markdown")
                .build();
        try {
            execute(sm);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }
}