package org.jenkinsci.plugins.proxmox.pve2api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kong.unirest.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link Connector}.
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

    @Test
    void ticket_is_renewed_before_proxmox_expires_it() {
        assertTrue(Connector.TICKET_LIFETIME_MS < 2 * 60 * 60 * 1000L);
    }

    @ParameterizedTest
    @CsvSource({
        "pve.example.com, https://pve.example.com:8006/api2/json/",
        "pve.example.com:8443, https://pve.example.com:8443/api2/json/",
        "https://pve.example.com:8006/, https://pve.example.com:8006/api2/json/",
        "' pve.example.com ', https://pve.example.com:8006/api2/json/",
        "10.0.0.1, https://10.0.0.1:8006/api2/json/",
        "'[::1]:8007', 'https://[::1]:8007/api2/json/'",
        "https://pve.example.com:8006/api2/json, https://pve.example.com:8006/api2/json/",
        "http://pve.example.com, https://pve.example.com:8006/api2/json/",
        "pve.example.com/, https://pve.example.com:8006/api2/json/",
        "pve_1, https://pve_1:8006/api2/json/"
    })
    void base_url_is_built_from_hostname(String hostname, String expected) {
        try (Connector connector = new Connector(hostname, "user", "pve", null, false)) {
            assertThat(connector.baseURL, is(expected));
        }
    }

    @Test
    void normalize_host_handles_null() {
        assertThat(Connector.normalizeHost(null), is(""));
    }

    @Test
    void path_segments_keep_regular_names() throws ProxmoxException {
        assertThat(Connector.encodePathSegment("pve-node_1.local"), is("pve-node_1.local"));
        assertThat(
                Connector.encodePathSegment("UPID:pve:00001234:00ABCDEF:5F000000:qmstart:100:root@pam:"),
                is("UPID:pve:00001234:00ABCDEF:5F000000:qmstart:100:root@pam:"));
    }

    @Test
    void path_segments_cannot_change_the_endpoint() throws ProxmoxException {
        assertThat(Connector.encodePathSegment("pve/qemu/999/status/stop?"), is("pve%2Fqemu%2F999%2Fstatus%2Fstop%3F"));
        assertThat(Connector.encodePathSegment("a#b c%"), is("a%23b%20c%25"));
        assertThat(Connector.encodePathSegment("snap \u00fc+"), is("snap%20%C3%BC%2B"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"pve/qemu/999/status/stop?", "pve node", "-pve", "", "pve%2Fx"})
    void invalid_node_names_are_rejected(String node) {
        try (Connector connector = new Connector("localhost", "user", "pve", null, false)) {
            assertThrows(ProxmoxException.class, () -> connector.startQemuMachine(node, 100));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1snap", "snap/../x", "snap name", "snap.1", ""})
    void invalid_snapshot_names_are_rejected(String snapshot) {
        try (Connector connector = new Connector("localhost", "user", "pve", null, false)) {
            assertThrows(ProxmoxException.class, () -> connector.rollbackQemuMachineSnapshot("pve", 100, snapshot));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-upid", "UPID:pve/x", "UPID:pve:00001234 x", ""})
    void invalid_task_ids_are_rejected(String taskId) {
        try (Connector connector = new Connector("localhost", "user", "pve", null, false)) {
            assertThrows(ProxmoxException.class, () -> connector.getTaskStatus("pve", taskId));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", ".."})
    void invalid_path_segments_are_rejected(String segment) {
        assertThrows(ProxmoxException.class, () -> Connector.encodePathSegment(segment));
    }

    @Test
    void null_path_segment_is_rejected() {
        assertThrows(ProxmoxException.class, () -> Connector.encodePathSegment(null));
    }

    @Test
    void task_success_requires_stopped_and_ok() {
        assertTrue(Connector.isTaskSuccessful(new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"OK\"}")));
        assertFalse(Connector.isTaskSuccessful(
                new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"VM is locked (backup)\"}")));
        assertFalse(Connector.isTaskSuccessful(new JSONObject("{\"status\":\"running\"}")));
        assertFalse(Connector.isTaskSuccessful(new JSONObject("{}")));
        assertFalse(Connector.isTaskSuccessful(null));
    }

    @Test
    void invalid_vmid_is_rejected_before_any_request() {
        try (Connector connector = new Connector("localhost", "user", "pve", null, false)) {
            assertThrows(ProxmoxException.class, () -> connector.startQemuMachine("pve", null));
            assertThrows(ProxmoxException.class, () -> connector.stopQemuMachine("pve", 0));
            assertThrows(ProxmoxException.class, () -> connector.rollbackQemuMachineSnapshot("pve", 100, ".."));
        }
    }
}
