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
import java.util.concurrent.ConcurrentHashMap;

public class Bot extends TelegramLongPollingBot {

    private final BackendClient backend = new BackendClient();

    // Per-user session state, keyed by Telegram chatId
    private final ConcurrentHashMap<Long, UserSession> sessions = new ConcurrentHashMap<>();

    /** Returns the session for this user, creating one if it doesn't exist yet. */
    private UserSession session(long chatId) {
        return sessions.computeIfAbsent(chatId, id -> new UserSession());
    }

    @Override
    public String getBotUsername() { return "Nort67Bot"; }

    @Override
    public String getBotToken() {
        String token = System.getenv("BOT_TOKEN");
        if (token == null || token.isEmpty())
            throw new RuntimeException("BOT_TOKEN environment variable not set!");
        return token;
    }

    // ═══════════════════════════════════════════════════════════
    // MAIN UPDATE HANDLER
    // ═══════════════════════════════════════════════════════════

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText().trim();
            long   chatId      = update.getMessage().getChatId();
            String username    = update.getMessage().getFrom().getUserName();

            // x402: intercept 64-char hex tx hash replies
            if (messageText.matches("[0-9a-fA-F]{64}")) {
                handlePaymentProof(chatId, messageText);
                return;
            }

            // Split command from arguments
            String[] parts   = messageText.split("\\s+");
            String   command = parts[0].toLowerCase();

            switch (command) {
                // ── GROUP 1: ACCOUNT & SETUP ──────────────────────────────
                case "/start":    handleStart(chatId, username);          break;
                case "/authorize": handleAuthorize(chatId, parts);        break;
                case "/mode":     handleMode(chatId, parts);              break;

                // ── GROUP 2: MARKETS & SIGNALS ────────────────────────────
                case "/trending":  handleTrending(chatId);               break;
                case "/market":    handleMarketDetail(chatId, parts);    break;
                case "/signals":   handleSignals(chatId);                break;
                case "/markets":   handleMarkets(chatId);                break;

                // ── GROUP 3: PORTFOLIO & TRADING ──────────────────────────
                case "/wallet":
                case "/portfolio": handleWallet(chatId);                  break;
                case "/topup":    handleTopup(chatId, parts);             break;
                case "/positions": handlePositions(chatId);               break;
                case "/trade":    handleTrade(chatId, parts);             break;
                case "/close":    handleClose(chatId, parts);             break;

                // ── GROUP 4: AI ADVICE ────────────────────────────────────
                case "/advice":         handleAdvice(chatId, parts, false); break;
                case "/premium_advice": handleAdvice(chatId, parts, true);  break;

                // ── GROUP 5: SOCIAL & COPY TRADING ────────────────────────
                case "/leaders":     handleLeaders(chatId);               break;
                case "/copy":        handleCopy(chatId, parts);           break;
                case "/uncopy":      handleUncopy(chatId, parts);         break;
                case "/myfollowers": handleMyFollowers(chatId);           break;

                default:
                    sendHelp(chatId);
            }
        } else if (update.hasCallbackQuery()) {
            handleCallback(
                update.getCallbackQuery().getData(),
                update.getCallbackQuery().getMessage().getChatId()
            );
        }
    }

    // ═══════════════════════════════════════════════════════════
    // GROUP 1 — ACCOUNT & SETUP
    // ═══════════════════════════════════════════════════════════

    /**
     * /start
     *
     * First-time users: registers them via POST /wallet/connect,
     * sets session.registered = true, shows onboarding instructions.
     * Returning users: shows their current status and command menu.
     */
    private void handleStart(long chatId, String username) {
        UserSession s = session(chatId);

        if (!s.isRegistered()) {
            // First time — register with backend
            sendText(chatId, "⏳ Setting up your NORT account...");
            String result = backend.registerUser(chatId, username);

            // Parse wallet address from response if available
            String walletAddress = "";
            try {
                JSONObject json = new JSONObject(result);
                walletAddress = json.optString("wallet_address", "");
                s.setRegistered(true);
            } catch (Exception e) {
                // Backend may be down — still mark registered locally so they can proceed
                s.setRegistered(true);
            }

            StringBuilder msg = new StringBuilder();
            msg.append("👋 *Welcome to NORT!*\n\n");
            msg.append("Your account has been created and a Base wallet has been set up for you.\n");
            if (!walletAddress.isEmpty())
                msg.append("🏦 Wallet: `").append(walletAddress).append("`\n");
            msg.append("\n");
            msg.append("*Before you can use premium advice, complete setup:*\n\n");
            msg.append("*Step 1 — Authorize spending:*\n");
            msg.append("`/authorize <max_usdc>` — sets how much USDC the bot can spend on your behalf.\n");
            msg.append("Example: `/authorize 5` allows up to $5 USDC for premium advice.\n\n");
            msg.append("*Step 2 — Choose your mode:*\n");
            msg.append("`/mode PAPER` — simulate trades with no real money (default)\n");
            msg.append("`/mode REAL` — execute real trades on-chain via your Base wallet\n\n");
            msg.append("─────────────────────────\n");
            msg.append("Type `/help` or any unknown command to see all available commands.");
            sendMarkdown(chatId, msg.toString());

        } else {
            // Returning user — show their current status
            UserSession sess = s;
            StringBuilder msg = new StringBuilder();
            msg.append("👋 *Welcome back to NORT!*\n\n");
            msg.append("📊 *Your Status:*\n");
            msg.append("• Mode: ").append(sess.modeLabel()).append("\n");
            msg.append("• Authorization: ").append(sess.isAuthorized()
                ? "✅ Set ($" + String.format("%.2f", sess.getMaxUsdc()) + " limit)"
                : "⚠️ Not set — run `/authorize <max_usdc>`").append("\n\n");
            msg.append("Use the menu below or type any command to get started.");
            sendMarkdown(chatId, msg.toString());
            sendMenu(chatId);
        }
    }

    /**
     * /authorize <max_usdc>
     *
     * Sets the user's ERC-20 USDC spending allowance. This is required
     * before /premium_advice can deduct funds. Stored both locally in
     * session and on the backend via POST /wallet/authorize.
     *
     * Example: /authorize 5  → allows up to $5 USDC per session
     */
    private void handleAuthorize(long chatId, String[] parts) {
        if (parts.length < 2) {
            sendMarkdown(chatId,
                "⚙️ *Authorize Spending*\n\n" +
                "Usage: `/authorize <max_usdc>`\n\n" +
                "Sets the maximum USDC the bot can spend on your behalf for premium advice.\n\n" +
                "Example: `/authorize 5` — allows up to $5 USDC\n\n" +
                "💡 You can update this at any time by running `/authorize` again."
            );
            return;
        }

        double maxUsdc;
        try {
            maxUsdc = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            sendText(chatId, "❌ Invalid amount. Please use a number. Example: `/authorize 5`");
            return;
        }

        if (maxUsdc <= 0) {
            sendText(chatId, "❌ Amount must be greater than 0. Example: `/authorize 5`");
            return;
        }

        sendText(chatId, "⏳ Setting spending authorization on-chain...");
        String result = backend.authorizeSpending(chatId, maxUsdc);

        // Update local session regardless of backend response
        // (backend may be setting this on-chain asynchronously)
        UserSession s = session(chatId);
        s.setAuthorized(true);
        s.setMaxUsdc(maxUsdc);

        StringBuilder msg = new StringBuilder();
        msg.append("✅ *Authorization Set*\n\n");
        msg.append("You have authorized NORT to spend up to *$").append(String.format("%.2f", maxUsdc)).append(" USDC*");
        msg.append(" on your behalf.\n\n");
        msg.append("This allowance covers `/premium_advice` calls (0.10 USDC each).\n\n");

        // Check if backend returned a tx hash for the on-chain allowance
        try {
            JSONObject json = new JSONObject(result);
            String txHash = json.optString("tx_hash", "");
            if (!txHash.isEmpty())
                msg.append("🔗 On-chain allowance tx: `").append(txHash).append("`\n\n");
        } catch (Exception ignored) {}

        msg.append("You're ready to use `/premium_advice`! 💎");
        sendMarkdown(chatId, msg.toString());
    }

    /**
     * /mode <PAPER|REAL>
     *
     * Switches the user's trading mode.
     * PAPER = all trades are simulated, no real funds move.
     * REAL  = trades execute on-chain via CDP wallet on Base.
     *
     * The mode is stored locally in session AND synced to the backend
     * via PATCH /permissions so it persists across bot restarts.
     */
    private void handleMode(long chatId, String[] parts) {
        if (parts.length < 2) {
            UserSession s = session(chatId);
            sendMarkdown(chatId,
                "⚙️ *Trading Mode*\n\n" +
                "Current mode: *" + s.modeLabel() + "*\n\n" +
                "Usage: `/mode PAPER` or `/mode REAL`\n\n" +
                "🟢 *PAPER* — Simulate trades with a virtual $1,000 balance. No real funds at risk.\n" +
                "🔴 *REAL* — Execute real trades on-chain via your Base wallet. Real USDC is at stake."
            );
            return;
        }

        String modeArg = parts[1].toUpperCase();
        if (!modeArg.equals("PAPER") && !modeArg.equals("REAL")) {
            sendText(chatId, "❌ Invalid mode. Use `/mode PAPER` or `/mode REAL`");
            return;
        }

        UserSession s = session(chatId);
        UserSession.TradingMode newMode = modeArg.equals("REAL")
            ? UserSession.TradingMode.REAL
            : UserSession.TradingMode.PAPER;

        // Extra confirmation gate before enabling REAL mode
        if (newMode == UserSession.TradingMode.REAL && !s.isAuthorized()) {
            sendMarkdown(chatId,
                "⚠️ *Authorization Required*\n\n" +
                "You must run `/authorize <max_usdc>` before enabling REAL mode.\n\n" +
                "This sets your spending limit and is required to protect you from unintended charges.\n\n" +
                "Example: `/authorize 10` then `/mode REAL`"
            );
            return;
        }

        // Sync to backend
        backend.setTradingMode(chatId, modeArg);

        // Update local session
        s.setMode(newMode);

        if (newMode == UserSession.TradingMode.REAL) {
            sendMarkdown(chatId,
                "🔴 *REAL Mode Activated*\n\n" +
                "⚠️ *Warning:* Your trades will now execute on-chain using real USDC from your Base wallet.\n\n" +
                "• Max spend authorized: *$" + String.format("%.2f", s.getMaxUsdc()) + " USDC*\n" +
                "• To switch back: `/mode PAPER`\n\n" +
                "_Trade carefully. On-chain transactions are irreversible._"
            );
        } else {
            sendMarkdown(chatId,
                "🟢 *PAPER Mode Activated*\n\n" +
                "You're now in paper trading mode. All trades are simulated with a virtual balance.\n" +
                "No real funds are at risk.\n\n" +
                "To switch to real trading: `/mode REAL`"
            );
        }
    }

    // ═══════════════════════════════════════════════════════════
    // GROUP 2 — MARKETS & SIGNALS (Fast Path)
    // ═══════════════════════════════════════════════════════════

    /**
     * /trending
     *
     * Returns the top markets by 24hr volume across crypto and sports.
     * Calls GET /markets?limit=10&sort_by=volume and formats the JSON
     * array into a numbered list of market cards.
     */
    private void handleTrending(long chatId) {
        sendText(chatId, "📈 Fetching trending markets...");
        String raw = backend.getTrendingMarkets();

        if (isError(raw)) {
            sendText(chatId, "⚠️ Could not fetch trending markets. Please try again shortly.");
            return;
        }

        try {
            // Response may be a JSON array or wrapped object
            StringBuilder msg = new StringBuilder();
            msg.append("📈 *Trending Markets*\n");
            msg.append("Top 10 by 24hr volume\n");
            msg.append("─────────────────────────\n\n");

            JSONArray markets;
            try {
                markets = new JSONArray(raw);
            } catch (Exception e) {
                JSONObject obj = new JSONObject(raw);
                markets = obj.optJSONArray("markets");
                if (markets == null) markets = obj.optJSONArray("data");
            }

            if (markets == null || markets.length() == 0) {
                sendText(chatId, "No trending markets available at this time.");
                return;
            }

            for (int i = 0; i < markets.length(); i++) {
                JSONObject m = markets.getJSONObject(i);
                String id       = m.optString("id", "—");
                String question = m.optString("question", m.optString("title", "Unknown market"));
                double odds     = m.optDouble("current_odds", m.optDouble("outcomePrices", -1));
                double volume   = m.optDouble("volume", 0);
                String category = m.optString("category", "—");

                msg.append("*").append(i + 1).append(".* ").append(question).append("\n");
                msg.append("   🆔 `").append(id).append("`");
                if (odds >= 0) msg.append("  |  YES: ").append(String.format("%.0f%%", odds * 100));
                if (volume > 0) msg.append("  |  Vol: $").append(formatVolume(volume));
                msg.append("  |  ").append(category).append("\n\n");
            }

            msg.append("─────────────────────────\n");
            msg.append("Use `/market <id>` for full detail or `/advice <id>` for AI analysis.");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            // Backend returned plain text — show as-is
            sendText(chatId, "📈 TRENDING MARKETS\n═══════════════════════\n\n" + raw);
        }
    }

    /**
     * /market <id>
     *
     * Returns full detail for a single market: question, current odds,
     * volume, category, and expiry date.
     * Calls GET /market/{id}.
     */
    private void handleMarketDetail(long chatId, String[] parts) {
        if (parts.length < 2) {
            sendMarkdown(chatId,
                "📋 *Market Detail*\n\n" +
                "Usage: `/market <market_id>`\n\n" +
                "Returns the full detail for a single market — question, odds, volume, category, and expiry.\n\n" +
                "Example: `/market 527079`\n\n" +
                "💡 Use `/trending` or `/signals` to find market IDs."
            );
            return;
        }

        String marketId = parts[1];
        session(chatId).setLastMarketId(marketId);

        sendText(chatId, "🔎 Fetching market " + marketId + "...");
        String raw = backend.getMarketDetail(marketId);

        if (isError(raw)) {
            sendMarkdown(chatId,
                "❌ Could not find market `" + marketId + "`.\n\n" +
                "Check the ID is correct — use `/trending` or `/signals` to browse available markets."
            );
            return;
        }

        try {
            JSONObject m = new JSONObject(raw);

            String id        = m.optString("id", marketId);
            String question  = m.optString("question", m.optString("title", "Unknown market"));
            double odds      = m.optDouble("current_odds", m.optDouble("outcomePrices", -1));
            double prevOdds  = m.optDouble("previous_odds", -1);
            double volume    = m.optDouble("volume", 0);
            double avgVol    = m.optDouble("avg_volume", 0);
            String category  = m.optString("category", "—");
            String expiresAt = m.optString("expires_at", m.optString("endDate", "—"));
            boolean active   = m.optBoolean("is_active", true);

            StringBuilder msg = new StringBuilder();
            msg.append("📋 *Market Detail*\n");
            msg.append("─────────────────────────\n\n");
            msg.append("*").append(question).append("*\n\n");
            msg.append("🆔 ID: `").append(id).append("`\n");
            msg.append("📂 Category: ").append(category).append("\n");
            msg.append("🟢 Status: ").append(active ? "Active" : "Closed").append("\n\n");

            if (odds >= 0) {
                msg.append("📊 *Current Odds*\n");
                msg.append("• YES: ").append(String.format("%.1f%%", odds * 100));
                msg.append("  |  NO: ").append(String.format("%.1f%%", (1 - odds) * 100)).append("\n");
                if (prevOdds >= 0) {
                    double change = (odds - prevOdds) * 100;
                    String arrow = change > 0 ? "↑" : change < 0 ? "↓" : "→";
                    msg.append("• Movement: ").append(arrow)
                       .append(String.format(" %.1f%%", Math.abs(change))).append(" vs last snapshot\n");
                }
                msg.append("\n");
            }

            msg.append("💰 *Volume*\n");
            msg.append("• 24hr: $").append(formatVolume(volume)).append("\n");
            if (avgVol > 0)
                msg.append("• Avg: $").append(formatVolume(avgVol)).append("\n");
            msg.append("\n");

            if (!expiresAt.equals("—")) {
                // Trim to date only if it's an ISO timestamp
                String expiry = expiresAt.length() > 10 ? expiresAt.substring(0, 10) : expiresAt;
                msg.append("📅 Expires: ").append(expiry).append("\n\n");
            }

            msg.append("─────────────────────────\n");
            msg.append("`/advice ").append(id).append("` — Free AI analysis\n");
            msg.append("`/premium_advice ").append(id).append("` — Full analysis (0.10 USDC)");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            // Plain text fallback
            sendText(chatId, "📋 MARKET DETAIL\n═══════════════════════\n\n" + raw);
        }
    }

    /**
     * /signals
     *
     * Returns the top 20 ranked signals from the Signals Engine with
     * composite scores and plain-English reason strings.
     * Calls GET /signals?top=20.
     */
    private void handleSignals(long chatId) {
        sendText(chatId, "📊 Analyzing market momentum...");
        String raw = backend.getSignals();

        if (isError(raw)) {
            sendText(chatId, "⚠️ Could not fetch signals. Please try again shortly.");
            return;
        }

        try {
            JSONArray signals;
            try {
                signals = new JSONArray(raw);
            } catch (Exception e) {
                JSONObject obj = new JSONObject(raw);
                signals = obj.optJSONArray("signals");
                if (signals == null) signals = obj.optJSONArray("data");
            }

            if (signals == null || signals.length() == 0) {
                sendText(chatId, "No signals available at this time.");
                return;
            }

            StringBuilder msg = new StringBuilder();
            msg.append("📊 *Market Signals*\n");
            msg.append("Top ").append(signals.length()).append(" by composite score\n");
            msg.append("─────────────────────────\n\n");

            for (int i = 0; i < signals.length(); i++) {
                JSONObject s  = signals.getJSONObject(i);
                String id     = s.optString("market_id", s.optString("id", "—"));
                String q      = s.optString("question", s.optString("market_question", "Unknown"));
                double score  = s.optDouble("composite_score", s.optDouble("score", 0));
                double odds   = s.optDouble("current_odds", -1);
                String reason = s.optString("reason", s.optString("analysis_summary", ""));

                // Score bar (visual indicator out of 5 blocks)
                int filled = (int) Math.round(score * 5);
                String bar = "█".repeat(Math.max(0, filled)) + "░".repeat(Math.max(0, 5 - filled));

                msg.append("*#").append(i + 1).append("* ").append(truncate(q, 60)).append("\n");
                msg.append("   Score: `").append(bar).append("` ").append(String.format("%.2f", score));
                if (odds >= 0)
                    msg.append("  |  YES: ").append(String.format("%.0f%%", odds * 100));
                msg.append("\n");
                if (!reason.isEmpty())
                    msg.append("   _").append(truncate(reason, 80)).append("_\n");
                msg.append("   `").append(id).append("`\n\n");
            }

            msg.append("─────────────────────────\n");
            msg.append("Use `/market <id>` for detail or `/advice <id>` for AI analysis.");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            sendText(chatId, "📊 SIGNALS\n═══════════════════════\n\n" + raw);
        }
    }

    /** Hidden /markets command — raw list, kept for debugging. */
    private void handleMarkets(long chatId) {
        sendText(chatId, "Fetching markets...");
        String raw = backend.getMarkets();
        sendText(chatId, isError(raw) ? "No market data available." : raw);
    }

    // ─── Helpers ─────────────────────────────────────────────

    /** Returns true if the backend response looks like an error. */
    private boolean isError(String response) {
        return response == null
            || response.trim().isEmpty()
            || response.startsWith("Error")
            || response.startsWith("Connection failed");
    }

    /** Formats a large volume number into a readable string (e.g. 1200000 → "1.2M"). */
    private String formatVolume(double v) {
        if (v >= 1_000_000) return String.format("%.1fM", v / 1_000_000);
        if (v >= 1_000)     return String.format("%.1fK", v / 1_000);
        return String.format("%.0f", v);
    }

    /** Truncates a string to maxLen characters, appending "…" if cut. */
    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 1) + "…";
    }

    // ═══════════════════════════════════════════════════════════
    // GROUP 3 — PORTFOLIO & TRADING (Fast Path)
    // ═══════════════════════════════════════════════════════════

    /**
     * /wallet  (also aliased as /portfolio)
     *
     * Returns the user's paper wallet summary: current balance,
     * net P&L, total trade count, and open positions count.
     * Calls GET /wallet/summary?telegram_user_id={id}.
     */
    private void handleWallet(long chatId) {
        sendText(chatId, "💼 Fetching your wallet...");
        String raw = backend.getWalletSummary(chatId);

        if (isError(raw)) {
            sendMarkdown(chatId,
                "⚠️ Could not retrieve wallet. Make sure you've run `/start` first."
            );
            return;
        }

        try {
            JSONObject w = new JSONObject(raw);

            double balance    = w.optDouble("paper_balance",    w.optDouble("balance",    1000.0));
            double deposited  = w.optDouble("total_deposited",  1000.0);
            double netPnl     = balance - deposited;
            int    totalTrades = w.optInt("total_trades",  0);
            int    openTrades  = w.optInt("open_positions", 0);
            String mode        = session(chatId).modeLabel();

            String pnlSign   = netPnl >= 0 ? "+" : "";
            String pnlEmoji  = netPnl >= 0 ? "📈" : "📉";

            StringBuilder msg = new StringBuilder();
            msg.append("💼 *Wallet Summary*\n");
            msg.append("─────────────────────────\n\n");
            msg.append("💵 *Balance:* $").append(String.format("%.2f", balance)).append(" USDC\n");
               .append(String.format("$%.2f", netPnl)).append("\n");
            msg.append("📊 *Trades:* ").append(totalTrades).append(" total");
            if (openTrades > 0)
                msg.append("  |  ").append(openTrades).append(" open");
            msg.append("\n");
            msg.append("⚙️ *Mode:* ").append(mode).append("\n\n");

            // Show open positions inline if there are any
            JSONArray positions = w.optJSONArray("open_positions_list");
            if (positions != null && positions.length() > 0) {
                msg.append("*Open Positions:*\n");
                for (int i = 0; i < Math.min(positions.length(), 5); i++) {
                    JSONObject p   = positions.getJSONObject(i);
                    String mktId   = p.optString("market_id", "—");
                    String outcome = p.optString("outcome", "—");
                    double cost    = p.optDouble("total_cost", 0);
                    double mtm     = p.optDouble("mark_to_market", cost);
                    double pnl     = mtm - cost;
                    String sign    = pnl >= 0 ? "+" : "";
                    msg.append("• `").append(mktId).append("` ").append(outcome)
                       .append("  $").append(String.format("%.2f", cost))
                       .append("  (").append(sign).append(String.format("$%.2f", pnl)).append(")\n");
                }
                if (positions.length() > 5)
                    msg.append("_...and ").append(positions.length() - 5).append(" more. Use `/positions` for full list._\n");
                msg.append("\n");
            }

            msg.append("─────────────────────────\n");
            msg.append("`/positions` — Full positions list\n");
            msg.append("`/trade <id> YES|NO <amount>` — Place a trade");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            sendText(chatId, "💼 WALLET\n═══════════════════════\n\n" + raw);
        }
    }

    /**
     * /topup <amount_kes>
     *
     * Initiates an M-Pesa STK push for the given KES amount.
     * The backend converts KES → USDC via live FX rate and triggers
     * the Daraja API. On confirmation, balance is credited.
     * Calls POST /topup.
     */
    private void handleTopup(long chatId, String[] parts) {
        if (parts.length < 2) {
            sendMarkdown(chatId,
                "💰 *Top Up via M-Pesa*\n\n" +
                "Usage: `/topup <amount_kes>`\n\n" +
                "Converts KES to USDC at the live exchange rate and credits your wallet.\n\n" +
                "Example: `/topup 500` — sends KSh 500 via M-Pesa\n\n" +
                "💡 Make sure your M-Pesa number is linked to your account."
            );
            return;
        }

        double amountKes;
        try {
            amountKes = Double.parseDouble(parts[1]);
        } catch (NumberFormatException e) {
            sendText(chatId, "❌ Invalid amount. Use a number. Example: `/topup 500`");
            return;
        }

        if (amountKes < 10) {
            sendText(chatId, "❌ Minimum top-up is KSh 10.");
            return;
        }

        sendText(chatId, "⏳ Initiating M-Pesa STK push for KSh " + String.format("%.0f", amountKes) + "...");
        String raw = backend.initiateTopup(chatId, amountKes);

        if (isError(raw)) {
            sendMarkdown(chatId,
                "❌ *Top-up failed*\n\n" +
                "Could not initiate M-Pesa payment. Please try again or contact support.\n\n" +
                "_Make sure your phone number is registered on your NORT account._"
            );
            return;
        }

        try {
            JSONObject json    = new JSONObject(raw);
            String status      = json.optString("status", "pending");
            double usdcAmount  = json.optDouble("usdc_amount", 0);
            double fxRate      = json.optDouble("fx_rate", 0);
            String checkoutId  = json.optString("checkout_request_id", "");

            StringBuilder msg = new StringBuilder();
            msg.append("📲 *M-Pesa STK Push Sent*\n\n");
            msg.append("Check your phone for a payment prompt.\n\n");
            msg.append("💵 Amount: *KSh ").append(String.format("%.0f", amountKes)).append("*\n");
            if (usdcAmount > 0)
                msg.append("🔄 Converts to: *$").append(String.format("%.4f", usdcAmount)).append(" USDC*\n");
            if (fxRate > 0)
                msg.append("📊 Rate: KSh ").append(String.format("%.2f", fxRate)).append(" / USDC\n");
            if (!checkoutId.isEmpty())
                msg.append("🆔 Ref: `").append(checkoutId).append("`\n");
            msg.append("\n");
            msg.append("Enter your M-Pesa PIN to confirm. Your USDC balance will be credited once the payment clears.");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            sendText(chatId, "📲 M-Pesa push initiated.\n\n" + raw);
        }
    }

    /**
     * /positions
     *
     * Lists all open paper trades with market question, direction
     * (YES/NO), entry price, shares, and current mark-to-market value.
     * Calls GET /trade/history?status=OPEN.
     */
    private void handlePositions(long chatId) {
        sendText(chatId, "📋 Fetching open positions...");
        String raw = backend.getOpenPositions(chatId);

        if (isError(raw)) {
            sendMarkdown(chatId, "⚠️ Could not fetch positions. Please try again shortly.");
            return;
        }

        try {
            JSONArray positions;
            try {
                positions = new JSONArray(raw);
            } catch (Exception e) {
                JSONObject obj = new JSONObject(raw);
                positions = obj.optJSONArray("trades");
                if (positions == null) positions = obj.optJSONArray("data");
            }

            if (positions == null || positions.length() == 0) {
                sendMarkdown(chatId,
                    "📋 *Open Positions*\n\n" +
                    "You have no open positions.\n\n" +
                    "Use `/trade <market_id> YES|NO <amount>` to place a trade."
                );
                return;
            }

            StringBuilder msg = new StringBuilder();
            msg.append("📋 *Open Positions* (").append(positions.length()).append(")\n");
            msg.append("─────────────────────────\n\n");

            for (int i = 0; i < positions.length(); i++) {
                JSONObject p    = positions.getJSONObject(i);
                String tradeId  = p.optString("id", p.optString("trade_id", "—"));
                String mktId    = p.optString("market_id", "—");
                String question = p.optString("market_question", truncate(mktId, 50));
                String outcome  = p.optString("outcome", "—");
                double shares   = p.optDouble("shares", 0);
                double entryPx  = p.optDouble("price_per_share", 0);
                double cost     = p.optDouble("total_cost",      shares * entryPx);
                double currOdds = p.optDouble("current_odds",    entryPx);
                double mtm      = shares * currOdds;
                double pnl      = mtm - cost;
                String pnlSign  = pnl >= 0 ? "+" : "";
                String pnlEmoji = pnl >= 0 ? "📈" : "📉";

                msg.append("*").append(i + 1).append(".* ").append(truncate(question, 55)).append("\n");
                msg.append("   ").append(outcome.equals("YES") ? "✅ YES" : "❌ NO");
                msg.append("  |  ").append(String.format("%.0f", shares)).append(" shares");
                msg.append("  |  Entry: ").append(String.format("%.0f%%", entryPx * 100)).append("\n");
                msg.append("   Cost: $").append(String.format("%.2f", cost));
                msg.append("  →  MtM: $").append(String.format("%.2f", mtm));
                msg.append("  ").append(pnlEmoji).append(" ").append(pnlSign).append(String.format("$%.2f", pnl)).append("\n");
                msg.append("   🆔 Trade: `").append(tradeId).append("`\n\n");
            }

            msg.append("─────────────────────────\n");
            msg.append("`/close <trade_id>` — Exit a position at current odds");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            sendText(chatId, "📋 POSITIONS\n═══════════════════════\n\n" + raw);
        }
    }

    /**
     * /trade <market_id> <YES|NO> <amount>
     *
     * Places a paper trade (PAPER mode) or a real on-chain trade (REAL mode).
     * PAPER → POST /papertrade
     * REAL  → POST /trade/real  (bridges USDC Base → Polygon, then CLOB)
     *
     * Example: /trade market-001 YES 50
     */
    private void handleTrade(long chatId, String[] parts) {
        if (parts.length < 4) {
            sendMarkdown(chatId,
                "💱 *Place a Trade*\n\n" +
                "Usage: `/trade <market_id> <YES|NO> <amount>`\n\n" +
                "Example: `/trade market-001 YES 50`\n\n" +
                "• `market_id` — from `/trending`, `/signals`, or `/market <id>`\n" +
                "• `YES` or `NO` — which outcome you're betting on\n" +
                "• `amount` — in USD (paper) or USDC (real)\n\n" +
                "Current mode: *" + session(chatId).modeLabel() + "*\n" +
                "_Use `/mode PAPER` or `/mode REAL` to switch._"
            );
            return;
        }

        String marketId = parts[1];
        String outcome  = parts[2].toUpperCase();
        double amount;

        if (!outcome.equals("YES") && !outcome.equals("NO")) {
            sendText(chatId, "❌ Outcome must be YES or NO. Example: `/trade market-001 YES 50`");
            return;
        }

        try {
            amount = Double.parseDouble(parts[3]);
        } catch (NumberFormatException e) {
            sendText(chatId, "❌ Invalid amount. Use a number. Example: `/trade market-001 YES 50`");
            return;
        }

        if (amount <= 0) {
            sendText(chatId, "❌ Amount must be greater than 0.");
            return;
        }

        UserSession s = session(chatId);
        boolean isReal = s.isRealMode();

        // REAL mode: extra safety checks
        if (isReal) {
            if (!s.isAuthorized()) {
                sendMarkdown(chatId,
                    "⚠️ *Authorization Required*\n\n" +
                    "Run `/authorize <max_usdc>` before placing real trades."
                );
                return;
            }
            if (amount > s.getMaxUsdc()) {
                sendMarkdown(chatId,
                    "⚠️ *Amount Exceeds Authorization*\n\n" +
                    "You authorized up to *$" + String.format("%.2f", s.getMaxUsdc()) + " USDC*.\n" +
                    "Requested: *$" + String.format("%.2f", amount) + "*\n\n" +
                    "Run `/authorize " + String.format("%.0f", amount + 5) + "` to raise your limit."
                );
                return;
            }
        }

        session(chatId).setLastMarketId(marketId);
        sendText(chatId, (isReal ? "🔴 " : "🟢 ") + "Placing " + (isReal ? "real" : "paper") +
                 " trade: " + outcome + " on " + marketId + " for $" + String.format("%.2f", amount) + "...");

        String raw = isReal
            ? backend.placeRealTrade(chatId, marketId, outcome, amount)
            : backend.placePaperTrade(chatId, marketId, outcome, amount);

        if (isError(raw)) {
            sendMarkdown(chatId,
                "❌ *Trade Failed*\n\n" +
                "Market not found or expired. Could not place the trade.\n\n" +
                "_Use `/market " + marketId + "` to check market status._"
            );
            return;
        }

        try {
            JSONObject json   = new JSONObject(raw);
            String tradeId    = json.optString("id", json.optString("trade_id", "—"));
            double shares     = json.optDouble("shares", amount / 0.5);
            double pricePer   = json.optDouble("price_per_share", 0.5);
            String txHash     = json.optString("tx_hash", "");

            StringBuilder msg = new StringBuilder();
            msg.append(isReal ? "🔴" : "🟢").append(" *Trade Executed*\n");
            msg.append("─────────────────────────\n\n");
            msg.append("📌 Market: `").append(marketId).append("`\n");
            msg.append("🎯 Direction: *").append(outcome).append("*\n");
            msg.append("💵 Amount: $").append(String.format("%.2f", amount)).append("\n");
            msg.append("📦 Shares: ").append(String.format("%.4f", shares)).append("\n");
            msg.append("💲 Entry price: ").append(String.format("%.0f%%", pricePer * 100)).append("\n");
            if (!txHash.isEmpty())
                msg.append("🔗 Tx: `").append(txHash).append("`\n");
            msg.append("🆔 Trade ID: `").append(tradeId).append("`\n\n");
            msg.append("─────────────────────────\n");
            msg.append("`/positions` — View all open positions\n");
            msg.append("`/close ").append(tradeId).append("` — Exit this position");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            sendText(chatId, (isReal ? "🔴" : "🟢") + " Trade placed.\n\n" + raw);
        }
    }

    /**
     * /close <trade_id>
     *
     * Exits an open position at the current market price.
     * P&L is calculated at close using live odds and stored in the DB.
     * Calls POST /trade/sell/{trade_id}.
     */
    private void handleClose(long chatId, String[] parts) {
        if (parts.length < 2) {
            sendMarkdown(chatId,
                "🔒 *Close a Position*\n\n" +
                "Usage: `/close <trade_id>`\n\n" +
                "Exits an open position at the current market odds.\n" +
                "P&L is calculated at close and recorded immediately.\n\n" +
                "Example: `/close abc123`\n\n" +
                "💡 Use `/positions` to see your open trade IDs."
            );
            return;
        }

        String tradeId = parts[1];
        sendText(chatId, "⏳ Closing position " + tradeId + "...");

        String raw = backend.closePosition(chatId, tradeId);

        if (isError(raw)) {
            sendMarkdown(chatId,
                "❌ *Could not close position*\n\n" +
                "Trade ID `" + tradeId + "` was not found or is already closed.\n\n" +
                "Use `/positions` to see your open trade IDs."
            );
            return;
        }

        try {
            JSONObject json  = new JSONObject(raw);
            String mktId     = json.optString("market_id",     "—");
            String outcome   = json.optString("outcome",       "—");
            double pnl       = json.optDouble("pnl",            0);
            double closePx   = json.optDouble("close_price",   0);
            double shares    = json.optDouble("shares",        0);
            String txHash    = json.optString("tx_hash",        "");

            String pnlSign  = pnl >= 0 ? "+" : "";
            String pnlEmoji = pnl >= 0 ? "📈" : "📉";

            // Fix 3: close confirmation — fetch question from trade record or use market ID
            String question = json.optString("market_question", json.optString("market_id", mktId));

            StringBuilder msg = new StringBuilder();
            msg.append("🔒 *Position Closed*\n");
            msg.append("─────────────────────────\n\n");
            msg.append("📌 *").append(truncate(question, 60)).append("*\n");
            msg.append("   `").append(mktId).append("`\n");
            msg.append("🎯 Outcome: *").append(outcome).append("*\n");
            if (shares > 0)
                msg.append("📦 Shares sold: ").append(String.format("%.4f", shares)).append("\n");
            if (closePx > 0)
                msg.append("💲 Close price: ").append(String.format("%.0f%%", closePx * 100)).append("\n");
            msg.append(pnlEmoji).append(" *P&L: ").append(pnlSign)
               .append(String.format("$%.2f", pnl)).append("*\n");
            if (!txHash.isEmpty())
                msg.append("🔗 Tx: `").append(txHash).append("`\n");
            msg.append("\n─────────────────────────\n");
            msg.append("`/wallet` — Updated balance\n");
            msg.append("`/positions` — Remaining open trades");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            sendText(chatId, "🔒 Position closed.\n\n" + raw);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // GROUP 4 — AI ADVICE (Agent Path)
    // ═══════════════════════════════════════════════════════════

    /**
     * /advice <market_id>      — free tier
     * /premium_advice <market_id> — paid tier (0.10 USDC via x402)
     *
     * Both route through handleAdvice(). The `premium` flag controls:
     *   - which backend method is called (getFreeAdvice vs getPremiumAdvice)
     *   - which fields are rendered in the response
     *   - whether the authorization + balance check runs first
     */
    private void handleAdvice(long chatId, String[] parts, boolean premium) {
        if (parts.length < 2) {
            sendMarkdown(chatId,
                (premium ? "💎 *Premium Advice*" : "🔍 *Free Advice*") + "\n\n" +
                "Usage: `" + (premium ? "/premium_advice" : "/advice") + " <market_id>`\n\n" +
                (premium
                    ? "Returns full structured analysis — summary, why trending, risk factors,\n" +
                      "confidence score (0–100), and position sizing guidance.\n\n" +
                      "Cost: *0.10 USDC* per query, deducted on Base via x402.\n" +
                      "Requires a prior `/authorize` to be set.\n\n"
                    : "Returns a 2-sentence summary with a BUY YES / BUY NO / WAIT recommendation.\n" +
                      "Free for all users. No USDC required.\n\n") +
                "Example: `" + (premium ? "/premium_advice" : "/advice") + " 527079`\n\n" +
                "💡 Use `/trending` or `/signals` to find market IDs."
            );
            return;
        }

        String mktId = parts[1];
        session(chatId).setLastMarketId(mktId);

        // ── Premium gate ───────────────────────────────────────────
        if (premium) {
            UserSession s = session(chatId);

            if (!s.isAuthorized()) {
                sendMarkdown(chatId,
                    "⚠️ *Authorization Required*\n\n" +
                    "You need to authorize a spending limit before using premium advice.\n\n" +
                    "Run `/authorize <max_usdc>` first.\n" +
                    "Example: `/authorize 5` — allows up to $5 USDC\n\n" +
                    "_Premium advice costs 0.10 USDC per query._"
                );
                return;
            }

            if (s.getMaxUsdc() < 0.10) {
                sendMarkdown(chatId,
                    "⚠️ *Insufficient Authorization*\n\n" +
                    "Your current limit is *$" + String.format("%.2f", s.getMaxUsdc()) + " USDC*, " +
                    "but premium advice costs *$0.10 USDC*.\n\n" +
                    "Run `/authorize 1` to set a sufficient limit."
                );
                return;
            }
        }

        // ── Call backend ───────────────────────────────────────────
        sendText(chatId, (premium ? "💎 " : "🔍 ") +
                 "Analyzing market `" + mktId + "`..." +
                 (premium ? " (premium)" : ""));

        String response = premium
            ? backend.getPremiumAdvice(mktId, chatId)
            : backend.getFreeAdvice(mktId, chatId);

        // ── Error handling ─────────────────────────────────────────
        if (isError(response) || response.contains("503") || response.startsWith("Error 5")) {
            sendMarkdown(chatId,
                "⚠️ *Backend Unavailable*\n\n" +
                "The AI agent is currently waking up. Please wait 30 seconds and try again."
            );
            return;
        }

        // ── 402 Payment Required ───────────────────────────────────
        // ── Payment Required ───────────────────────────────────────
        if (response.contains("402") || response.contains("PAYMENT-REQUIRED")) {
            sendMarkdown(chatId,
                "💎 *Payment Required*\n\n" +
                "Insufficient balance — top up via `/topup`.\n\n" +
                "You need to `/authorize` before using premium advice if you haven't already.\n" +
                "Use `/topup <amount_kes>` to add USDC via M-Pesa."
            );
            return;
        }

        // ── Parse and render ───────────────────────────────────────
        try {
            JSONObject json = new JSONObject(response);

            String marketId   = json.optString("market_id",        mktId);
            String summary    = json.optString("summary",          "No summary available.");
            String plan       = json.optString("suggested_plan",   json.optString("recommendation", "WAIT"));
            String disclaimer = json.optString("disclaimer", "This is not financial advice. Real funds may be at risk.");
            String staleWarn  = json.optString("stale_data_warning", "");

            StringBuilder msg = new StringBuilder();

            if (premium) {
                // ── PREMIUM: full structured output ───────────────────
                String why       = json.optString("why_trending", "");
                double confidence = json.optDouble("confidence", -1);

                // Risk factors array
                String riskList = "None listed";
                try {
                    Object risksObj = json.opt("risk_factors");
                    if (risksObj instanceof JSONArray) {
                        JSONArray risks = (JSONArray) risksObj;
                        StringBuilder rb = new StringBuilder();
                        for (int i = 0; i < risks.length(); i++)
                            rb.append("• ").append(risks.getString(i)).append("\n");
                        riskList = rb.toString().trim();
                    }
                } catch (Exception ignored) {}

                msg.append("💎 *Premium Analysis*\n");
                msg.append("Market: `").append(marketId).append("`\n");
                msg.append("─────────────────────────\n\n");
                msg.append("*📝 Summary*\n").append(summary).append("\n\n");
                if (!why.isEmpty())
                    msg.append("*📈 Why Trending*\n").append(why).append("\n\n");
                msg.append("*⚠️ Risk Factors*\n").append(riskList).append("\n\n");
                msg.append("*🎯 Recommendation:* ").append(formatPlan(plan)).append("\n");
                if (confidence >= 0)
                    msg.append("*📊 Confidence:* ").append(formatConfidenceBar(confidence)).append("\n");
                if (!staleWarn.isEmpty())
                    msg.append("\n⚠️ _").append(staleWarn).append("_\n");
                msg.append("\n─────────────────────────\n");
                msg.append("_").append(disclaimer).append("_\n\n");
                msg.append("`/trade ").append(marketId).append(" YES <amount>` — Place a trade");

            } else {
                // ── FREE: brief 2-sentence summary + recommendation ───
                msg.append("🔍 *Market Analysis*\n");
                msg.append("Market: `").append(marketId).append("`\n");
                msg.append("─────────────────────────\n\n");
                msg.append(summary).append("\n\n");
                msg.append("*Recommendation:* ").append(formatPlan(plan)).append("\n");
                if (!staleWarn.isEmpty())
                    msg.append("\n⚠️ _").append(staleWarn).append("_\n");
                msg.append("\n─────────────────────────\n");
                msg.append("_").append(disclaimer).append("_\n\n");
                msg.append("💎 `/premium_advice ").append(marketId)
                   .append("` — Full analysis with confidence score (0.10 USDC)");
            }

            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            // Raw fallback if JSON parsing fails
            sendText(chatId, (premium ? "💎" : "🔍") + " ADVICE\n═══════════════════════\n\n" +
                             response.substring(0, Math.min(500, response.length())));
        }
    }

    // ─── Advice formatting helpers ────────────────────────────

    /**
     * Formats the plan string into a clean emoji-prefixed label.
     * "BUY YES" → "✅ BUY YES", "BUY NO" → "❌ BUY NO", "WAIT" → "⏸ WAIT"
     */
    private String formatPlan(String plan) {
        if (plan == null) return "⏸ WAIT";
        String p = plan.toUpperCase().trim();
        if (p.contains("YES"))  return "✅ BUY YES";
        if (p.contains("NO"))   return "❌ BUY NO";
        if (p.contains("WAIT")) return "⏸ WAIT";
        return plan;
    }

    /**
     * Renders a confidence score (0.0–1.0 or 0–100) as a visual bar + percentage.
     * e.g. 0.72 → "███████░░░ 72%"
     */
    private String formatConfidenceBar(double confidence) {
        // Normalise: backend may send 0.72 or 72
        double norm = confidence > 1.0 ? confidence / 100.0 : confidence;
        int filled = (int) Math.round(norm * 10);
        String bar = "█".repeat(Math.max(0, filled)) + "░".repeat(Math.max(0, 10 - filled));
        return "`" + bar + "` " + String.format("%.0f%%", norm * 100);
    }

    // ═══════════════════════════════════════════════════════════
    // GROUP 5 — SOCIAL & COPY TRADING (Fast Path)
    // ═══════════════════════════════════════════════════════════

    /**
     * /leaders
     *
     * Shows the leaderboard — top traders ranked by portfolio value
     * and P&L, with their badges and win rates.
     * Calls GET /leaderboard.
     */
    private void handleLeaders(long chatId) {
        sendText(chatId, "🏆 Fetching leaderboard...");
        String raw = backend.getLeaderboard();

        if (isError(raw)) {
            sendMarkdown(chatId, "⚠️ Could not fetch the leaderboard. Please try again shortly.");
            return;
        }

        try {
            JSONArray leaders;
            try {
                leaders = new JSONArray(raw);
            } catch (Exception e) {
                JSONObject obj = new JSONObject(raw);
                leaders = obj.optJSONArray("leaderboard");
                if (leaders == null) leaders = obj.optJSONArray("data");
            }

            if (leaders == null || leaders.length() == 0) {
                sendMarkdown(chatId,
                    "🏆 *Leaderboard*\n\n" +
                    "No traders on the leaderboard yet.\n\n" +
                    "Start trading to appear here — use `/trade <id> YES|NO <amount>`."
                );
                return;
            }

            StringBuilder msg = new StringBuilder();
            msg.append("🏆 *Leaderboard*\n");
            msg.append("Top traders by portfolio P&L\n");
            msg.append("─────────────────────────\n\n");

            // Medal emojis for top 3
            String[] medals = {"🥇", "🥈", "🥉"};

            for (int i = 0; i < leaders.length(); i++) {
                JSONObject l    = leaders.getJSONObject(i);
                String username = l.optString("username", l.optString("display_name", "Anonymous"));
                double balance  = l.optDouble("portfolio_value", l.optDouble("paper_balance", 1000));
                double netPnl   = l.optDouble("net_pnl",   0);
                double winRate  = l.optDouble("win_rate",  0);
                int    trades   = l.optInt("total_trades", 0);
                String badge    = l.optString("badge", "");

                String rank     = i < 3 ? medals[i] : "*" + (i + 1) + ".*";
                String pnlSign  = netPnl >= 0 ? "+" : "";
                String pnlEmoji = netPnl >= 0 ? "📈" : "📉";

                msg.append(rank).append(" *@").append(username).append("*");
                if (!badge.isEmpty()) msg.append("  ").append(badge);
                msg.append("\n");
                msg.append("   💵 $").append(String.format("%.2f", balance));
                msg.append("  ").append(pnlEmoji).append(" ").append(pnlSign)
                   .append(String.format("$%.2f", netPnl));
                if (winRate > 0)
                    msg.append("  |  Win: ").append(String.format("%.0f%%", winRate));
                if (trades > 0)
                    msg.append("  |  ").append(trades).append(" trades");
                msg.append("\n\n");
            }

            msg.append("─────────────────────────\n");
            msg.append("`/copy <username> <pct>` — Copy a top trader\n");
            msg.append("_e.g. `/copy alice 10` mirrors Alice at 10% of your balance_");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            sendText(chatId, "🏆 LEADERBOARD\n═══════════════════════\n\n" + raw);
        }
    }

    /**
     * /copy <username> <pct>
     *
     * Subscribes to copy-trade a leader. Whenever the leader places a
     * trade, the bot automatically mirrors it proportionally based on
     * `pct` % of your balance.
     * Calls POST /copytrading/follow.
     *
     * Example: /copy alice 10  → allocates 10% of balance to copy Alice
     */
    private void handleCopy(long chatId, String[] parts) {
        if (parts.length < 3) {
            sendMarkdown(chatId,
                "🔄 *Copy Trading*\n\n" +
                "Usage: `/copy <username> <pct>`\n\n" +
                "Mirrors a leader's trades proportionally based on `pct` % of your balance.\n\n" +
                "Example: `/copy alice 10` — allocates 10% of your balance to copy Alice\n\n" +
                "• Minimum allocation: 1%\n" +
                "• Maximum allocation: 100%\n" +
                "• Use `/leaders` to see who's worth copying\n" +
                "• Use `/uncopy <username>` to stop at any time"
            );
            return;
        }

        String leaderUsername = parts[1].replace("@", ""); // strip @ if included
        double pct;
        try {
            pct = Double.parseDouble(parts[2]);
        } catch (NumberFormatException e) {
            sendText(chatId, "❌ Invalid percentage. Use a number. Example: `/copy alice 10`");
            return;
        }

        if (pct < 1 || pct > 100) {
            sendText(chatId, "❌ Allocation must be between 1% and 100%. Example: `/copy alice 10`");
            return;
        }

        sendText(chatId, "⏳ Setting up copy trading for @" + leaderUsername + "...");
        String raw = backend.followLeader(chatId, leaderUsername, pct);

        if (isError(raw)) {
            sendMarkdown(chatId,
                "❌ *Could not follow @" + leaderUsername + "*\n\n" +
                "The username may not exist or they may not be a leader.\n\n" +
                "Use `/leaders` to see available traders to copy."
            );
            return;
        }

        try {
            JSONObject json     = new JSONObject(raw);
            String status       = json.optString("status", "active");
            double leaderBalance = json.optDouble("leader_portfolio_value", 0);
            double leaderPnl    = json.optDouble("leader_net_pnl", 0);
            String leaderBadge  = json.optString("leader_badge", "");

            StringBuilder msg = new StringBuilder();
            msg.append("🔄 *Copy Trading Active*\n");
            msg.append("─────────────────────────\n\n");
            msg.append("👤 Following: *@").append(leaderUsername).append("*");
            if (!leaderBadge.isEmpty()) msg.append("  ").append(leaderBadge);
            msg.append("\n");
            msg.append("📊 Allocation: *").append(String.format("%.0f%%", pct)).append("* of your balance per trade\n");
            if (leaderBalance > 0)
                msg.append("💵 Leader portfolio: $").append(String.format("%.2f", leaderBalance)).append("\n");
            if (leaderPnl != 0) {
                String sign = leaderPnl >= 0 ? "+" : "";
                msg.append("📈 Leader P&L: ").append(sign).append(String.format("$%.2f", leaderPnl)).append("\n");
            }
            msg.append("\n");
            msg.append("Every trade @").append(leaderUsername)
               .append(" places will be automatically mirrored to your account.\n\n");
            msg.append("─────────────────────────\n");
            msg.append("`/uncopy ").append(leaderUsername).append("` — Stop copying\n");
            msg.append("`/myfollowers` — See who's following you");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            sendMarkdown(chatId,
                "🔄 *Copy Trading Active*\n\n" +
                "You are now copying *@" + leaderUsername + "* at *" +
                String.format("%.0f%%", pct) + "* allocation.\n\n" +
                "Use `/uncopy " + leaderUsername + "` to stop."
            );
        }
    }

    /**
     * /uncopy <username>
     *
     * Stops copy-trading the specified leader.
     * Existing open positions from copy trades are NOT closed.
     * Calls POST /copytrading/unfollow.
     */
    private void handleUncopy(long chatId, String[] parts) {
        if (parts.length < 2) {
            sendMarkdown(chatId,
                "⏹️ *Stop Copy Trading*\n\n" +
                "Usage: `/uncopy <username>`\n\n" +
                "Stops mirroring a leader's trades. Existing open positions are not affected.\n\n" +
                "Example: `/uncopy alice`"
            );
            return;
        }

        String leaderUsername = parts[1].replace("@", "");
        sendText(chatId, "⏳ Unfollowing @" + leaderUsername + "...");

        String raw = backend.unfollowLeader(chatId, leaderUsername);

        if (isError(raw)) {
            sendMarkdown(chatId,
                "❌ *Could not unfollow @" + leaderUsername + "*\n\n" +
                "You may not currently be copying this user."
            );
            return;
        }

        sendMarkdown(chatId,
            "⏹️ *Copy Trading Stopped*\n\n" +
            "You are no longer copying *@" + leaderUsername + "*.\n\n" +
            "Any positions already opened via copy trading remain open — " +
            "use `/positions` to review and `/close <trade_id>` to exit them if needed.\n\n" +
            "─────────────────────────\n" +
            "`/leaders` — Find another trader to follow"
        );
    }

    /**
     * /myfollowers
     *
     * Shows who is currently copy-trading you — their display names,
     * allocation percentages, and total capital following you.
     * Calls GET /copytrading/followers.
     */
    private void handleMyFollowers(long chatId) {
        sendText(chatId, "👥 Fetching your followers...");
        String raw = backend.getMyFollowers(chatId);

        if (isError(raw)) {
            sendMarkdown(chatId, "⚠️ Could not fetch followers. Please try again shortly.");
            return;
        }

        try {
            JSONArray followers;
            try {
                followers = new JSONArray(raw);
            } catch (Exception e) {
                JSONObject obj = new JSONObject(raw);
                followers = obj.optJSONArray("followers");
                if (followers == null) followers = obj.optJSONArray("data");
            }

            if (followers == null || followers.length() == 0) {
                sendMarkdown(chatId,
                    "👥 *My Followers*\n\n" +
                    "Nobody is copy-trading you yet.\n\n" +
                    "Climb the `/leaders` leaderboard to attract followers!"
                );
                return;
            }

            double totalCapital = 0;
            StringBuilder msg   = new StringBuilder();
            msg.append("👥 *My Followers* (").append(followers.length()).append(")\n");
            msg.append("─────────────────────────\n\n");

            for (int i = 0; i < followers.length(); i++) {
                JSONObject f    = followers.getJSONObject(i);
                String username = f.optString("username", f.optString("display_name", "Anonymous"));
                double alloc    = f.optDouble("allocation_pct",      0);
                double capital  = f.optDouble("allocated_capital",   0);
                int    trades   = f.optInt("trades_copied",          0);

                totalCapital += capital;

                msg.append("*").append(i + 1).append(".* @").append(username).append("\n");
                msg.append("   📊 ").append(String.format("%.0f%%", alloc)).append(" allocation");
                if (capital > 0)
                    msg.append("  |  $").append(String.format("%.2f", capital)).append(" capital");
                if (trades > 0)
                    msg.append("  |  ").append(trades).append(" trades copied");
                msg.append("\n\n");
            }

            msg.append("─────────────────────────\n");
            msg.append("💰 Total capital following you: *$")
               .append(String.format("%.2f", totalCapital)).append(" USDC*\n\n");
            msg.append("_Every trade you place will be mirrored to all followers._");
            sendMarkdown(chatId, msg.toString());

        } catch (Exception e) {
            sendText(chatId, "👥 MY FOLLOWERS\n═══════════════════════\n\n" + raw);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // x402 PAYMENT PROOF HANDLER
    // ═══════════════════════════════════════════════════════════

    private void handlePaymentProof(long chatId, String txHash) {
        String verifyResult = backend.verifyPayment(txHash, chatId);
        try {
            JSONObject json = new JSONObject(verifyResult);
            if (json.getBoolean("success")) {
                sendText(chatId, "✅ Payment verified! Unlocking premium advice...");
                String lastMarketId = session(chatId).getLastMarketId();
                if (lastMarketId != null) {
                    handleAdvice(chatId, new String[]{"/premium_advice", lastMarketId}, true);
                } else {
                    sendText(chatId, "✅ Payment confirmed. Now send `/premium_advice <market_id>` to get your analysis.");
                }
            } else {
                sendText(chatId, "❌ Payment not verified. " + json.optString("reason", "Unknown error"));
            }
        } catch (Exception e) {
            sendText(chatId, "❌ Verification error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════
    // CALLBACK QUERY HANDLER (inline keyboard buttons)
    // ═══════════════════════════════════════════════════════════

    private void handleCallback(String callData, long chatId) {
        switch (callData) {
            case "btn_trending": handleTrending(chatId);                                                          break;
            case "btn_signals":  handleSignals(chatId);                                                           break;
            case "btn_wallet":   handleWallet(chatId);                                                            break;
            case "btn_advice":   sendMarkdown(chatId, "🔍 *AI Advice*\n\nUsage: `/advice <market_id>`\nExample: `/advice 527079`"); break;
            default:             sendText(chatId, "Unknown button action.");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // HELP
    // ═══════════════════════════════════════════════════════════

    private void sendHelp(long chatId) {
        sendMarkdown(chatId,
            "*NORT — Command Reference*\n\n" +
            "⚙️ *Account & Setup*\n" +
            "`/start` — Register and onboard\n" +
            "`/authorize <max_usdc>` — Set spending limit\n" +
            "`/mode <PAPER|REAL>` — Switch trading mode\n\n" +
            "📊 *Markets & Signals*\n" +
            "`/trending` — Top markets by 24hr volume\n" +
            "`/market <id>` — Full detail for one market\n" +
            "`/signals` — Top 20 ranked signals with scores\n\n" +
            "💼 *Portfolio & Trading*\n" +
            "`/wallet` — Wallet balance & P&L\n" +
            "`/topup <amount_kes>` — Fund via M-Pesa\n" +
            "`/positions` — Open positions\n" +
            "`/trade <id> <YES|NO> <amount>` — Place trade\n" +
            "`/close <trade_id>` — Exit a position\n\n" +
            "🤖 *AI Advice*\n" +
            "`/advice <id>` — Free AI analysis\n" +
            "`/premium_advice <id>` — Full analysis (0.10 USDC)\n\n" +
            "👥 *Copy Trading*\n" +
            "`/leaders` — Leaderboard\n" +
            "`/copy <username> <pct>` — Copy a trader\n" +
            "`/uncopy <username>` — Stop copying\n" +
            "`/myfollowers` — Who's copying you"
        );
    }

    // ═══════════════════════════════════════════════════════════
    // SHARED SEND HELPERS
    // ═══════════════════════════════════════════════════════════

    /** Sends plain text (no markdown parsing). */
    public void sendText(long chatId, String text) {
        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .build();
        try { execute(sm); }
        catch (TelegramApiException e) { System.err.println("sendText failed: " + e.getMessage()); }
    }

    /** Sends Telegram Markdown formatted text. */
    public void sendMarkdown(long chatId, String text) {
        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text(text)
                .parseMode("Markdown")
                .build();
        try { execute(sm); }
        catch (TelegramApiException e) {
            // Fallback to plain text if markdown fails
            System.err.println("sendMarkdown failed, retrying plain: " + e.getMessage());
            sendText(chatId, text.replaceAll("[*_`]", ""));
        }
    }

    /** Sends the main interactive menu with inline keyboard. */
    public void sendMenu(long chatId) {
        SendMessage sm = SendMessage.builder()
                .chatId(String.valueOf(chatId))
                .text("NORT — What would you like to do?")
                .build();

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        row1.add(InlineKeyboardButton.builder().text("📈 Trending").callbackData("btn_trending").build());
        row1.add(InlineKeyboardButton.builder().text("📊 Signals").callbackData("btn_signals").build());

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        row2.add(InlineKeyboardButton.builder().text("💼 Wallet").callbackData("btn_wallet").build());
        row2.add(InlineKeyboardButton.builder().text("🤖 AI Advice").callbackData("btn_advice").build());

        rows.add(row1);
        rows.add(row2);
        markup.setKeyboard(rows);
        sm.setReplyMarkup(markup);

        try { execute(sm); }
        catch (TelegramApiException e) { System.err.println("sendMenu failed: " + e.getMessage()); }
    }
}
