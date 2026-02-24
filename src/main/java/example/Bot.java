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
        // Intern 4: Set this in IntelliJ Run Configuration Environment Variables!
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
            if (messageText.matches("[0-9a-fA-F]{64}")) { // crude tx hash check
                // In production, check user state for pending payment
                String txHash = messageText;
                String verifyResult = backend.verifyPayment(txHash, chatId);
                try {
                    org.json.JSONObject verifyJson = new org.json.JSONObject(verifyResult);
                    if (verifyJson.getBoolean("success")) {
                        sendText(chatId, "✅ Payment verified! Unlocking premium advice...");
                        // Fetch premium content
                        // You may want to store the last marketId requested by user
                        String unlocked = backend.getPremiumAdvice("last_market_id", txHash);
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
                    // [INTERN 1 & 2]: Fetching markets and signals from Python
                    sendText(chatId, "⌛ *Querying Signals Engine...*");
                    String marketData = backend.getTrendingMarkets();
                    sendText(chatId, "🔥 **Top Opportunities:**\n" + marketData);
                    break;

                case "/advice":
                    String[] adviceParts = messageText.split(" ");
                    if (adviceParts.length < 2) {
                        sendText(chatId, "Usage: /advice <market_id>\nExample: /advice 517310");
                    } else {
                        String mktId = adviceParts[1];
                        // x402 Payment Gate - Start flow
                        String premiumResponse = backend.getPremiumAdvice(mktId, null);
                        try {
                            org.json.JSONObject json = new org.json.JSONObject(premiumResponse);
                            sendText(chatId, "💎 *Premium Content:*\n" + json.getString("content"));
                        } catch (Exception e) {
                            // Check for 402 Payment Required
                            if (premiumResponse.contains("402") || premiumResponse.contains("PAYMENT-REQUIRED")) {
                                // Parse payment requirements from JSON body
                                try {
                                    org.json.JSONObject paymentJson = new org.json.JSONObject(premiumResponse);
                                    double amount = paymentJson.getDouble("amount");
                                    String address = paymentJson.getString("address");
                                    String asset = paymentJson.getString("asset");
                                    sendText(chatId, "💎 *Premium Content Locked*\nTo unlock, send $" + amount + " " + asset + " to address: `" + address + "` on Base network.\nReply with your transaction hash.");
                                    // Store state for user (in production, use DB or cache)
                                } catch (Exception ex) {
                                    sendText(chatId, "Payment required, but could not parse payment details.\nRaw: " + premiumResponse);
                                }
                            } else {
                                sendText(chatId, "Error: " + e.getMessage() + "\nRaw: " + premiumResponse);
                            }
                        }
                    }
                    break;

                case "/portfolio":
                    // [INTERN 5]: Fetching Paper Trade history from SQLite
                    sendText(chatId, "📂 **Portfolio Summary (Paper Mode)**\nBalance: $1,000.00\nActive Bets: 0");
                    break;

                case "/signals":
                    sendText(chatId, "*Analyzing Market Momentum...*");
                    String signals = backend.getSignals(); // Calling real logic!
                    sendText(chatId, "**Top Opportunities:**\n" + signals);
                    break;

                case "/papertrade":
                    // Example: /papertrade 123 yes 50
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
                // Triggering Intern 1's Market Logic
                String response = backend.getTrendingMarkets();
                sendText(chatId, "**Current Trends:**\n" + response);
            }
            else if (callData.equals("btn_advice")) {
                // Triggering Intern 3's Agent Logic
                sendText(chatId, "Enter the Market ID for AI analysis:");
            }
            else if (callData.equals("btn_portfolio")) {
                // Triggering Intern 5's Paper Wallet
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

        // Row 3: The Dashboard (Intern 6) - provide both App and Browser options
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        String hostUrl = System.getenv("WEBAPP_URL");
        if (hostUrl == null || hostUrl.isEmpty()) {
            hostUrl = "https://nort-rho.vercel.app/";
        }

        // Button A: open in Telegram Web App using hosted URL
        InlineKeyboardButton appBtn = InlineKeyboardButton.builder()
                .text("🖥️ Open Dashboard (App)")
                .webApp(new WebAppInfo(hostUrl))
                .build();

        // Button B: open hosted URL in browser - reliable on desktop
        InlineKeyboardButton browserBtn = InlineKeyboardButton.builder()
                .text("🌐 Open Dashboard (Browser)")
                .url(hostUrl)
                .build();

        rowsInline.add(row1);
        rowsInline.add(row2);
        rowsInline.add(row3);

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
    // Uses the bot token from env `BOT_TOKEN`.
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