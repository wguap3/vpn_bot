package com.example.telegram_bot.dto;

import lombok.Data;

import java.time.Instant;

@Data
public class NewUserRequest {
    private String telegramId;
    ;
    private String clientFilePath;
    private Instant paymentTime;
    private Instant endTime;
}
