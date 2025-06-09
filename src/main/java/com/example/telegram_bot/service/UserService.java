package com.example.telegram_bot.service;

import com.example.telegram_bot.dal.UserRepository;
import com.example.telegram_bot.dto.NewUserRequest;
import com.example.telegram_bot.dto.UserDto;
import com.example.telegram_bot.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;

    public UserDto createUser(NewUserRequest request) {
        User user = new User();
        user.setTelegramId(request.getTelegramId());
        user.setClientFilePath(request.getClientFilePath());
        user.setPaymentTime(request.getPaymentTime());
        user.setEndTime(request.getEndTime());

        User saved = userRepository.save(user);

        return mapToDto(saved);
    }

    public List<UserDto> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::mapToDto)
                .toList();
    }

    public Optional<UserDto> findByTelegramId(String telegramId) {
        return userRepository.findByTelegramId(telegramId)
                .map(this::mapToDto);
    }

    public void updateUser(User user) {
        userRepository.update(user);
    }


    private UserDto mapToDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setTelgramId(user.getTelegramId());
        dto.setClientFilePath(user.getClientFilePath());
        dto.setPaymentTime(user.getPaymentTime());
        dto.setEndTime(user.getEndTime());
        return dto;
    }
}

