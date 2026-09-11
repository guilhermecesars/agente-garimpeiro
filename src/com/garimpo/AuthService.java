package com.garimpo;

public class AuthService {

    public static boolean isUserActive(String idToken) {
        if (idToken == null || idToken.trim().isEmpty()) {
            return false;
        }

        String token = idToken.trim();
        if (token.startsWith("Bearer ")) {
            token = token.substring(7).trim();
        }

        return UserStore.tokenValido(token);
    }
}
