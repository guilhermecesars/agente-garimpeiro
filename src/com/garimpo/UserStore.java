package com.garimpo;

import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cadastro de usuarios e "assinatura" em memoria.
 * O checkout aqui e simulado (sempre aprova na hora) - nao ha integracao
 * com nenhum gateway de pagamento real. Serve para demonstrar o fluxo de
 * acesso pago num case de portfolio, nao para cobrar dinheiro de verdade.
 */
public class UserStore {

    static class Usuario {
        final String senhaHash;
        boolean pago = false;
        String token = null;

        Usuario(String senhaHash) {
            this.senhaHash = senhaHash;
        }
    }

    private static final Map<String, Usuario> USUARIOS = new ConcurrentHashMap<>();
    private static final Map<String, String> EMAIL_POR_TOKEN = new ConcurrentHashMap<>();

    private static String normalizar(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static String hash(String texto) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(texto.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static synchronized boolean cadastrar(String email, String senha) {
        String chave = normalizar(email);
        if (chave.isEmpty() || USUARIOS.containsKey(chave)) {
            return false;
        }
        USUARIOS.put(chave, new Usuario(hash(senha)));
        return true;
    }

    public static synchronized boolean autenticar(String email, String senha) {
        Usuario u = USUARIOS.get(normalizar(email));
        return u != null && u.senhaHash.equals(hash(senha));
    }

    public static synchronized boolean jaAssinante(String email) {
        Usuario u = USUARIOS.get(normalizar(email));
        return u != null && u.pago;
    }

    /** Simula a aprovacao de um pagamento e emite um token de acesso. */
    public static synchronized String simularCheckout(String email) {
        Usuario u = USUARIOS.get(normalizar(email));
        if (u == null) return null;
        u.pago = true;
        String token = UUID.randomUUID().toString();
        u.token = token;
        EMAIL_POR_TOKEN.put(token, normalizar(email));
        return token;
    }

    public static boolean tokenValido(String token) {
        if (token == null || token.isBlank()) return false;
        String email = EMAIL_POR_TOKEN.get(token);
        if (email == null) return false;
        Usuario u = USUARIOS.get(email);
        return u != null && u.pago && token.equals(u.token);
    }
}
