package org.jenkinsci.plugins.proxmox.pve2api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.Secret;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProxmoxAuth}. Without a running Jenkins, {@link Secret#fromString} uses a
 * mock confidential store and keeps these plain strings as they are.
 */
class ProxmoxAuthTest {

    @Test
    void username_and_realm_are_joined() {
        ProxmoxAuth auth = ProxmoxAuth.usernamePassword("jenkins", "pve", Secret.fromString("pw"));
        assertThat(auth.getUserAtRealm(), is("jenkins@pve"));
        assertThat(((ProxmoxAuth.UsernamePassword) auth).getPlainPassword(), is("pw"));
    }

    @Test
    void realm_in_username_wins_over_realm_field() {
        ProxmoxAuth auth = ProxmoxAuth.usernamePassword("root@pam", "pve", null);
        assertThat(auth.getUserAtRealm(), is("root@pam"));
        assertThat(((ProxmoxAuth.UsernamePassword) auth).getPlainPassword(), is(""));
    }

    @Test
    void api_token_is_parsed() {
        ProxmoxAuth.ApiToken token = (ProxmoxAuth.ApiToken)
                ProxmoxAuth.apiToken(Secret.fromString("jenkins@pve!ci=11111111-2222-3333-4444-555555555555"));
        assertThat(token.getUserAtRealm(), is("jenkins@pve"));
        assertThat(
                token.getAuthorizationHeader(),
                is("PVEAPIToken=jenkins@pve!ci=11111111-2222-3333-4444-555555555555"));
        assertTrue(token.isValidFormat());
    }

    @Test
    void api_token_accepts_the_header_form_from_the_proxmox_ui() {
        Secret secret = Secret.fromString(" PVEAPIToken=jenkins@pve!ci=abcdef12-3456 ");
        ProxmoxAuth.ApiToken token = (ProxmoxAuth.ApiToken) ProxmoxAuth.apiToken(secret);
        assertThat(token.getAuthorizationHeader(), is("PVEAPIToken=jenkins@pve!ci=abcdef12-3456"));
        assertTrue(token.isValidFormat());
    }

    @Test
    void ldap_user_ids_with_an_at_sign_are_accepted() {
        ProxmoxAuth.ApiToken token = token("john@example.com@ldap!ci=11111111-2222-3333-4444-555555555555");
        assertTrue(token.isValidFormat());
        assertThat(token.getUserAtRealm(), is("john@example.com@ldap"));
    }

    @Test
    void malformed_api_tokens_are_detected() {
        assertFalse(token("just-a-password").isValidFormat());
        assertFalse(token("jenkins@pve!ci=").isValidFormat());
        assertFalse(token("jenkins@pve!ci=short").isValidFormat());
        assertFalse(token("jenkins@p!ci=11111111-2222-3333-4444-555555555555").isValidFormat());
        assertFalse(token("jenkins@pve!ci=11111111-2222-3333-4444-555555555555 x").isValidFormat());
        assertFalse(token("jenkins@pve/x!ci=11111111-2222-3333-4444-555555555555").isValidFormat());
        assertFalse(((ProxmoxAuth.ApiToken) ProxmoxAuth.apiToken(null)).isValidFormat());
    }

    @Test
    void malformed_api_token_never_shows_up_as_user_name() {
        // A secret text holding a plain password must not leak through descriptions or logs.
        ProxmoxAuth.ApiToken token = token("just-a-password");
        assertThat(token.getUserAtRealm(), is(ProxmoxAuth.ApiToken.INVALID_TOKEN));
        assertThat(token.toString().contains("just-a-password"), is(false));
        assertThat(token("p!ss").getUserAtRealm(), is(ProxmoxAuth.ApiToken.INVALID_TOKEN));
    }

    private static ProxmoxAuth.ApiToken token(String value) {
        return (ProxmoxAuth.ApiToken) ProxmoxAuth.apiToken(Secret.fromString(value));
    }

    @Test
    void equal_credentials_share_a_connector() {
        ProxmoxAuth a = ProxmoxAuth.usernamePassword("jenkins", "pve", Secret.fromString("pw"));
        ProxmoxAuth b = ProxmoxAuth.usernamePassword("jenkins@pve", "ignored", Secret.fromString("pw"));
        ProxmoxAuth c = ProxmoxAuth.usernamePassword("jenkins", "pve", Secret.fromString("other"));
        assertThat(a, is(b));
        assertThat(a, is(not(c)));
        assertThat(a.hashCode(), is(b.hashCode()));
    }

    @Test
    void to_string_never_contains_the_secret() {
        ProxmoxAuth auth = ProxmoxAuth.usernamePassword("jenkins", "pve", Secret.fromString("top-secret"));
        assertThat(auth.toString().contains("top-secret"), is(false));
        ProxmoxAuth token = ProxmoxAuth.apiToken(Secret.fromString("jenkins@pve!ci=top-secret"));
        assertThat(token.toString().contains("top-secret"), is(false));
    }

    @Test
    void split_user_and_realm_prefers_the_realm_in_the_user_name() {
        assertArrayEquals(new String[] {"root", "pam"}, ProxmoxAuth.splitUserAndRealm("root@pam", "pve"));
        assertArrayEquals(new String[] {"jenkins", "pve"}, ProxmoxAuth.splitUserAndRealm("jenkins", "pve"));
        assertArrayEquals(new String[] {"jenkins", "pve"}, ProxmoxAuth.splitUserAndRealm(" jenkins@pve ", " x "));
        // LDAP/OIDC user ids contain '@' themselves; the realm is after the last one.
        assertArrayEquals(
                new String[] {"john@example.com", "ldap"},
                ProxmoxAuth.splitUserAndRealm("john@example.com@ldap", "pve"));
        assertArrayEquals(new String[] {"", ""}, ProxmoxAuth.splitUserAndRealm(null, null));
        assertArrayEquals(new String[] {"jenkins", ""}, ProxmoxAuth.splitUserAndRealm("jenkins", null));
        assertArrayEquals(new String[] {"", "pve"}, ProxmoxAuth.splitUserAndRealm("", "pve"));
    }

    @Test
    void username_password_is_always_well_formed() {
        assertTrue(ProxmoxAuth.usernamePassword("jenkins", "pve", null).isValidFormat());
    }

    @Test
    void token_with_and_without_the_ui_prefix_share_a_connector() {
        ProxmoxAuth bare =
                ProxmoxAuth.apiToken(Secret.fromString("jenkins@pve!ci=11111111-2222-3333-4444-555555555555"));
        ProxmoxAuth prefixed = ProxmoxAuth.apiToken(
                Secret.fromString("PVEAPIToken=jenkins@pve!ci=11111111-2222-3333-4444-555555555555"));
        assertThat(prefixed, is(bare));
        assertThat(prefixed.hashCode(), is(bare.hashCode()));
    }

    @Test
    void token_and_password_credentials_are_never_equal() {
        ProxmoxAuth token = ProxmoxAuth.apiToken(Secret.fromString("pw"));
        ProxmoxAuth password = ProxmoxAuth.usernamePassword("pw", "", null);
        assertThat(token, is(not(password)));
        assertThat(password, is(not(token)));
    }

    @Test
    void empty_token_still_produces_a_header_prefix_only() {
        ProxmoxAuth.ApiToken token = (ProxmoxAuth.ApiToken) ProxmoxAuth.apiToken(null);
        assertThat(token.getAuthorizationHeader(), is("PVEAPIToken="));
    }
}
