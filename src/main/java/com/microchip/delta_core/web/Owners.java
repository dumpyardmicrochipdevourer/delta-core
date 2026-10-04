package com.microchip.delta_core.web;

import com.microchip.delta_core.config.DeltaProperties;
import com.microchip.delta_core.domain.Owner;
import com.microchip.delta_core.service.Refusal;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class Owners {

    static final String BOT_TOKEN = "X-Delta-Token";
    static final String BOT_USER = "X-Delta-User";

    private final String botToken;

    public Owners(DeltaProperties properties) {
        this.botToken = properties.bot().token();
    }

    public Owner of(HttpServletRequest request, Jwt jwt) {
        if (isBot(request)) {
            String user = request.getHeader(BOT_USER);
            if (user == null || !user.matches("^[A-Za-z0-9_-]{1,32}$")) {
                throw new Refusal(400, "bad_user", "имя должно быть [A-Za-z0-9_-], 1-32 символа");
            }
            return new Owner(Owner.Kind.BOT, user);
        }
        if (jwt != null) {
            return new Owner(Owner.Kind.USER, jwt.getSubject());
        }
        // the service is only reachable through the gateway, which sets X-Real-IP
        String ip = request.getHeader("X-Real-IP");
        return new Owner(Owner.Kind.ANON, ip != null && !ip.isBlank() ? ip : request.getRemoteAddr());
    }

    // A token that is present must be right: a wrong one is refused, never treated as a visitor
    public boolean isBot(HttpServletRequest request) {
        String token = request.getHeader(BOT_TOKEN);
        if (token == null) {
            return false;
        }
        if (botToken.isBlank() || !MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8), botToken.getBytes(StandardCharsets.UTF_8))) {
            throw new Refusal(401, "bad_token", "неверный токен");
        }
        return true;
    }
}
