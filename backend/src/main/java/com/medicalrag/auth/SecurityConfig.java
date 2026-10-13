package com.medicalrag.auth;

import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medicalrag.web.ApiResponses;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    DaoAuthenticationProvider authenticationProvider(AdministratorUserDetailsService users, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(users) {
            @Override
            public Authentication authenticate(Authentication authentication) throws AuthenticationException {
                if (authentication.getCredentials() == null
                        || authentication.getCredentials().toString().getBytes(StandardCharsets.UTF_8).length > 72) {
                    throw new BadCredentialsException("账号或密码错误");
                }
                return super.authenticate(authentication);
            }
        };
        provider.setPasswordEncoder(passwords);
        return provider;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiResponses responses, ObjectMapper json,
            AdministratorService administrators) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/auth/setup-status", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/setup", "/api/auth/login").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .csrf(csrf -> { })
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                responses.error(response, 401, "AUTHENTICATION_REQUIRED", "请先登录本地管理员账号"))
                        .accessDeniedHandler((request, response, exception) -> {
                            if (exception instanceof CsrfException) {
                                responses.error(response, 403, "CSRF_INVALID", "CSRF 校验失败，请重新获取令牌后再操作");
                            } else {
                                responses.error(response, 403, "AUTHORIZATION_DENIED", "当前账号没有管理员权限");
                            }
                        }))
                .formLogin(login -> login
                        // A2 is API-only; do not generate a Spring login HTML page.
                        .loginPage("/api/auth/login")
                        .loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, authentication) -> {
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            json.writeValue(response.getWriter(), administrators.current(authentication.getName()));
                        })
                        .failureHandler((request, response, exception) -> {
                            if (exception instanceof InternalAuthenticationServiceException) {
                                responses.error(response, 503, "AUTHENTICATION_UNAVAILABLE", "账号服务暂时不可用，请检查数据库状态");
                            } else {
                                responses.error(response, 401, "INVALID_CREDENTIALS", "账号或密码错误");
                            }
                        }))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            if (authentication == null) {
                                responses.error(response, 401, "AUTHENTICATION_REQUIRED", "请先登录本地管理员账号");
                            } else {
                                response.setStatus(204);
                            }
                        }));
        return http.build();
    }
}
