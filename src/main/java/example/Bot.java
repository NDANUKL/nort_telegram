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

            String command = messageText.split(" ")[0];

            switch (command) {
                case "/start":
                    sendMenu(chatId);
                    break;

                // ─────────────────────────────────────────────────────
                // /trending — Top 10 markets by volume
                // ─────────────────────────────────────────────────────
                case "/trending":
                    sendText(chatId, "Fetching top 10 trending markets...");
                    String trendingRaw = backend.getTrendingMarkets();
                    try {
                        JSONObject trendingJson = new JSONObject(trendingRaw);
                        JSONArray markets = trendingJson.getJSONArray("markets");
                        StringBuilder trending = new StringBuilder();
                        trending.append("TOP 10 TRENDING MARKETS\n");
                        trending.append("═══════════════════════════════════════\n\n");
                        for (int i = 0; i < markets.length(); i++) {
                            JSONObject m    = markets.getJSONObject(i);
                            String mid      = m.optString("id", "?");
                            String question = m.optString("question", "Unknown");
                            double volume   = m.optDouble("volume", 0);
                            double odds     = m.optDouble("current_odds", 0);
                            String expires  = m.optString("expires_at", "?").split(" ")[0];
                            String oddsStr  = odds > 0 ? String.format("%.0f%%", odds * 100) : "—";

                            trending.append(String.format("%d. %s\n", i + 1, question));
                            trending.append(String.format("   ID: %-10s  Volume: $%,.0f\n", mid, volume));
                            trending.append(String.format("   Odds: %-8s  Expires: %s\n", oddsStr, expires));
                            trending.append("\n");
                        }
                        trending.append("───────────────────────────────────────\n");
                        trending.append("Use /advice <id> for a full AI analysis.");
                        sendText(chatId, trending.toString());
                    } catch (Exception e) {
                        sendText(chatId, "Unable to retrieve trending markets at this time.");
                    }
                    break;

                // ─────────────────────────────────────────────────────
                // /advice — AI analysis for a specific market
                // ─────────────────────────────────────────────────────
                // ─────────────────────────────────────────────────────
// /advice — AI analysis for a specific market
// ─────────────────────────────────────────────────────
                case "/advice":
                    String[] adviceParts = messageText.split(" ");
                    if (adviceParts.length < 2) {
                        sendText(chatId, "Usage: /advice <market_id>\nExample: /advice 527079");
                    } else {
                        String mktId = adviceParts[1];
                        sendText(chatId, "Analyzing market " + mktId + "...\nGathering news, social intelligence, and AI signals.");
                        String rawAdvice = backend.getAIAdvice(mktId);

                        try {
                            JSONObject json     = new JSONObject(rawAdvice);
                            String marketId     = json.optString("market_id", mktId);
                            String summary      = json.optString("summary", "");
                            String whyTrending  = json.optString("why_trending", "");
                            String plan         = json.optString("suggested_plan", "WAIT");
                            String disclaimer   = json.optString("disclaimer", "This is not financial advice.");
                            double confidence   = json.optDouble("confidence", 0.5);
                            String staleWarning = json.optString("stale_data_warning", "");

                            // Confidence percentage only (no bar)
                            int pct    = (int) Math.round(confidence * 100);
                            String confidenceLabel = pct >= 75 ? "HIGH" : pct >= 50 ? "MEDIUM" : "LOW";

                            // Risk factors
                            String riskList = "None identified.";
                            try {
                                JSONArray risks = json.optJSONArray("risk_factors");
                                if (risks != null && risks.length() > 0) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < risks.length(); i++)
                                        sb.append("  - ").append(risks.getString(i)).append("\n");
                                    riskList = sb.toString().trim();
                                }
                            } catch (Exception ignored) {}

                            // Search queries used
                            String searchedFor = "";
                            try {
                                JSONArray tools = json.optJSONArray("tool_calls_used");
                                if (tools != null) {
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < tools.length(); i++) {
                                        String entry = tools.getString(i);
                                        if (entry.startsWith("tavily_news:") || entry.startsWith("tavily_social:")) {
                                            String query = entry.substring(entry.indexOf(":") + 1).trim()
                                                    .replaceAll("\"", "").replaceAll(" OR .*", "").trim();
                                            sb.append("  - ").append(query).append("\n");
                                        }
                                    }
                                    if (sb.length() > 0) searchedFor = sb.toString().trim();
                                }
                            } catch (Exception ignored) {}

                            // Message 1 — Analysis
                            StringBuilder part1 = new StringBuilder();
                            part1.append("MARKET ANALYSIS  #").append(marketId).append("\n");
                            part1.append("═══════════════════════════════════════\n\n");
                            part1.append("SUMMARY\n");
                            part1.append(summary).append("\n\n");
                            part1.append("WHY TRENDING\n");
                            part1.append(whyTrending).append("\n\n");
                            part1.append("RISK FACTORS\n");
                            part1.append(riskList);

                            part1.append("\n\nRECOMMENDED ACTION\n");
                            part1.append(plan).append("\n\n");
                            part1.append("CONFIDENCE\n");
                            part1.append(pct).append("%  ").append(confidenceLabel).append("\n\n");
                            if (!staleWarning.isEmpty()) {
                                part1.append("DATA NOTE\n");
                                part1.append(staleWarning).append("\n\n");
                            }
                            part1.append("═══════════════════════════════════════\n");
                            part1.append(disclaimer).append("\n");
                            part1.append("Powered by live web intelligence and AI reasoning.");

                            sendText(chatId, part1.toString());

                        } catch (Exception e) {
                            sendText(chatId, "Unable to parse response.\n\nPreview:\n" +
                                    rawAdvice.substring(0, Math.min(1000, rawAdvice.length())));
                        }
                    }
                    break;


                // ─────────────────────────────────────────────────────
                // /signals — Top 10 AI signals
                // ─────────────────────────────────────────────────────
                case "/signals":
                    sendText(chatId, "Analyzing market momentum...");
                    String signalsRaw = backend.getSignals();
                    try {
                        JSONArray signalList = new JSONArray(signalsRaw);
                        StringBuilder signals = new StringBuilder();
                        signals.append("TOP 10 MARKET SIGNALS\n");
                        signals.append("═══════════════════════════════════════\n\n");
                        for (int i = 0; i < signalList.length(); i++) {
                            JSONObject s    = signalList.getJSONObject(i);
                            String mid      = s.optString("market_id", "?");
                            String question = s.optString("question", "Unknown");
                            double score    = s.optDouble("score", 0);
                            String reason   = s.optString("reason", "");
                            double volume   = s.optDouble("volume", 0);
                            double odds     = s.optDouble("current_odds", 0);
                            String oddsStr  = odds > 0 ? String.format("%.0f%%", odds * 100) : "—";

                            int scorePct = (int) Math.round(score * 100);

                            signals.append(String.format("%d. %s\n", i + 1, question));
                            signals.append(String.format("   ID: %-10s  Score: %d%%\n", mid, scorePct));
                            signals.append(String.format("   Volume: $%,.0f  |  Odds: %s\n", volume, oddsStr));
                            signals.append(String.format("   Note: %s\n", reason));
                            signals.append("\n");
                        }
                        signals.append("───────────────────────────────────────\n");
                        signals.append("Use /advice <id> to get a full AI analysis.");
                        sendText(chatId, signals.toString());
                    } catch (Exception e) {
                        sendText(chatId, "Unable to retrieve signals at this time.");
                    }
                    break;

                case "/portfolio":
                    sendText(chatId, "PORTFOLIO SUMMARY\n" +
                            "═══════════════════════════════════════\n" +
                            "Mode:             Paper Trading\n" +
                            "Account Balance:  $1,000.00\n" +
                            "Active Positions: 0\n" +
                            "Total PNL:        $0.00\n" +
                            "Win Rate:         N/A\n\n" +
                            "No real funds at risk.");
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

                case "/papertrade":
                    String[] parts = messageText.split(" ");
                    if (parts.length < 4) {
                        sendText(chatId, "PAPER TRADE ORDER\n" +
                                "═══════════════════════════════════════\n" +
                                "Usage:   /papertrade <market_id> <yes/no> <amount>\n" +
                                "Example: /papertrade 527079 yes 50\n\n" +
                                "Simulates trades without real capital at risk.");
                    } else {
                        try {
                            String result = backend.placePaperTrade(chatId, parts[1], parts[2], Double.parseDouble(parts[3]));
                            sendText(chatId, "PAPER TRADE EXECUTED\n" +
                                    "═══════════════════════\n\n" + result);
                        } catch (NumberFormatException e) {
                            sendText(chatId, "Invalid amount. Please enter a numeric value (e.g. 50 or 100.50).");
                        }
                    }
                    break;

                default:
                    sendText(chatId, "NORT  —  AI Prediction Market Analyst\n\n" +
                            "Commands\n" +
                            "───────────────────────────────────────\n" +
                            "/trending          Top 10 markets by volume\n" +
                            "/signals           Top 10 AI trading signals\n" +
                            "/advice <id>       Full AI analysis for a market\n" +
                            "/markets           Live market listings\n" +
                            "/portfolio         Paper trading summary\n" +
                            "/papertrade        Simulate a trade\n\n" +
                            "Type /start for the interactive menu.");
            }
        }

        // Button callbacks
        else if (update.hasCallbackQuery()) {
            String callData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();

            switch (callData) {
                case "btn_trending":
                    String trendingRaw2 = backend.getTrendingMarkets();
                    try {
                        JSONObject trendingJson2 = new JSONObject(trendingRaw2);
                        JSONArray markets2 = trendingJson2.getJSONArray("markets");
                        StringBuilder trending2 = new StringBuilder();
                        trending2.append("TOP 10 TRENDING MARKETS\n");
                        trending2.append("═══════════════════════════════════════\n\n");
                        for (int i = 0; i < markets2.length(); i++) {
                            JSONObject m    = markets2.getJSONObject(i);
                            String mid      = m.optString("id", "?");
                            String question = m.optString("question", "Unknown");
                            double volume   = m.optDouble("volume", 0);
                            double odds     = m.optDouble("current_odds", 0);
                            String expires  = m.optString("expires_at", "?").split(" ")[0];
                            String oddsStr  = odds > 0 ? String.format("%.0f%%", odds * 100) : "—";
                            trending2.append(String.format("%d. %s\n", i + 1, question));
                            trending2.append(String.format("   ID: %s  |  $%,.0f  |  %s  |  %s\n\n", mid, volume, oddsStr, expires));
                        }
                        trending2.append("Use /advice <id> for a full AI analysis.");
                        sendText(chatId, trending2.toString());
                    } catch (Exception e) {
                        sendText(chatId, "Unable to retrieve trending markets at this time.");
                    }
                    break;

                case "btn_advice":
                    sendText(chatId, "AI MARKET ANALYSIS\n" +
                            "═══════════════════════════════════════\n" +
                            "Usage:   /advice <market_id>\n" +
                            "Example: /advice 527079\n\n" +
                            "Returns a comprehensive AI-powered analysis\n" +
                            "including news, sentiment, risk factors,\n" +
                            "and a recommended trading action.");
                    break;

                case "btn_portfolio":
                    sendText(chatId, "PORTFOLIO SUMMARY\n" +
                            "═══════════════════════════════════════\n" +
                            "Mode:             Paper Trading\n" +
                            "Account Balance:  $1,000.00\n" +
                            "Active Positions: 0\n" +
                            "Total PNL:        $0.00");
                    break;
            }
        }
    }

    public void sendMenu(long chatId) {
        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text("NORT  —  AI Prediction Market Analyst\n\n" +
                        "Real-time market intelligence powered by\n" +
                        "live data, web search, and AI reasoning.\n\n" +
                        "Paper trading environment — no real capital at risk.\n\n" +
                        "Select an option below or use a command directly.")
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
        // Telegram enforces a 4096 character limit per message
        if (text.length() > 4096) {
            text = text.substring(0, 4090) + "\n[...]";
        }
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