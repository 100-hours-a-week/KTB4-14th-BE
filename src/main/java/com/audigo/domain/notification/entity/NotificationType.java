package com.audigo.domain.notification.entity;

public enum NotificationType {
    MATCH_SUCCESS,
    NEW_MESSAGE,
    @Deprecated(forRemoval = false)
    TRAVEL_BEFORE,
    TRAVEL_COMPLETE,
    TRAVEL_FAILED
}
