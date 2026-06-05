package com.msa.api_gateway.filter;

import com.msa.api_gateway.exception.RedisUnavailableException;
import com.msa.api_gateway.util.WebFluxResponseUtils;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.List;

@Component
@Slf4j
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final String INTERNAL_PATH_PREFIX = "/internal/";
    private static final String BL_PREFIX = "BL:";
    private static final String BL_USER_PREFIX = "BL:USER:";
    private static final List<String> INTERNAL_HEADERS = List.of("X-User-Id", "X-User-Role", "X-Hub-Id", "X-Company-Id");

    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
    );

    private static final List<String> PUBLIC_PREFIXES = List.of(
            "/swagger-ui",
            "/v3/api-docs",
            "/swagger-resources",
            "/webjars"
    );

    private final SecretKey secretKey;
    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public JwtAuthFilter(
            @Value("${jwt.secret}") String secret,
            ReactiveRedisTemplate<String, String> redisTemplate
    ) {
        this.secretKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(secret));
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (path.startsWith(INTERNAL_PATH_PREFIX)) {
            log.warn("event=GATEWAY_INTERNAL_PATH_BLOCKED path={}", path);
            return WebFluxResponseUtils.writeErrorResponse(exchange, HttpStatus.FORBIDDEN, "접근이 거부되었습니다.");
        }

        if (PUBLIC_PATHS.stream().anyMatch(path::equals) ||
                PUBLIC_PREFIXES.stream().anyMatch(path::startsWith) ||
                path.contains("/v3/api-docs")) {
            return chain.filter(sanitizeHeaders(exchange));
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("event=GATEWAY_AUTH_MISSING path={}", path);
            return WebFluxResponseUtils.writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "인증 토큰이 없습니다.");
        }

        String token = authHeader.substring(7);
        if (token.isBlank()) {
            log.warn("event=GATEWAY_AUTH_BLANK path={}", path);
            return WebFluxResponseUtils.writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "인증 토큰이 없습니다.");
        }

        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("event=GATEWAY_AUTH_EXPIRED path={}", path);
            return WebFluxResponseUtils.writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다.");
        } catch (JwtException e) {
            log.warn("event=GATEWAY_AUTH_INVALID path={} reason={}", path, e.getClass().getSimpleName());
            return WebFluxResponseUtils.writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다.");
        }

        String userId = claims.getSubject();
        String role = claims.get("role", String.class);
        String hubId = claims.get("hubId", String.class);
        String companyId = claims.get("companyId", String.class);

        // 필수 Claim(userId, role) null 검증
        if (userId == null || role == null) {
            log.warn("event=GATEWAY_AUTH_CLAIM_MISSING path={} userIdPresent={} rolePresent={}",
                    path,
                    userId != null,
                    role != null
            );
            return WebFluxResponseUtils.writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "토큰에 필수 정보가 누락되었습니다.");
        }

        // Redis 블랙리스트 체크 (AT 블랙리스트 + 삭제된 유저 차단) - 병렬 조회
        Mono<Boolean> tokenBlacklisted = redisTemplate.hasKey(BL_PREFIX + token)
                .onErrorMap(e -> new RedisUnavailableException());
        Mono<Boolean> userBlocked = redisTemplate.hasKey(BL_USER_PREFIX + userId)
                .onErrorMap(e -> new RedisUnavailableException());

        return Mono.zip(tokenBlacklisted, userBlocked)
                .flatMap(tuple -> {
                    if (Boolean.TRUE.equals(tuple.getT1()) || Boolean.TRUE.equals(tuple.getT2())) {
                        log.warn("event=GATEWAY_AUTH_BLACKLISTED path={} userId={} tokenBlacklisted={} userBlocked={}",
                                path,
                                userId,
                                tuple.getT1(),
                                tuple.getT2()
                        );
                        return WebFluxResponseUtils.writeErrorResponse(exchange, HttpStatus.UNAUTHORIZED, "인증이 만료되었습니다.");
                    }

                    // 헤더 스푸핑 방지: 클라이언트가 임의로 삽입한 헤더를 먼저 제거 후 JWT 기반 값으로 덮어씀
                    ServerHttpRequest.Builder requestMutator = exchange.getRequest().mutate()
                            .headers(headers -> INTERNAL_HEADERS.forEach(headers::remove))
                            .header("X-User-Id", userId)
                            .header("X-User-Role", role);

                    // null인 경우 헤더 미포함 (MASTER는 X-Hub-Id, X-Company-Id 없음)
                    if (hubId != null) requestMutator.header("X-Hub-Id", hubId);
                    if (companyId != null) requestMutator.header("X-Company-Id", companyId);

                    exchange.getAttributes().put("isAuthenticated", true);

                    ServerWebExchange mutatedExchange = exchange.mutate()
                            .request(requestMutator.build())
                            .build();

                    return chain.filter(mutatedExchange);
                })
                .onErrorResume(RedisUnavailableException.class, e -> {
                    log.warn("event=GATEWAY_REDIS_UNAVAILABLE path={}", path);
                    return WebFluxResponseUtils.writeErrorResponse(exchange, HttpStatus.SERVICE_UNAVAILABLE, "서비스가 일시적으로 불가합니다.");
                });
    }

    @Override
    public int getOrder() {
        return -1;
    }

    // 클라이언트가 임의로 삽입한 내부 헤더를 제거 (헤더 스푸핑 방지)
    private ServerWebExchange sanitizeHeaders(ServerWebExchange exchange) {
        ServerHttpRequest sanitized = exchange.getRequest().mutate()
                .headers(headers -> INTERNAL_HEADERS.forEach(headers::remove))
                .build();
        return exchange.mutate().request(sanitized).build();
    }
}
