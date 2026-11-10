package org.jenkinsci.plugins.proxmox.pve2api;

import hudson.util.Secret;
import java.util.Objects;

/**
 * How the {@link Connector} authenticates against the Proxmox VE API: either with a
 * username and password (ticket login) or with an API token.
 */
public abstract class ProxmoxAuth {

    private ProxmoxAuth() {}

    /**
     * @return {@code user@realm} of the account the API is accessed with.
     */
    public abstract String getUserAtRealm();

    /**
     * @return a value that identifies these credentials, so connectors can be shared.
     *     It contains the secret and must never be logged.
     */
    abstract String cacheKey();

    /**
     * @param username the Proxmox user, optionally already including the realm ({@code root@pam})
     * @param realm the realm to use when {@code username} does not contain one
     */
    public static ProxmoxAuth usernamePassword(String username, String realm, Secret password) {
        return new UsernamePassword(username, realm, password);
    }

    /**
     * @param token a Proxmox API token in the form {@code user@realm!tokenid=uuid}, optionally with a
     *     leading {@code PVEAPIToken=} as shown in the Proxmox UI
     */
    public static ProxmoxAuth apiToken(Secret token) {
        return new ApiToken(token);
    }

    static String[] splitUserAndRealm(String username, String defaultRealm) {
        String user = username == null ? "" : username.trim();
        String realm = defaultRealm == null ? "" : defaultRealm.trim();
        int at = user.lastIndexOf('@');
        if (at > 0) {
            realm = user.substring(at + 1);
            user = user.substring(0, at);
        }
        return new String[] {user, realm};
    }

    static final class UsernamePassword extends ProxmoxAuth {
        private final String user;
        private final String realm;
        private final Secret password;

        UsernamePassword(String username, String realm, Secret password) {
            String[] parts = splitUserAndRealm(username, realm);
            this.user = parts[0];
            this.realm = parts[1];
            this.password = password;
        }

        @Override
        public String getUserAtRealm() {
            return user + "@" + realm;
        }

        String getPlainPassword() {
            return password == null ? "" : password.getPlainText();
        }

        @Override
        String cacheKey() {
            return "password:" + getUserAtRealm() + ":" + getPlainPassword();
        }
    }

    static final class ApiToken extends ProxmoxAuth {
        private static final String PREFIX = "PVEAPIToken=";

        private final String token;

        ApiToken(Secret token) {
            String value = token == null ? "" : token.getPlainText().trim();
            if (value.startsWith(PREFIX)) {
                value = value.substring(PREFIX.length()).trim();
            }
            this.token = value;
        }

        /** Shown instead of a user name when the secret is not a token, so the secret itself never leaks. */
        static final String INVALID_TOKEN = "invalid-token";

        @Override
        public String getUserAtRealm() {
            if (!isValidFormat()) {
                return INVALID_TOKEN;
            }
            return token.substring(0, token.indexOf('!'));
        }

        /** @return the value of the {@code Authorization} header. */
        String getAuthorizationHeader() {
            return PREFIX + token;
        }

        boolean isValidFormat() {
            int bang = token.indexOf('!');
            int eq = token.indexOf('=');
            return bang > 0 && eq > bang + 1 && eq < token.length() - 1 && token.indexOf('@') > 0;
        }

        @Override
        String cacheKey() {
            return "token:" + token;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ProxmoxAuth && cacheKey().equals(((ProxmoxAuth) o).cacheKey());
    }

    @Override
    public int hashCode() {
        return Objects.hash(cacheKey());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + getUserAtRealm() + "]";
    }
}
