package vn.reconhub.core.domain;

/** A write-off request kept on the exception until it is approved or rejected. */
public record WriteOff(String requestedBy, String requestedById, String reason, String note, long amount) {}
