package com.medicalrag.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministratorService {
    private final AdministratorRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AdministratorService(AdministratorRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean isInitialized() {
        return repository.isInitialized();
    }

    @Transactional
    public Administrator setup(String username, String password) {
        if (repository.isInitialized()) {
            throw new SetupAlreadyCompletedException();
        }
        int passwordBytes = password.getBytes(StandardCharsets.UTF_8).length;
        if (password.length() < 12 || passwordBytes > 72) {
            throw new IllegalArgumentException("密码至少需要 12 个字符，UTF-8 编码长度不得超过 72 字节");
        }
        return repository.create(normalizeUsername(username), passwordEncoder.encode(password))
                .orElseThrow(SetupAlreadyCompletedException::new);
    }

    public Administrator current(String username) {
        return repository.findByUsername(normalizeUsername(username))
                .map(AdministratorRepository.StoredAdministrator::administrator)
                .orElseThrow(() -> new UsernameNotFoundException("当前管理员不存在，请重新登录"));
    }

    public static String normalizeUsername(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    public static class SetupAlreadyCompletedException extends RuntimeException {
        public SetupAlreadyCompletedException() {
            super("管理员已创建，首次初始化入口已关闭");
        }
    }
}
