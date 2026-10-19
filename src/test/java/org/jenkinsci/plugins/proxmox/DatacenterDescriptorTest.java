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

    private static Datacenter.DescriptorImpl descriptor(JenkinsRule r) {
        return r.jenkins.getDescriptorByType(Datacenter.DescriptorImpl.class);
    }

    @Test
    void test_connection_reports_missing_fields(JenkinsRule r) {
        Datacenter.DescriptorImpl descriptor = descriptor(r);
        Secret password = Secret.fromString("secret");

        assertThat(descriptor.doTestConnection(null, "user", "pve", password, false).kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor.doTestConnection("host", " ", "pve", password, false).kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor.doTestConnection("host", "user", null, password, false).kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor.doTestConnection("host", "user", "pve", null, false).kind, is(FormValidation.Kind.ERROR));
    }

    @Test
    void test_connection_reports_invalid_hostname(JenkinsRule r) {
        FormValidation result =
                descriptor(r).doTestConnection("host/path", "user", "pve", Secret.fromString("secret"), false);
        assertThat(result.kind, is(FormValidation.Kind.ERROR));
        assertThat(result.getMessage(), containsString("Invalid hostname"));
    }

    @Test
    void test_connection_requires_administer(JenkinsRule r) {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(
                new MockAuthorizationStrategy().grant(Jenkins.READ).everywhere().to("user"));
        Datacenter.DescriptorImpl descriptor = descriptor(r);

        try (ACLContext ignored = ACL.as2(User.getById("user", true).impersonate2())) {
            assertThrows(
                    AccessDeniedException.class,
                    () -> descriptor.doTestConnection("host", "user", "pve", Secret.fromString("secret"), false));
        }
    }

    @Test
    void check_password_handles_missing_value(JenkinsRule r) {
        assertThat(descriptor(r).doCheckPassword(null).kind, is(FormValidation.Kind.ERROR));
        assertThat(descriptor(r).doCheckPassword(Secret.fromString("secret")).kind, is(FormValidation.Kind.OK));
    }

    @Test
    void reuses_connector_until_settings_change(JenkinsRule r) {
        Datacenter datacenter = new Datacenter("reuse-host", "user", "pve", Secret.fromString("secret"), false);
        Datacenter sameSettings = new Datacenter("reuse-host", "user", "pve", Secret.fromString("secret"), false);
        Datacenter otherPassword = new Datacenter("reuse-host", "user", "pve", Secret.fromString("other"), false);

        assertThat(sameSettings.proxmoxInstance() == datacenter.proxmoxInstance(), is(true));
        assertThat(otherPassword.proxmoxInstance() == datacenter.proxmoxInstance(), is(false));
    }
}
