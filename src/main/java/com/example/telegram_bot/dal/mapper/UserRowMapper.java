package com.example.telegram_bot.dal.mapper;

import com.example.telegram_bot.model.User;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

@Component
public class UserRowMapper implements RowMapper<User> {
    @Override
    public User mapRow(ResultSet resultSet, int rowNum) throws SQLException {
        User user = new User();
        user.setId(resultSet.getLong("id"));
        user.setTelegramId(resultSet.getString("telegram_id"));
        user.setClientFilePath(resultSet.getString("client_file_path"));

        Timestamp paymentTime = resultSet.getTimestamp("payment_time");
        user.setPaymentTime(paymentTime.toInstant());

        Timestamp endTime = resultSet.getTimestamp("end_time");
        user.setEndTime(endTime.toInstant());

        return user;
    }
}
