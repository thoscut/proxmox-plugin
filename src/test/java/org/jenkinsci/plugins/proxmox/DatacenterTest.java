package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.util.Secret;
import java.util.List;
import java.util.Map;
import org.jenkinsci.plugins.proxmox.pve2api.ConnectorPool;
import org.jenkinsci.plugins.proxmox.pve2api.ProxmoxAuth;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Datacenter}.
 * Validates null parameter guards return empty collections.
 */
class DatacenterTest {

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
    void find_by_description_needs_a_running_jenkins() {
        assertThat(Datacenter.findByDescription("admin@pam - host"), is((Datacenter) null));
        assertThat(Datacenter.findByDescription(null), is((Datacenter) null));
    }

    @Test
    void cloud_name_only_contains_url_safe_characters() {
        assertThat(Datacenter.cloudName("h@st:1", "a b-c/d"), is("proxmox-h_st_1-a_b-c_d"));
    }

    @Test
    void resolve_auth_without_credential_uses_username_realm_and_password() {
        ProxmoxAuth auth = Datacenter.resolveAuth("admin", "pam", null, null);
        assertThat(auth.getUserAtRealm(), is("admin@pam"));
        assertThat(auth, is(ProxmoxAuth.usernamePassword("admin", "pam", null)));
        // A blank credential id counts as "no credential".
        assertThat(Datacenter.resolveAuth("admin", "pam", null, "  "), is(auth));
        // A realm inside the user name wins over the realm field.
        assertThat(Datacenter.resolveAuth("root@pam", "pve", null, null).getUserAtRealm(), is("root@pam"));
    }

    @Test
    void resolve_auth_with_credential_needs_a_running_jenkins() {
        IllegalStateException e = assertThrows(
                IllegalStateException.class, () -> Datacenter.resolveAuth(null, null, null, "proxmox-token"));
        assertThat(e.getMessage(), containsString("proxmox-token"));
    }

    @Test
    void instance_resolve_auth_without_credential_is_stable() {
        Datacenter dc = new Datacenter("host", "admin", "pam", null, false);
        assertThat(dc.resolveAuth(), is(dc.resolveAuth()));
        assertThat(dc.resolveAuth().getUserAtRealm(), is("admin@pam"));
    }

    @Test
    void equally_configured_datacenters_share_a_connector_key() {
        Datacenter a = new Datacenter("host", "admin", "pam", Secret.fromString("pw"), false);
        Datacenter b = new Datacenter("host", "admin@pam", "ignored", Secret.fromString("pw"), null);
        Datacenter c = new Datacenter("host", "admin", "pam", Secret.fromString("other"), false);
        assertThat(a.connectorKey(), is(b.connectorKey()));
        assertThat(a.connectorKey(), is(not(c.connectorKey())));
        ProxmoxAuth auth = ProxmoxAuth.usernamePassword("admin", "pam", Secret.fromString("pw"));
        assertThat(a.connectorKey(), is(new ConnectorPool.Key("host", auth, false)));
    }

    @Test
    void unresolvable_credential_fails_fast_without_touching_the_network() {
        Datacenter dc = new Datacenter("host", null, null, null, "proxmox-token", false);
        assertThrows(IllegalStateException.class, dc::resolveAuth);
        assertThrows(IllegalStateException.class, dc::proxmoxInstance);
        // The list helpers swallow the failure and return empty collections for the dropdowns.
        assertThat(dc.getNodes(), is(empty()));
        assertThat(dc.getQemuMachines("node1").isEmpty(), is(true));
        // Without a node the virtual machines of all nodes are listed
        assertThat(dc.getQemuMachines(null).isEmpty(), is(true));
        assertThat(dc.getQemuMachines("").isEmpty(), is(true));
        assertThat(dc.getQemuMachineSnapshots("node1", 100), is(empty()));
        // The node of a virtual machine is optional
        assertThat(dc.getQemuMachineSnapshots(null, 100), is(empty()));
        assertThat(dc.getQemuMachineSnapshots("", 100), is(empty()));
    }

    @Test
    void blank_description_is_never_matched() {
        assertThat(Datacenter.findByDescription(""), is((Datacenter) null));
        assertThat(Datacenter.findByDescription("   "), is((Datacenter) null));
    }
}
