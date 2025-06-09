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
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;
import org.telegram.telegrambots.meta.api.objects.payments.SuccessfulPayment;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.*;
import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

@Component
@RequiredArgsConstructor
public class TelegramBot extends TelegramLongPollingBot {
    private final UserService userService;
    private final BotConfig botConfig;
    private final PaymentConfig paymentConfig;

    // Тарифы VPN (месяцы -> цена в копейках)
    private final Map<Integer, Integer> vpnPlans = Map.of(
            1, 7000,   // 70 руб
            2, 14000 // 140 руб
              // 250 руб
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

        if (text.equals("/start") || text.equals("⬅️ Главное меню")) {
            sendStartMessage(chatId);
        } else if (text.equals("🛒 Купить подписку")) {
            showPlans(chatId);
        } else if (text.equals("📊 Статус подписки")) {
            checkStatus(chatId, update.getMessage().getFrom().getId());
        } else if (text.equals("📄 Инструкция по установке")) {
            sendInstallInstructions(chatId);
        } else if (text.equals("🆘 Поддержка")) {
            sendSupportInfo(chatId);
        }else {
            sendMessage(chatId, "Пожалуйста, используйте кнопки меню ⬇️");
        }
    }

    private void sendStartMessage(Long chatId) throws TelegramApiException {
        String text = """
                🔐 <b>Добро пожаловать в VPN сервис!</b>
                
                <b>Используйте кнопки ниже для управления:</b>
                
                <b>Наши преимущества:</b>
                ✓ Высокая скорость
                ✓ Безлимитный трафик
                ✓ Простое подключение""";

        ReplyKeyboardMarkup keyboard = new ReplyKeyboardMarkup();
        keyboard.setResizeKeyboard(true);
        keyboard.setOneTimeKeyboard(false);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("🛒 Купить подписку");
        rows.add(row1);

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📊 Статус подписки");
        rows.add(row2);

        KeyboardRow row3 = new KeyboardRow();
        row3.add("📄 Инструкция по установке");
        rows.add(row3);

        KeyboardRow row4 = new KeyboardRow();
        row4.add("🆘 Поддержка");
        rows.add(row4);


        keyboard.setKeyboard(rows);

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("HTML")
                .replyMarkup(keyboard)
                .build();

        execute(message);
    }

    private void showPlans(Long chatId) throws TelegramApiException {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        vpnPlans.forEach((months, price) -> {
            String buttonText = String.format("%d месяц(а) - %d руб", months, price / 100);
            rows.add(List.of(
                    InlineKeyboardButton.builder()
                            .text(buttonText)
                            .callbackData("plan_" + months)
                            .build()
            ));
        });

        // Кнопка "Назад"
        rows.add(List.of(
                InlineKeyboardButton.builder()
                        .text("⬅️ Назад")
                        .callbackData("back_to_main")
                        .build()
        ));

        markup.setKeyboard(rows);

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text("<b>Выберите срок подписки:</b>")
                .parseMode("HTML")
                .replyMarkup(markup)
                .build();

        execute(message);
    }

    private void handleCallback(CallbackQuery callback) throws TelegramApiException {
        String data = callback.getData();
        Long chatId = callback.getMessage().getChatId();

        if (data.startsWith("plan_")) {
            int months = Integer.parseInt(data.substring(5));
            createInvoice(chatId, months);
        } else if ("back_to_main".equals(data)) {
            sendStartMessage(chatId);
            deleteMessage(chatId, callback.getMessage().getMessageId());
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

    private void handleSuccessfulPayment(Message message) throws TelegramApiException {
        Long tgId = message.getFrom().getId();
        Long chatId = message.getChatId();
        SuccessfulPayment payment = message.getSuccessfulPayment();

        int months = Integer.parseInt(payment.getInvoicePayload().split("_")[1]);
        String clientName = "client" + tgId;
        String clientPath = "/home/user/openvpn-clients/" + clientName + ".ovpn";

        Optional<UserDto> existingUserOpt = userService.findByTelegramId(tgId.toString());

        Instant newPaymentTime = Instant.now();
        long hoursToAdd = months * 720L;
        Instant newEndTime = newPaymentTime.plus(hoursToAdd, ChronoUnit.HOURS);

        if (existingUserOpt.isPresent()) {
            UserDto existingUser = existingUserOpt.get();

            // Продлеваем от текущего срока, если он ещё не истёк
            Instant base = existingUser.getEndTime().isAfter(newPaymentTime)
                    ? existingUser.getEndTime()
                    : newPaymentTime;

            newEndTime = base.plus(hoursToAdd, ChronoUnit.HOURS);

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
            request.setEndTime(newEndTime);

            userService.createUser(request);
        }

        unblockClient(clientName);
        sendVpnFile(chatId, clientName);
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.of("UTC")); // указываем часовой пояс, например UTC

        String formattedDate = formatter.format(newEndTime);
        String successText = """
                ✅Подписка активирована!
                
                Доступ до: %s
                
                Конфигурационный файл отправлен отдельным сообщением.
                
                Если вы уже являлись нашим клиентом конфигурационный файл менять не нужно!""".formatted(formattedDate);

        sendMessage(chatId, successText);
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
    private void sendInstallInstructions(Long chatId) throws TelegramApiException {
        String text = """
            📄 Инструкция по установке VPN:

            1. Скачайте конфигурационный файл (.ovpn), который мы отправили.
            2. Установите приложение OpenVPN:
               • Android — OpenVPN for Android
               • iOS — OpenVPN Connect
               • Windows/macOS — с официального сайта openvpn.net
            3. Импортируйте файл в приложение.
            4. Подключитесь, используя импортированный профиль.
            """;

        sendMessage(chatId, text);
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
    }

    private void checkStatus(Long chatId, Long userId) throws TelegramApiException {
        Optional<UserDto> userOpt = userService.findByTelegramId(userId.toString());

        if (userOpt.isEmpty()) {
            sendMessage(chatId, "❌ У вас нет активной подписки");
            return;
        }

        UserDto user = userOpt.get();
        Instant now = Instant.now();

        // Создаем форматтер даты
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .withZone(ZoneId.of("UTC")); // или свой часовой пояс

        String formattedEndTime = formatter.format(user.getEndTime());

        if (user.getEndTime().isAfter(now)) {
            sendMessage(chatId, "✅ Подписка активна до: " + formattedEndTime);
        } else {
            sendMessage(chatId, "❌ Подписка истекла: " + formattedEndTime);
        }
    }


    @Scheduled(cron = "0 10 22 * * *", zone = "UTC")
    public void checkExpiredSubscriptions() {
        Instant now = Instant.now();
        System.out.println("Check started at: " + now);

        List<UserDto> users = userService.getAllUsers();
        for (UserDto user : users) {
            System.out.println("User " + user.getTelgramId() + " subscription ends at " + user.getEndTime());
            if (user.getEndTime().isBefore(now)) {
                blockClient(user.getClientFilePath());

                String message = "Ваша подписка истекла. Пожалуйста, продлите её для продолжения использования VPN.\n" +
                        "Для продления нажмите кнопку \"🛒 Купить подписку\"";
                try {
                    sendMessage(Long.parseLong(user.getTelgramId()), message);
                } catch (TelegramApiException e) {
                    e.printStackTrace();
                }
            }
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

        if (clientIp == null) return;

        try {
            new ProcessBuilder("/bin/bash", "-c", "./unblock_client.sh " + clientIp)
                    .directory(new File("/home/user"))
                    .start()
                    .waitFor();
        } catch (Exception e) {
            e.printStackTrace();
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

        if (clientIp == null) return;

        try {
            new ProcessBuilder("/bin/bash", "-c", "./block_client.sh " + clientIp)
                    .directory(new File("/home/user"))
                    .start()
                    .waitFor();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private void sendSupportInfo(Long chatId) throws TelegramApiException {
        String text = """
            🆘 <b>Поддержка</b>

            Если у вас возникли вопросы или проблемы — напишите нам:
            👉 <a href="https://t.me/wguap3">@wguap3</a>

            Мы постараемся ответить как можно быстрее!
            """;

        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("HTML")
                .build();

        execute(message);
    }

    private void deleteMessage(Long chatId, Integer messageId) throws TelegramApiException {
        DeleteMessage deleteMessage = new DeleteMessage();
        deleteMessage.setChatId(chatId.toString());
        deleteMessage.setMessageId(messageId);
        execute(deleteMessage);
    }

    private void sendMessage(Long chatId, String text) throws TelegramApiException {
        execute(SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
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