package example;

import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

public class Main {
    public static void main(String[] args) {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
            Bot bot = new Bot();  // Your Bot class MUST extend TelegramLongPollingBot
            botsApi.registerBot(bot);

            // Send welcome message
            bot.sendText(6117624773L, "Hello there and welcome to Nort! Your one stop shop for all your Polymarket advice needs");

            // Keep app running
            System.out.println("Bot started! Press Ctrl+C to stop.");
            Thread.currentThread().join();  // Prevents immediate exit

        } catch (TelegramApiException | InterruptedException e) {
            e.printStackTrace();
        }
    }
}
