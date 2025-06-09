package com.example.telegram_bot.model;

import lombok.Data;

import java.time.Instant;

@Data
public class User {
    private Long id;
    private String telegramId;
    ;
    private String clientFilePath;
    private Instant paymentTime;
    private Instant endTime;
}

