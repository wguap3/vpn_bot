package com.example.telegram_bot.service;

import com.example.telegram_bot.config.BotConfig;
import com.example.telegram_bot.config.PaymentConfig;
import com.example.telegram_bot.dto.NewUserRequest;
import com.example.telegram_bot.dto.UserDto;
import com.example.telegram_bot.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class TelegramBot extends TelegramLongPollingBot {
    private final UserService userService;
    private final BotConfig botConfig;
    private final PaymentConfig paymentConfig;

    // Хранилища данных
    private final Map<Long, LocalDate> subscriptions = new HashMap<>();
    private final Map<Long, String> clientNames = new HashMap<>();

    // Тарифы VPN (месяцы -> цена в копейках)
    private final Map<Integer, Integer> vpnPlans = Map.of(
            1, 7000,   // 70 руб
            2, 14000,  // 140 руб
            3, 21000,  // 210 руб
            4, 25000   // 250 руб
    );

    @Override
    public String getBotUsername() {
        return botConfig.getBotName();
    }

    @Override
    public String getBotToken() {
        return botConfig.getToken();
    }

    @Override
    public void onUpdateReceived(Update update) {
        try {
            if (update.hasPreCheckoutQuery()) {
                handlePreCheckout(update.getPreCheckoutQuery());
            } else if (update.hasMessage()) {
                if (update.getMessage().hasSuccessfulPayment()) {
                    handleSuccessfulPayment(update.getMessage());
                } else if (update.getMessage().hasText()) {
                    handleTextMessage(update);
                }
            } else if (update.hasCallbackQuery()) {
                handleCallback(update.getCallbackQuery());
            }
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleTextMessage(Update update) throws TelegramApiException {
        String text = update.getMessage().getText();
        Long chatId = update.getMessage().getChatId();

        switch (text) {
            case "/start" -> sendStartMessage(chatId);
            case "/buy" -> showPlans(chatId);
            case "/status" -> checkStatus(chatId, update.getMessage().getFrom().getId());
            default -> sendMessage(chatId, "Неизвестная команда. Используйте /buy для покупки VPN.");
        }
    }

    private void sendStartMessage(Long chatId) throws TelegramApiException {
        String text = """
                🔐 <b>Добро пожаловать в VPN сервис!</b>
                
                <b>Доступные команды:</b>
                /buy - Купить подписку
                /status - Проверить статус
                
                <b>Наши преимущества:</b>
                ✓ Высокая скорость
                ✓ Безлимитный трафик
                ✓ Без логов""";

        sendHtmlMessage(chatId, text);
    }

    private void showPlans(Long chatId) throws TelegramApiException {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        vpnPlans.forEach((months, price) -> {
            String buttonText = String.format("%d месяц - %d руб", months, price / 100);
            rows.add(List.of(
                    InlineKeyboardButton.builder()
                            .text(buttonText)
                            .callbackData("plan_" + months)
                            .build()
            ));
        });

        markup.setKeyboard(rows);

        sendMessage(chatId, "Выберите срок подписки:", markup);
    }

    private void handleCallback(org.telegram.telegrambots.meta.api.objects.CallbackQuery callback)
            throws TelegramApiException {

        String data = callback.getData();
        Long chatId = callback.getMessage().getChatId();

        if (data.startsWith("plan_")) {
            int months = Integer.parseInt(data.substring(5));
            createInvoice(chatId, months);
        }
    }

    private void createInvoice(Long chatId, int months) throws TelegramApiException {
        Integer price = vpnPlans.get(months);
        if (price == null) {
            sendMessage(chatId, "Ошибка: неверный тарифный план");
            return;
        }

        SendInvoice invoice = SendInvoice.builder()
                .chatId(chatId.toString())
                .title("VPN подписка на " + months + " месяц(ев)")
                .description("Доступ к VPN сервису")
                .payload("vpn_" + months)
                .providerToken(paymentConfig.getToken())
                .currency("RUB")
                .startParameter("vpn_subscription")
                .prices(List.of(new LabeledPrice("Подписка", price)))
                .build();

        execute(invoice);
    }

    private void handlePreCheckout(PreCheckoutQuery preCheckout) throws TelegramApiException {
        AnswerPreCheckoutQuery answer = AnswerPreCheckoutQuery.builder()
                .preCheckoutQueryId(preCheckout.getId())
                .ok(true)
                .build();

        execute(answer);
    }

    private void handleSuccessfulPayment(org.telegram.telegrambots.meta.api.objects.Message message)
            throws TelegramApiException {

        Long tgId = message.getFrom().getId();
        Long chatId = message.getChatId();
        SuccessfulPayment payment = message.getSuccessfulPayment();

        int months = Integer.parseInt(payment.getInvoicePayload().split("_")[1]);
        String clientName = "client" + tgId;
        String clientPath = "/home/user/openvpn-clients/" + clientName + ".ovpn";

        Optional<UserDto> existingUserOpt = userService.findByTelegramId(tgId.toString());

        Instant newPaymentTime = Instant.now();
        Instant newEndTime;

        if (existingUserOpt.isPresent()) {
            UserDto existingUser = existingUserOpt.get();

            // Продлеваем от текущего срока, если он ещё не истёк
            Instant base = existingUser.getEndTime().isAfter(newPaymentTime)
                    ? existingUser.getEndTime()
                    : newPaymentTime;

            newEndTime = base.plus(months, ChronoUnit.MINUTES);

            User updatedUser = new User();
            updatedUser.setId(existingUser.getId());
            updatedUser.setTelegramId(existingUser.getTelgramId());
            updatedUser.setClientFilePath(clientPath);
            updatedUser.setPaymentTime(newPaymentTime);
            updatedUser.setEndTime(newEndTime);

            userService.updateUser(updatedUser);

        } else {
            if (!generateVpnConfig(clientName)) {
                sendMessage(chatId, "❌ Ошибка генерации конфига.");
                return;
            }

            NewUserRequest request = new NewUserRequest();
            request.setTelegramId(tgId.toString());
            request.setClientFilePath(clientPath);
            request.setPaymentTime(newPaymentTime);
            request.setEndTime(newPaymentTime.plus(months, ChronoUnit.MINUTES));

            userService.createUser(request);
        }
        unblockClient(clientName);
        sendVpnFile(chatId, clientName);
        sendMessage(chatId, "✅ Подписка активирована или продлена. Конфигурация отправлена.");
    }


    private boolean generateVpnConfig(String clientName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", "./create_client_config.sh " + clientName);
            pb.directory(new File("/home/user/easy-rsa"));
            Process process = pb.start();

            StreamGobbler outputGobbler = new StreamGobbler(process.getInputStream(), System.out::println);
            StreamGobbler errorGobbler = new StreamGobbler(process.getErrorStream(), System.err::println);
            outputGobbler.start();
            errorGobbler.start();

            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void sendVpnFile(Long chatId, String clientName) throws TelegramApiException {
        File file = new File("/home/user/openvpn-clients/" + clientName + ".ovpn");
        if (!file.exists()) {
            sendMessage(chatId, "❌ Файл конфигурации не найден");
            return;
        }

        SendDocument doc = SendDocument.builder()
                .chatId(chatId.toString())
                .document(new InputFile(file))
                .caption("⚙️ Ваш конфиг OpenVPN")
                .build();

        execute(doc);
        sendMessage(chatId, "⚙️ (Mock) Файл конфигурации для " + clientName + " отправлен.");
    }

    private void checkStatus(Long chatId, Long userId) throws TelegramApiException {
        Optional<UserDto> userOpt = userService.findByTelegramId(userId.toString());

        if (userOpt.isEmpty()) {
            sendHtmlMessage(chatId, "❌ У вас нет активной подписки");
            return;
        }

        UserDto user = userOpt.get();
        Instant now = Instant.now();

        if (user.getEndTime().isAfter(now)) {
            sendMessage(chatId, "✅ Подписка активна до: " + user.getEndTime());
        } else {
            sendMessage(chatId, "❌ Подписка истекла: " + user.getEndTime());
        }
    }


    @Scheduled(cron = "0 20 19 * * *", zone = "UTC")
    public void checkExpiredSubscriptions() {
        Instant now = Instant.now();
        System.out.println("Check started at: " + now);

        List<UserDto> users = userService.getAllUsers();
        for (UserDto user : users) {
            System.out.println("User " + user.getTelgramId() + " subscription ends at " + user.getEndTime());
            if (user.getEndTime().isBefore(now)) {
                blockClient(user.getClientFilePath());

                String message = "Ваша подписка истекла. Пожалуйста, продлите её для продолжения использования VPN.\n" +
                        "Для продления введите команду: \\buy";
                try {
                    sendMessage(Long.parseLong(user.getTelgramId()), message);
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    private void blockClient(String filePath) {
        String clientName = new File(filePath).getName().replace(".ovpn", "");
        String clientIp = null;
        String clientsFile = "/etc/openvpn/clients_ip.list";

        try (BufferedReader br = new BufferedReader(new FileReader(clientsFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].equals(clientName)) {
                    clientIp = parts[1];
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (clientIp == null) {
            System.out.println("IP для клиента " + clientName + " не найден в " + clientsFile);
            return;
        }

        try {
            new ProcessBuilder("/bin/bash", "-c", "./block_client.sh " + clientIp)
                    .directory(new File("/home/user"))
                    .start()
                    .waitFor();

            System.out.println("🔒 Клиент " + clientName + " с IP " + clientIp + " заблокирован по IP");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private void unblockClient(String clientName) {
        String clientIp = null;
        String clientsFile = "/etc/openvpn/clients_ip.list";

        try (BufferedReader br = new BufferedReader(new FileReader(clientsFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[0].equals(clientName)) {
                    clientIp = parts[1];
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (clientIp == null) {
            System.out.println("IP для клиента " + clientName + " не найден в " + clientsFile);
            return;
        }

        try {
            new ProcessBuilder("/bin/bash", "-c", "./unblock_client.sh " + clientIp)
                    .directory(new File("/home/user"))
                    .start()
                    .waitFor();

            System.out.println("✅ Клиент " + clientName + " с IP " + clientIp + " разблокирован по IP");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }



    private void revokeVpnAccess(String filePath) {
        String clientName = new File(filePath).getName().replace(".ovpn", "");
        try {
            new ProcessBuilder("/bin/bash", "-c", "./revoke_client.sh " + clientName)
                    .directory(new File("/home/user/easy-rsa"))
                    .start()
                    .waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println("Отозвали сертификат");
    }


    private void sendMessage(Long chatId, String text) throws TelegramApiException {
        execute(SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build());
    }

    private void sendHtmlMessage(Long chatId, String html) throws TelegramApiException {
        execute(SendMessage.builder()
                .chatId(chatId.toString())
                .text(html)
                .parseMode("HTML")
                .build());
    }

    private void sendMessage(Long chatId, String text, InlineKeyboardMarkup markup)
            throws TelegramApiException {

        execute(SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(markup)
                .build());
    }

    private static class StreamGobbler extends Thread {
        private final InputStream inputStream;
        private final Consumer<String> consumer;

        public StreamGobbler(InputStream inputStream, Consumer<String> consumer) {
            this.inputStream = inputStream;
            this.consumer = consumer;
        }

        @Override
        public void run() {
            new BufferedReader(new InputStreamReader(inputStream)).lines()
                    .forEach(consumer);
        }
    }
}