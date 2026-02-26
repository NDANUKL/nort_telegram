package example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import example.client.BackendClient;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

public class Bot extends TelegramLongPollingBot {

    private final BackendClient backend = new BackendClient();

    @Override
    public String getBotUsername() {
        return "Nort67Bot";
    }

    @Override
    public String getBotToken() {
        String token = System.getenv("BOT_TOKEN");
        if (token == null || token.isEmpty()) {
            throw new RuntimeException("BOT_TOKEN environment variable not set!");
        }
        return token;
        return System.getenv("BOT_TOKEN");
    }

    @Override
    public void onUpdateReceived(Update update) {
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
                    sendText(chatId, "Querying trending markets...");
                    sendText(chatId, "*Querying Signals Engine...*");
                    String marketData = backend.getTrendingMarkets();
                    if (marketData == null || marketData.trim().isEmpty()) {
                        marketData = "No trending markets available at this time.";
                    }
                    sendText(chatId, "TRENDING MARKETS\n" +
                            "═══════════════════════\n\n" + marketData);
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
                    sendText(chatId, "Fetching market data...");
                    String rawMarkets = backend.getMarkets();
                    if (rawMarkets == null || rawMarkets.trim().isEmpty()) {
                        rawMarkets = "No market data available at this time.";
                    }
                    sendText(chatId, "LIVE MARKETS\n" +
                            "═══════════════════════\n\n" + rawMarkets);
                    break;

                case "/signals":
                    sendText(chatId, "*Analyzing Market Momentum...*");
                    String signals = backend.getSignals();
                    sendText(chatId, "**Top Opportunities:**\n" + signals);
                    break;

                case "/papertrade":
                    String[] parts = messageText.split(" ");
                    if (parts.length < 4) {
                        sendText(chatId, "PAPER TRADE ORDER\n" +
                                "═══════════════════════\n" +
                                "Usage: /papertrade <market_id> <yes/no> <amount>\n" +
                                "Example: /papertrade 527079 yes 50\n\n" +
                                "Simulates trades without real money risk.");
                    } else {
                        try {
                            String result = backend.placePaperTrade(chatId, parts[1], parts[2], Double.parseDouble(parts[3]));
                            sendText(chatId, "PAPER TRADE EXECUTED\n" +
                                    "═══════════════════════\n\n" + result);
                        } catch (NumberFormatException e) {
                            sendText(chatId, "Invalid amount. Please use numbers only (e.g. 50, 100.50).");
                        }
                    }
                    break;

                default:
                    sendText(chatId, "NORT67 AI MARKET ANALYST\n\n" +
                            "Available commands:\n" +
                            "• /trending - Hottest markets by volume\n" +
                            "• /advice <id> - AI analysis for market\n" +
                            "• /signals - Algorithmic trading signals\n" +
                            "• /markets - Live market listings\n" +
                            "• /portfolio - Paper trading summary\n" +
                            "• /papertrade <id> yes/no <amount> - Simulate trades\n\n" +
                            "Type /start for interactive menu.");
            }
        }

        // Button callbacks
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

    public void sendMenu(long chatId) {
        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text("NORT67 AI MARKET ANALYST\n\n" +
                        "Real-time prediction market analysis powered by:\n" +
                        "• Live market data feeds\n" +
                        "• AI reasoning engine\n" +
                        "• Web intelligence gathering\n\n" +
                        "Paper trading environment - risk-free strategy testing\n\n" +
                        "Commands: /advice /signals /trending /portfolio")
                .build();

        InlineKeyboardMarkup markupInline = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rowsInline = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        row1.add(InlineKeyboardButton.builder().text("Trending Markets").callbackData("btn_trending").build());
        row1.add(InlineKeyboardButton.builder().text("AI Advice").callbackData("btn_advice").build());

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

    public void sendText(long chatId, String text) {
        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        try {
            execute(sm);
        } catch (TelegramApiException e) {
            System.err.println("Failed to send message: " + e.getMessage());
        }
    }
}
