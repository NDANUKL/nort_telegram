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
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            // x402: intercept 64-char hex tx hash replies before command parsing
            if (messageText.matches("[0-9a-fA-F]{64}")) {
                String verifyResult = backend.verifyPayment(messageText, chatId);
                try {
                    JSONObject verifyJson = new JSONObject(verifyResult);
                    if (verifyJson.getBoolean("success")) {
                        sendText(chatId, "✅ Payment verified! Unlocking premium advice...");
                        String unlocked = backend.getPremiumAdvice("last_market_id", chatId);
                        JSONObject unlockedJson = new JSONObject(unlocked);
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
                    String marketData = backend.getTrendingMarkets();
                    if (marketData == null || marketData.trim().isEmpty())
                        marketData = "No trending markets available at this time.";
                    sendText(chatId, "TRENDING MARKETS\n═══════════════════════\n\n" + marketData);
                    break;

                case "/advice":
                    String[] adviceParts = messageText.split(" ");
                    if (adviceParts.length < 2) {
                        sendText(chatId, "Usage: /advice <market_id>\nExample: /advice 527079");
                    } else {
                        String mktId = adviceParts[1];
                        sendText(chatId, "Analyzing market " + mktId + "...");
                        String premiumResponse = backend.getPremiumAdvice(mktId, chatId);

                        // Guard: backend unavailable
                        if (premiumResponse.contains("503") || premiumResponse.startsWith("Connection failed") || premiumResponse.startsWith("Error 5")) {
                            sendText(chatId, "⚠️ The backend is currently waking up or unavailable. Please wait 30 seconds and try again.");
                            break;
                        }

                        try {
                            JSONObject json = new JSONObject(premiumResponse);

                            // Structured advice response
                            if (json.has("summary")) {
                                String marketId  = json.optString("market_id", mktId);
                                String summary   = json.optString("summary", "");
                                String why       = json.optString("why_trending", "");
                                String plan      = json.optString("suggested_plan", "WAIT");
                                String disclaimer = json.optString("disclaimer", "This is not financial advice.");
                                double confidence = json.optDouble("confidence", 0.5);
                                String staleWarn = json.optString("stale_data_warning", "");

                                String riskList = "None listed";
                                try {
                                    Object risksObj = json.opt("risk_factors");
                                    if (risksObj instanceof JSONArray) {
                                        JSONArray risks = (JSONArray) risksObj;
                                        StringBuilder sb = new StringBuilder();
                                        for (int i = 0; i < risks.length(); i++)
                                            sb.append("• ").append(risks.getString(i)).append("\n");
                                        riskList = sb.toString().trim();
                                    }
                                } catch (Exception ignored) {}

                                StringBuilder msg = new StringBuilder();
                                msg.append("💎 MARKET ANALYSIS: ").append(marketId).append("\n\n");
                                msg.append("SUMMARY\n").append(summary).append("\n\n");
                                msg.append("TREND ANALYSIS\n").append(why).append("\n\n");
                                msg.append("RISK FACTORS\n").append(riskList).append("\n\n");
                                msg.append("RECOMMENDED ACTION: ").append(plan).append("\n");
                                msg.append("CONFIDENCE: ").append(String.format("%.0f%%", confidence * 100)).append("\n");
                                if (!staleWarn.isEmpty())
                                    msg.append("\n⚠️ DATA WARNING: ").append(staleWarn).append("\n");
                                msg.append("\n═══════════════════════\n_").append(disclaimer).append("_");
                                sendText(chatId, msg.toString());

                            } else {
                                sendText(chatId, "💎 *Premium Content:*\n" + json.getString("content"));
                            }

                        } catch (Exception e) {
                            // Check if it's a 402 payment required response
                            if (premiumResponse.contains("402") || premiumResponse.contains("PAYMENT-REQUIRED")) {
                                try {
                                    JSONObject paymentJson = new JSONObject(premiumResponse);
                                    double amount  = paymentJson.getDouble("amount");
                                    String address = paymentJson.getString("address");
                                    String asset   = paymentJson.getString("asset");
                                    sendText(chatId, "💎 *Premium Content Locked*\n\nTo unlock, send $"
                                        + amount + " " + asset + " to:\n`" + address + "`\non Base network.\n\nReply with your transaction hash.");
                                } catch (Exception ex) {
                                    sendText(chatId, "Payment required, but could not parse payment details.\nRaw: " + premiumResponse);
                                }
                            } else {
                                sendText(chatId, "Error: " + e.getMessage() + "\nRaw: " + premiumResponse.substring(0, Math.min(200, premiumResponse.length())));
                            }
                        }
                    }
                    break;

                case "/portfolio":
                    sendText(chatId, "PORTFOLIO SUMMARY (Paper Trading Mode)\n" +
                            "═══════════════════════════════════════\n" +
                            "Account Balance: $1,000.00\n" +
                            "Active Positions: 0\n" +
                            "Total PNL: $0.00\n" +
                            "Win Rate: N/A\n\n" +
                            "Paper trading environment - no real funds at risk.");
                    break;

                case "/markets":
                    sendText(chatId, "Fetching market data...");
                    String rawMarkets = backend.getMarkets();
                    if (rawMarkets == null || rawMarkets.trim().isEmpty())
                        rawMarkets = "No market data available at this time.";
                    sendText(chatId, "LIVE MARKETS\n═══════════════════════\n\n" + rawMarkets);
                    break;

                case "/signals":
                    sendText(chatId, "Analyzing market momentum...");
                    String signals = backend.getSignals();
                    if (signals == null || signals.trim().isEmpty())
                        signals = "No signals available.";
                    sendText(chatId, "MARKET SIGNALS RANKING\n═══════════════════════\n\n" + signals);
                    break;

                case "/papertrade":
                    String[] parts = messageText.split(" ");
                    if (parts.length < 4) {
                        sendText(chatId, "PAPER TRADE ORDER\n═══════════════════════\n" +
                                "Usage: /papertrade <market_id> <yes/no> <amount>\n" +
                                "Example: /papertrade 527079 yes 50\n\n" +
                                "Simulates trades without real money risk.");
                    } else {
                        try {
                            String result = backend.placePaperTrade(chatId, parts[1], parts[2], Double.parseDouble(parts[3]));
                            sendText(chatId, "PAPER TRADE EXECUTED\n═══════════════════════\n\n" + result);
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

            switch (callData) {
                case "btn_trending":
                    String response = backend.getTrendingMarkets();
                    sendText(chatId, response != null && !response.trim().isEmpty() ?
                            "TRENDING MARKETS\n═══════════════════════\n\n" + response :
                            "No trending market data available at this time.");
                    break;
                case "btn_advice":
                    sendText(chatId, "AI ADVICE\n═════════════\n" +
                            "Usage: /advice <market_id>\n" +
                            "Example: /advice 527079\n\n" +
                            "Get detailed AI-powered analysis for any market.");
                    break;
                case "btn_portfolio":
                    sendText(chatId, "PORTFOLIO SUMMARY (Paper Trading Mode)\n" +
                            "═══════════════════════════════════════\n" +
                            "Account Balance: $1,000.00\nActive Positions: 0\nTotal PNL: $0.00");
                    break;
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
        row2.add(InlineKeyboardButton.builder().text("Portfolio").callbackData("btn_portfolio").build());

        rowsInline.add(row1);
        rowsInline.add(row2);
        markupInline.setKeyboard(rowsInline);
        sm.setReplyMarkup(markupInline);

        try {
            execute(sm);
        } catch (TelegramApiException e) {
            System.err.println("Failed to send menu: " + e.getMessage());
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
