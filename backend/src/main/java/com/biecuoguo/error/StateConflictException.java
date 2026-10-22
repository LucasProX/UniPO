package com.biecuoguo.error;

/** A fixed, user-safe conflict description; never include payloads or dependency error text. */
public final class StateConflictException extends RuntimeException {
    public StateConflictException(String message) { super(message); }
}
