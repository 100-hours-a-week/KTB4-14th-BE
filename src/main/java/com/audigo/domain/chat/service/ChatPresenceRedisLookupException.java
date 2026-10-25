package com.audigo.domain.chat.service;

import org.springframework.dao.DataAccessException;

public final class ChatPresenceRedisLookupException extends DataAccessException {

    private static final long serialVersionUID = 1L;

    private final int queryIndex;
    private final int queryTotal;
    private final long elapsedMillis;

    public ChatPresenceRedisLookupException(
            int queryIndex,
            int queryTotal,
            long elapsedMillis,
            DataAccessException cause
    ) {
        super("Redis chat presence lookup failed at item " + queryIndex + " of " + queryTotal, cause);
        this.queryIndex = queryIndex;
        this.queryTotal = queryTotal;
        this.elapsedMillis = elapsedMillis;
    }

    public int queryIndex() {
        return queryIndex;
    }

    public int queryTotal() {
        return queryTotal;
    }

    public long elapsedMillis() {
        return elapsedMillis;
    }
}
