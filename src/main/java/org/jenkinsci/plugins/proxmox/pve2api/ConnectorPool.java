package org.jenkinsci.plugins.proxmox.pve2api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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

    /**
     * A connector whose configuration disappeared may still be used by a launch that started
     * before, so it is closed only after the longest request sequence (two tasks, e.g. rollback
     * and start, plus a few plain requests) can have finished.
     */
    static final long RETIRE_GRACE_MS = 2 * Connector.DEFAULT_TASK_TIMEOUT_MS + 4 * Connector.SOCKET_TIMEOUT_MS;

    private static final class Retired {
        final Connector connector;
        final long retiredAtMs;

        Retired(Connector connector, long retiredAtMs) {
            this.connector = connector;
            this.retiredAtMs = retiredAtMs;
        }
    }

    private static final Map<Key, Connector> CONNECTORS = new HashMap<>();
    private static final List<Retired> RETIRED = new ArrayList<>();

    private ConnectorPool() {}

    /**
     * @param key the configuration to connect with
     * @param inUse the configurations of all currently configured datacenters; connectors for
     *     any other configuration are retired and closed after a grace period
     * @return the shared connector for {@code key}
     */
    public static synchronized Connector acquire(Key key, Set<Key> inUse) {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Key, Connector>> it = CONNECTORS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Connector> entry = it.next();
            if (!entry.getKey().equals(key) && !inUse.contains(entry.getKey())) {
                LOGGER.log(Level.FINE, "Retiring unused Proxmox connection to {0}", entry.getKey());
                it.remove();
                RETIRED.add(new Retired(entry.getValue(), now));
            }
        }
        closeRetired(now);
        return CONNECTORS.computeIfAbsent(key, k -> new Connector(k.hostname, k.auth, k.ignoreSSL));
    }

    private static void closeRetired(long now) {
        Iterator<Retired> it = RETIRED.iterator();
        while (it.hasNext()) {
            Retired retired = it.next();
            if (now - retired.retiredAtMs >= RETIRE_GRACE_MS) {
                it.remove();
                retired.connector.close();
            }
        }
    }

    /** Closes every pooled and retired connector. Called on Jenkins shutdown and by tests. */
    public static synchronized void closeAll() {
        for (Connector connector : CONNECTORS.values()) {
            connector.close();
        }
        CONNECTORS.clear();
        for (Retired retired : RETIRED) {
            retired.connector.close();
        }
        RETIRED.clear();
    }

    static synchronized int size() {
        return CONNECTORS.size();
    }

    static synchronized int retiredSize() {
        return RETIRED.size();
    }
}
