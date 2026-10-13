package com.medicalrag.auth;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AdministratorUserDetailsService implements UserDetailsService {
    private final AdministratorRepository repository;

    public AdministratorUserDetailsService(AdministratorRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        var stored = repository.findByUsername(AdministratorService.normalizeUsername(username))
                .orElseThrow(() -> new UsernameNotFoundException("账号或密码错误"));
        return User.withUsername(stored.administrator().username())
                .password(stored.passwordHash()).roles("ADMIN").build();
    }
}
