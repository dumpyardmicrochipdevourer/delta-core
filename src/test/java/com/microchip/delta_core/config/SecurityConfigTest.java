package com.microchip.delta_core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigTest {

    @Test
    void roleClaimBecomesAnAuthority() {
        assertEquals(List.of("ROLE_ADMIN"), authorities("ADMIN"));
        assertEquals(List.of("ROLE_USER"), authorities("USER"));
    }

    @Test
    void noRoleClaimMeansNoAuthority() {
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject("u")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertFalse(SecurityConfig.jwtAuthenticationConverter().convert(token).getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().startsWith("ROLE_")));
    }

    private static List<String> authorities(String role) {
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject("u").claim("role", role)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        // Spring Security adds its own FACTOR_* authority next to the roles
        return SecurityConfig.jwtAuthenticationConverter().convert(token).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).filter(a -> a.startsWith("ROLE_")).toList();
    }
}
