package org.jenkinsci.plugins.proxmox.pve2api;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shares one {@link Connector} (and so one HTTP client and one login ticket) per distinct
 * connection configuration, and closes connectors whose configuration is no longer in use.
 *
 * <p>Jenkins creates a new cloud object every time a datacenter is saved, so caching the
 * connector in the cloud object would leak an HTTP client per save.
 */
public final class ConnectorPool {

    private static final Logger LOGGER = Logger.getLogger(ConnectorPool.class.getName());

    /** Everything a connector's behaviour depends on. */
    public static final class Key {
        private final String hostname;
        private final ProxmoxAuth auth;
        private final boolean ignoreSSL;

        public Key(String hostname, ProxmoxAuth auth, Boolean ignoreSSL) {
            this.hostname = hostname == null ? "" : hostname;
            this.auth = auth;
            this.ignoreSSL = Boolean.TRUE.equals(ignoreSSL);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key)) {
                return false;
            }
            Key other = (Key) o;
            return hostname.equals(other.hostname) && ignoreSSL == other.ignoreSSL && Objects.equals(auth, other.auth);
        }

        @Override
        public int hashCode() {
            return Objects.hash(hostname, auth, ignoreSSL);
        }

        @Override
        public String toString() {
            // Deliberately without the credentials.
            return hostname + " (" + (auth == null ? "no credentials" : auth.getUserAtRealm()) + ")";
        }
    }

    private static final Map<Key, Connector> CONNECTORS = new HashMap<>();

    private ConnectorPool() {}

    /**
     * @param key the configuration to connect with
     * @param inUse the configurations of all currently configured datacenters; connectors for
     *     any other configuration are closed
     * @return the shared connector for {@code key}
     */
    public static synchronized Connector acquire(Key key, Set<Key> inUse) {
        Iterator<Map.Entry<Key, Connector>> it = CONNECTORS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Connector> entry = it.next();
            if (!entry.getKey().equals(key) && !inUse.contains(entry.getKey())) {
                LOGGER.log(Level.FINE, "Closing unused Proxmox connection to {0}", entry.getKey());
                it.remove();
                entry.getValue().close();
            }
        }
        return CONNECTORS.computeIfAbsent(key, k -> new Connector(k.hostname, k.auth, k.ignoreSSL));
    }

    /** Closes every pooled connector; used by tests and on shutdown. */
    public static synchronized void closeAll() {
        for (Connector connector : CONNECTORS.values()) {
            connector.close();
        }
        CONNECTORS.clear();
    }

    static synchronized int size() {
        return CONNECTORS.size();
    }
}
