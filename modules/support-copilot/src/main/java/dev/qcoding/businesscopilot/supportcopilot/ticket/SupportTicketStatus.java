package dev.qcoding.businesscopilot.supportcopilot.ticket;

/** Explicit support ticket lifecycle. */
public enum SupportTicketStatus {
    RECEIVED,
    CLASSIFIED,
    DRAFTED,
    NEEDS_HUMAN,
    CONFIRMED,
    CANCELED,
    CLOSED,
    FAILED
}
