package com.aewol.config;

import com.aewol.common.filter.JwtAuthenticationFilter;
import com.aewol.common.security.JwtAccessDeniedHandler;
import com.aewol.common.security.JwtAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * 허용할 프론트엔드 오리진 목록(쉼표 구분).
     *
     * <p>운영 도메인을 코드에 박아두면 도메인이 바뀔 때마다 재빌드해야 하고, 로컬 주소가
     * 운영에 그대로 남는 사고도 생긴다. 기본값이 기존 로컬 주소라 local·dev 프로파일의
     * 동작은 달라지지 않는다.
     */
    @Value("${cors.allowed-origins:http://localhost:5173,http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .exceptionHandling(ex -> ex
                        // 미인증 → 401(JSON), 인증됐으나 권한 부족 → 403(JSON)으로 명확히 구분
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint())
                        .accessDeniedHandler(jwtAccessDeniedHandler()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/api/auth/**").permitAll()
                        // springfox의 UI는 /swagger-resources 로 스펙 위치를 먼저 조회한다
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                        // 컨테이너 헬스체크·배포 확인용. 상태값만 반환하므로 인증 없이 연다.
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        // <img> 태그는 Authorization 헤더를 붙일 수 없다. 이 경로는 JWT 대신
                        // URL에 실린 서명과 만료 시각으로 접근을 판단한다(FileController).
                        .requestMatchers(HttpMethod.GET, "/api/files/**").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        // /api/support는 지원정책(SupportController)과 고객센터(FAQ·1:1 문의)가
                        // prefix를 나눠 쓴다. 공개해도 되는 것은 FAQ뿐이라 그것만 연다.
                        // 지원정책은 회원 반려동물 기준 매칭이고 문의는 개인 기록이라 인증이 필요하다.
                        .requestMatchers(HttpMethod.GET, "/api/support/faqs", "/api/support/faqs/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/banks").permitAll()
                        // User only (관리자는 공동구매에 참여/구매할 수 없음 — 등록·관리만 담당)
                        .requestMatchers(HttpMethod.POST, "/api/group-purchase/*/join").hasRole("USER")
                        .requestMatchers(HttpMethod.POST, "/api/group-purchase/*/leave").hasRole("USER")
                        .requestMatchers(HttpMethod.GET, "/api/group-purchase/my").hasRole("USER")
                        // Admin only
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/group-purchase", "/api/group-purchase/images", "/api/group-purchase/*/cancel").hasRole("ADMIN")
                        // All other requests require authentication
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint() {
        return new JwtAuthenticationEntryPoint();
    }

    @Bean
    public JwtAccessDeniedHandler jwtAccessDeniedHandler() {
        return new JwtAccessDeniedHandler();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
