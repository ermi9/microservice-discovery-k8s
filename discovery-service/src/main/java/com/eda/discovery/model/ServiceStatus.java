package com.eda.discovery.model;

/**
 * The single status vocabulary for the whole system.
 *
 * <p>Every writer of a service's status — the HTTP health check, the Kubernetes Watch,
 * the polling fallback — uses these four values and no others, so a consumer can decide
 * routability from the value alone rather than guessing at which writer produced it.
 * {@link #isRoutable} is that decision.
 *
 * <p>Kept as String constants rather than an enum because the value crosses a JSON
 * wire boundary to consumers that own their own DTOs (the gateway, and the choreography
 * layer); a typo'd or future value must deserialize into <em>something</em> rather than
 * blowing up the consumer.
 */
public final class ServiceStatus {

    /** Registered, but no health signal has been observed yet. Not routable. */
    public static final String UNKNOWN = "unknown";

    /** Observed ready and serving. The only routable status. */
    public static final String HEALTHY = "healthy";

    /**
     * Reachable but not currently serving — pod running yet not Ready, or an HTTP
     * probe failing below the configured failure threshold. Expected to be transient.
     */
    public static final String NOT_READY = "not-ready";

    /** Gone or failed: pod deleted/terminated, or probe failures past the threshold. */
    public static final String UNAVAILABLE = "unavailable";

    private ServiceStatus() {}

    public static boolean isRoutable(String status) {
        return HEALTHY.equals(status);
    }
}
