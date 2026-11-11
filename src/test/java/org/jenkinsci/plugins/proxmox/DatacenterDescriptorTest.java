package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

/**
 * Tests for the form validation of {@link Datacenter.DescriptorImpl}.
 */
@WithJenkins
class DatacenterDescriptorTest {

    private static final Secret PASSWORD = Secret.fromString("secret");

    private static Datacenter.DescriptorImpl descriptor(JenkinsRule r) {
        return r.jenkins.getDescriptorByType(Datacenter.DescriptorImpl.class);
    }

    @Test
    void test_connection_reports_missing_fields(JenkinsRule r) {
        Datacenter.DescriptorImpl descriptor = descriptor(r);

        FormValidation noHostname = descriptor.doTestConnection(null, "user", "pve", PASSWORD, null, false);
        assertThat(noHostname.kind, is(FormValidation.Kind.ERROR));
        assertThat(noHostname.getMessage(), containsString("Hostname"));

        FormValidation noUsername = descriptor.doTestConnection("host", " ", "pve", PASSWORD, null, false);
        assertThat(noUsername.kind, is(FormValidation.Kind.ERROR));
        assertThat(noUsername.getMessage(), containsString("Username"));

        FormValidation noRealm = descriptor.doTestConnection("host", "user", null, PASSWORD, null, false);
        assertThat(noRealm.kind, is(FormValidation.Kind.ERROR));
        assertThat(noRealm.getMessage(), containsString("Realm"));

        FormValidation noPassword = descriptor.doTestConnection("host", "user", "pve", null, "", false);
        assertThat(noPassword.kind, is(FormValidation.Kind.ERROR));
        assertThat(noPassword.getMessage(), containsString("Password"));
    }

    @Test
    void test_connection_reports_an_unknown_credential(JenkinsRule r) {
        FormValidation result = descriptor(r).doTestConnection("host", null, null, null, "no-such-credential", false);
        assertThat(result.kind, is(FormValidation.Kind.ERROR));
        assertThat(result.getMessage(), containsString("no-such-credential"));
    }

    @Test
    void hostname_must_not_contain_user_information(JenkinsRule r) {
        assertThat(descriptor(r).doCheckHostname("root@pve.example.com").kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor(r).doCheckHostname(" ").kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor(r).doCheckHostname("pve.example.com:8006").kind, is(FormValidation.Kind.OK));
    }

    @Test
    void username_realm_and_password_are_only_required_without_a_credential(JenkinsRule r) {
        Datacenter.DescriptorImpl descriptor = descriptor(r);

        assertThat(descriptor.doCheckPassword(null, null).kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckPassword(PASSWORD, null).kind, is(FormValidation.Kind.OK));
        assertThat(descriptor.doCheckPassword(null, "some-credential").kind, is(FormValidation.Kind.OK));

        assertThat(descriptor.doCheckUsername("", "").kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckUsername("", "some-credential").kind, is(FormValidation.Kind.OK));

        assertThat(descriptor.doCheckRealm(null, null).kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor.doCheckRealm(null, "some-credential").kind, is(FormValidation.Kind.OK));
    }

    @Test
    void credential_check_warns_without_selection_and_fails_for_unknown_ids(JenkinsRule r) {
        assertThat(descriptor(r).doCheckCredentialsId("").kind, is(FormValidation.Kind.WARNING));
        assertThat(descriptor(r).doCheckCredentialsId("no-such-credential").kind, is(FormValidation.Kind.ERROR));
    }

    @Test
    void form_methods_require_administer(JenkinsRule r) {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("user"));
        Datacenter.DescriptorImpl descriptor = descriptor(r);

        try (ACLContext ignored = ACL.as2(User.getById("user", true).impersonate2())) {
            assertThrows(
                    AccessDeniedException.class,
                    () -> descriptor.doTestConnection("host", "user", "pve", PASSWORD, null, false));
            assertThrows(AccessDeniedException.class, () -> descriptor.doCheckHostname("host"));
            assertThrows(AccessDeniedException.class, () -> descriptor.doCheckCredentialsId("id"));
            assertThrows(AccessDeniedException.class, () -> descriptor.doCheckCloudHealth("user@pve - host"));
            assertThrows(AccessDeniedException.class, () -> descriptor.doGetDetailedStatistics("user@pve - host"));
        }
    }

    @Test
    void health_check_reports_an_unknown_datacenter(JenkinsRule r) {
        assertThat(descriptor(r).doCheckCloudHealth(" ").kind, is(FormValidation.Kind.WARNING));
        assertThat(descriptor(r).doCheckCloudHealth("user@pve - unknown").kind, is(FormValidation.Kind.ERROR));
    }
}
