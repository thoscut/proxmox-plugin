package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Datacenter}.
 * Validates null parameter guards return empty collections.
 */
class DatacenterTest {

    @Test
    void getQemuMachines_returns_empty_for_null_node() {
        Datacenter dc = new Datacenter("host", "user", "pve", null, false);
        Map<Integer, String> result = dc.getQemuMachines(null);
        assertThat(result.isEmpty(), is(true));
    }

    @Test
    void getQemuMachines_returns_empty_for_empty_node() {
        Datacenter dc = new Datacenter("host", "user", "pve", null, false);
        Map<Integer, String> result = dc.getQemuMachines("");
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

    @Test
    void cloud_names_are_unique_and_url_safe() {
        Datacenter a = new Datacenter("pve1.example.com:8006", "admin", "pam", null, false);
        Datacenter b = new Datacenter("pve2.example.com", "admin", "pam", null, false);
        assertThat(a.name, is("proxmox-pve1.example.com_8006-admin-pam"));
        assertThat(a.name.equals(b.name), is(false));
    }

    @Test
    void credential_based_cloud_name_uses_the_credential_id() {
        Datacenter dc = new Datacenter("pve.example.com", null, null, null, "proxmox-token", false);
        assertThat(dc.name, is("proxmox-pve.example.com-proxmox-token"));
        assertThat(dc.getCredentialsId(), is("proxmox-token"));
    }

    @Test
    void blank_credential_id_is_treated_as_absent() {
        Datacenter dc = new Datacenter("host", "admin", "pam", null, "  ", false);
        assertThat(dc.getCredentialsId(), is((String) null));
        assertThat(dc.getDatacenterDescription(), is("admin@pam - host"));
    }

    @Test
    void description_falls_back_to_credential_id_without_jenkins() {
        Datacenter dc = new Datacenter("host", null, null, null, "proxmox-token", false);
        assertThat(dc.getDatacenterDescription(), is("proxmox-token - host"));
    }

    @Test
    void cloud_name_only_contains_url_safe_characters() {
        assertThat(Datacenter.cloudName("a b", "c/d", "h@st:1"), is("proxmox-h_st_1-a_b-c_d"));
    }
}
