package com.eda.discovery.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The status vocabulary's routing predicate, as a truth table.
 *
 * <p>This is the contract the gateway and the choreography layer both encode, so it is
 * pinned here rather than left implicit. If a fifth status is ever added, this test is
 * where the decision about its routability has to be made explicitly.
 */
class ServiceStatusTest {

    @ParameterizedTest(name = "isRoutable(\"{0}\") == {1}")
    @CsvSource({
            "healthy,     true",
            "not-ready,   false",
            "unavailable, false",
            "unknown,     false"
    })
    @DisplayName("exactly one of the four platform statuses is routable")
    void truthTable(String status, boolean routable) {
        assertThat(ServiceStatus.isRoutable(status)).isEqualTo(routable);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "HEALTHY", "Healthy", " healthy", "healthy ", "degraded", "unhealthy"})
    @DisplayName("null, blank, mis-cased and legacy values are never routable")
    void nothingElseIsRoutable(String status) {
        assertThat(ServiceStatus.isRoutable(status)).isFalse();
    }

    @Test
    @DisplayName("the vocabulary is exactly these four values")
    void vocabularyIsClosed() {
        assertThat(ServiceStatus.UNKNOWN).isEqualTo("unknown");
        assertThat(ServiceStatus.HEALTHY).isEqualTo("healthy");
        assertThat(ServiceStatus.NOT_READY).isEqualTo("not-ready");
        assertThat(ServiceStatus.UNAVAILABLE).isEqualTo("unavailable");
    }
}
