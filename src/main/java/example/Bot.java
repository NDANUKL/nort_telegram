package example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import example.client.BackendClient;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONArray;

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
                    String trending = backend.getTrendingMarkets();
                    sendFormattedMarkets(chatId, trending, "TOP 20 TRENDING"); // ✅ Fixed
                    break;



                case "/advice":
                    String[] adviceParts = messageText.split(" ");
                    if (adviceParts.length < 2) {
                        sendText(chatId, "Usage: /advice <market_id>\nExample: /advice 527079");
                    } else {
                        String mktId = adviceParts[1];
                        sendText(chatId, "Analyzing market " + mktId + "...");
                        String rawAdvice = backend.getAIAdvice(mktId);

                        try {
                            org.json.JSONObject json = new org.json.JSONObject(rawAdvice);

                            // Flexible key mapping for all JSON fields
                            String marketId = json.optString("market_id", json.optString("marketid", mktId));
                            String summary = json.optString("summary", "");
                            String whyTrending = json.optString("why_trending", json.optString("whytrending", ""));
                            String plan = json.optString("suggested_plan", json.optString("suggestedplan", "WAIT"));
                            String disclaimer = json.optString("disclaimer", "");
                            double confidence = json.optDouble("confidence", 0.5);
                            String staleWarning = json.optString("stale_data_warning", json.optString("staledatawarning", ""));

                            // Parse all possible risk factors arrays
                            String riskList = "None listed";
                            try {
                                String riskKey = json.optString("risk_factors", json.optString("riskfactors", "[]"));
                                org.json.JSONArray risks = new org.json.JSONArray(riskKey);
                                if (risks.length() > 0) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < risks.length(); i++) {
                                        sb.append("- ").append(risks.getString(i)).append("\n");
                                    }
                                    riskList = sb.toString();
                                }
                            } catch (Exception ignored) {
                                riskList = json.optString("risk_factors", "Parse error");
                            }

                            // Full detailed output matching JSON structure
                            StringBuilder formatted = new StringBuilder();
                            formatted.append("Market Analysis: ").append(marketId).append("\n\n");
                            formatted.append("Summary:\n").append(summary).append("\n\n");
                            formatted.append("Why Trending:\n").append(whyTrending).append("\n\n");
                            formatted.append("Risk Factors:\n").append(riskList).append("\n\n");
                            formatted.append("Suggested Plan: ").append(plan).append("\n");
                            formatted.append("Confidence: ").append(String.format("%.0f", confidence * 100)).append("%\n\n");

                            if (!staleWarning.isEmpty()) {
                                formatted.append("Data Warning:\n").append(staleWarning).append("\n\n");
                            }

                            formatted.append(disclaimer);

                            sendText(chatId, formatted.toString());
                        } catch (Exception e) {
                            sendText(chatId, "Parse error: " + e.getMessage() + "\n\nRaw JSON:\n" +
                                    rawAdvice.substring(0, Math.min(2000, rawAdvice.length())));
                        }
                    }
                    break;


                case "/portfolio":
                    // [INTERN 5]: Fetching Paper Trade history from SQLite
                    sendText(chatId, "📂 **Portfolio Summary (Paper Mode)**\nBalance: $1,000.00\nActive Bets: 0");
                    break;

                case "/markets":
                    String allMarkets = backend.getMarkets();
                    sendFormattedMarkets(chatId, allMarkets, "TOP 50 MARKETS"); // ✅ Fixed
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
                String trending = backend.getTrendingMarkets();  // Correct endpoint
                sendFormattedMarkets(chatId, trending, "🔥 TOP 20 TRENDING");  // ✅ Formatted!
        }
            else if (callData.equals("btn_advice")) {
                // Triggering Intern 3's Agent Logic
                sendText(chatId, "Enter the following command /advice <market_id> for AI analysis:");
            }
            else if (callData.equals("btn_portfolio")) {
                // Triggering Intern 5's Paper Wallet
                sendText(chatId, "📂 Loading your paper trades...");
            }
        }
    }

    private void sendFormattedMarkets(Long chatId, String rawJson, String title) {
        try {
            JSONObject json = new JSONObject(rawJson);
            JSONArray markets = json.getJSONArray("markets");

            StringBuilder msg = new StringBuilder(title + ":\n\n");
            for (int i = 0; i < Math.min(10, markets.length()); i++) {
                JSONObject m = markets.getJSONObject(i);
                String summary = m.getString("summary");
                msg.append("• ").append(summary).append("\n\n");
            }
            sendText(chatId, msg.toString());
        } catch (Exception e) {
            sendText(chatId, title + ":\n" + rawJson.substring(0, 2000));
        }
    }

    // --- UI COMPONENT: MAIN MENU ---
    public void sendMenu(Long chatId) {
        SendMessage sm = SendMessage.builder()
                .chatId(chatId.toString())
                .text("Nort67 AI Assistant\nWelcome to the prediction market hub. Select a service:") // ❌ No Markdown
                .disableWebPagePreview(true) // ✅ Plain text
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

        // Row 3: The Dashboard (Intern 6)
        List<InlineKeyboardButton> row3 = new ArrayList<>();
        row3.add(InlineKeyboardButton.builder()
                .text("🖥️ Open Dashboard")
                .url("https://t.me/Nort67Bot/myapp") // Placeholder for Intern 6's TMA URL
                .build());

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

    // --- HELPER: BASIC TEXT SENDER ---
    public void sendText(Long chatId, String text) {
        // Truncate to safe 3000 chars
        if (text.length() > 3000) {
            text = text.substring(0, 3000) + "\n\n📄 (Truncated - too long)";
        }

        SendMessage sm = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                // ❌ NO PARSEMODE - plain text only
                .disableWebPagePreview(true)
                .build();

        try {
            execute(sm);
        } catch (TelegramApiException e) {
            System.err.println("Send failed: " + e.getMessage());
        }
    }

}