package com.dating.user.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

@Service
public class TokenService {

    private final SecretKey accessTokenKey;
    private final long accessTokenExpiresInSeconds;
    private final long refreshTokenExpiresInSeconds;
    private final SecureRandom secureRandom;

    public TokenService() {
        this("default-secret-key-change-in-production-32bytes", 7200, 2592000);
    }

    public TokenService(String secret, long accessTokenExpiresInSeconds, long refreshTokenExpiresInSeconds) {
        this.accessTokenKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiresInSeconds = accessTokenExpiresInSeconds;
        this.refreshTokenExpiresInSeconds = refreshTokenExpiresInSeconds;
        this.secureRandom = new SecureRandom();
    }

    public String generateAccessToken(String userId, String userType) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpiresInSeconds * 1000);
        return Jwts.builder()
                .subject(userId)
                .claim("userType", userType)
                .claim("tokenType", "ACCESS")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(accessTokenKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(accessTokenKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isTokenValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    public String extractUserId(String token) {
        return parseToken(token).getSubject();
    }

    public long getAccessTokenExpiresInSeconds() {
        return accessTokenExpiresInSeconds;
    }

    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashToken(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public boolean verifyTokenHash(String token, String hash) {
        return hashToken(token).equals(hash);
    }

    public long getRefreshTokenExpiresInSeconds() {
        return refreshTokenExpiresInSeconds;
    }
}
