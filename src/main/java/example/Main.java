package example;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) throws TelegramApiException {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        Bot bot = new Bot();
        botsApi.registerBot(bot);
        bot.sendText(6117624773L, "Hello there and welcome to Nort! Your one stop shop for all your Polymarket advice needs");

        // Ensure the Web App is available from the bot menu (use WEBAPP_URL or fallback)
        String webApp = System.getenv("WEBAPP_URL");
        if (webApp == null || webApp.isEmpty()) webApp = "https://nort-rho.vercel.app/";
        bot.setChatMenuWebApp(webApp);
    }
}

