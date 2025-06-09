package com.example.telegram_bot.dal;

import com.example.telegram_bot.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository extends BaseRepository<User> {
    private static final String FIND_ALL_QUERY = "SELECT * FROM users";
    private static final String FIND_BY_EMAIL_QUERY = "SELECT * FROM users WHERE telegram_id = ?";
    private static final String FIND_BY_ID_QUERY = "SELECT * FROM users WHERE id = ?";
    private static final String INSERT_QUERY = "INSERT INTO users(telegram_id, client_file_path,payment_time,end_time)" +
            "VALUES (?, ?, ?, ?) returning id";
    private static final String UPDATE_QUERY = "UPDATE users SET  telegram_id = ?, client_file_path = ?, payment_time = ? , end_time = ? WHERE telegram_id = ?";

    public UserRepository(JdbcTemplate jdbc, RowMapper<User> mapper) {
        super(jdbc, mapper);
    }

    public List<User> findAll() {
        return findMany(FIND_ALL_QUERY);
    }

    public Optional<User> findByTelegramId(String telegram_id) {
        return findOne(FIND_BY_EMAIL_QUERY, telegram_id);
    }

    public Optional<User> findById(long userId) {
        return findOne(FIND_BY_ID_QUERY, userId);
    }

    public User save(User user) {
        long id = insert(
                INSERT_QUERY,
                user.getTelegramId(),
                user.getClientFilePath(),
                Timestamp.from(user.getPaymentTime()),
                Timestamp.from(user.getEndTime())
        );
        user.setId(id);
        return user;
    }

    public User update(User user) {
        update(
                UPDATE_QUERY,
                user.getTelegramId(),
                user.getClientFilePath(),
                Timestamp.from(user.getPaymentTime()),
                Timestamp.from(user.getEndTime()),
                user.getTelegramId() // <-- правильно передаем telegram_id для WHERE
        );
        return user;
    }

}
