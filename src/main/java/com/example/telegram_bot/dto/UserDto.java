package com.example.telegram_bot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.Instant;

@Data
public class UserDto {
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private long id;
    private String telgramId;
    private String clientFilePath;
    private Instant paymentTime;
    private Instant endTime;
}
