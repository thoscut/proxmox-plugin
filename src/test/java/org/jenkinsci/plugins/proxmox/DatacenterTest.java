package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Datacenter}.
 * Validates null parameter guards return empty collections.
 */
class DatacenterTest {

    @Test
    void getQemuMachines_returns_empty_for_null_node() {
        Datacenter dc = new Datacenter("host", "user", "pve", null, false);
        HashMap<String, Integer> result = dc.getQemuMachines(null);
        assertThat(result.isEmpty(), is(true));
    }

    @Test
    void getQemuMachines_returns_empty_for_empty_node() {
        Datacenter dc = new Datacenter("host", "user", "pve", null, false);
        HashMap<String, Integer> result = dc.getQemuMachines("");
        assertThat(result.isEmpty(), is(true));
    }

    @Test
    void getQemuMachineSnapshots_returns_empty_for_null_node() {
        Datacenter dc = new Datacenter("host", "user", "pve", null, false);
        List<String> result = dc.getQemuMachineSnapshots(null, 1);
        assertThat(result, is(empty()));
    }

    @Test
    void getQemuMachineSnapshots_returns_empty_for_null_vmid() {
        Datacenter dc = new Datacenter("host", "user", "pve", null, false);
        List<String> result = dc.getQemuMachineSnapshots("node1", null);
        assertThat(result, is(empty()));
    }

    @Test
    void getQemuMachineSnapshots_returns_empty_for_invalid_vmid() {
        Datacenter dc = new Datacenter("host", "user", "pve", null, false);
        List<String> result = dc.getQemuMachineSnapshots("node1", 0);
        assertThat(result, is(empty()));
    }

    @Test
    void getQemuMachineSnapshots_returns_empty_for_empty_node() {
        Datacenter dc = new Datacenter("host", "user", "pve", null, false);
        List<String> result = dc.getQemuMachineSnapshots("", 1);
        assertThat(result, is(empty()));
    }

    @Test
    void getDatacenterDescription_format() {
        Datacenter dc = new Datacenter("myhost", "admin", "pam", null, false);
        assertThat(dc.getDatacenterDescription(), is("admin@pam - myhost"));
    }
}
