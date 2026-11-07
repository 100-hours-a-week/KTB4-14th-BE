package com.audigo.domain.auth.service;

public class AuthSessionUnavailableException extends RuntimeException {

    public AuthSessionUnavailableException(Throwable cause) {
        super("Refresh session store is unavailable.", cause);
    }
}
