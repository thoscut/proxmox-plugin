package org.jenkinsci.plugins.proxmox.pve2api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

import hudson.util.Secret;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ConnectorPoolTest {

    @AfterEach
    void closeAll() {
        ConnectorPool.closeAll();
    }

    private static ConnectorPool.Key key(String host, String password) {
        // Plain, non-base64 strings stay plain text in Secret.fromString without a running Jenkins.
        ProxmoxAuth auth = ProxmoxAuth.usernamePassword("jenkins", "pve", Secret.fromString(password));
        return new ConnectorPool.Key(host, auth, false);
    }

    @Test
    void same_configuration_shares_one_connector() {
        ConnectorPool.Key key = key("pve1", "pw");
        Set<ConnectorPool.Key> inUse = Collections.singleton(key);
        Connector first = ConnectorPool.acquire(key, inUse);
        Connector second = ConnectorPool.acquire(key("pve1", "pw"), inUse);
        assertThat(second, is(sameInstance(first)));
        assertThat(ConnectorPool.size(), is(1));
    }

    @Test
    void changed_configuration_gets_a_new_connector_and_the_old_one_is_closed() {
        ConnectorPool.Key old = key("pve1", "old");
        Connector oldConnector = ConnectorPool.acquire(old, Collections.singleton(old));

        ConnectorPool.Key updated = key("pve1", "new");
        Connector newConnector = ConnectorPool.acquire(updated, Collections.singleton(updated));

        assertThat(newConnector, is(not(sameInstance(oldConnector))));
        assertThat(ConnectorPool.size(), is(1));
    }

    @Test
    void connectors_of_other_configured_datacenters_are_kept() {
        ConnectorPool.Key a = key("pve1", "pw");
        ConnectorPool.Key b = key("pve2", "pw");
        Set<ConnectorPool.Key> inUse = new HashSet<>();
        inUse.add(a);
        inUse.add(b);
        Connector first = ConnectorPool.acquire(a, inUse);
        ConnectorPool.acquire(b, inUse);
        assertThat(ConnectorPool.acquire(a, inUse), is(sameInstance(first)));
        assertThat(ConnectorPool.size(), is(2));
    }

    @Test
    void keys_ignore_ssl_flag_and_host_matter() {
        ProxmoxAuth auth = ProxmoxAuth.usernamePassword("jenkins", "pve", Secret.fromString("pw"));
        assertThat(new ConnectorPool.Key("pve1", auth, true), is(not(new ConnectorPool.Key("pve1", auth, false))));
        assertThat(new ConnectorPool.Key("pve1", auth, null), is(new ConnectorPool.Key("pve1", auth, false)));
        assertThat(new ConnectorPool.Key("pve1", auth, false).toString().contains("pw"), is(false));
    }
}
