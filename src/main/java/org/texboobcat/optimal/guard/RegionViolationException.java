package org.texboobcat.optimal.guard;

// Thrown in strict mode when a region thread touches state it does not own.
public class RegionViolationException extends IllegalStateException {

    public RegionViolationException(String message) {
        super(message);
    }
}
