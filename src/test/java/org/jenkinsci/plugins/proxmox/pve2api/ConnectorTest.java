package org.jenkinsci.plugins.proxmox.pve2api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Connector}.
 * Validates constants, timeout configuration, and interface contracts.
 */
class ConnectorTest {

    @Test
    void wait_time_is_one_second() {
        assertThat(Connector.WAIT_TIME_MS, is(1000L));
    }

    @Test
    void implements_autocloseable() {
        assertTrue(
                AutoCloseable.class.isAssignableFrom(Connector.class),
                "Connector must implement AutoCloseable for proper resource cleanup");
    }
}
