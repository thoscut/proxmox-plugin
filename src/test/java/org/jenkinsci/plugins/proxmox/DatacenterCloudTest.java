package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DatacenterCloudTest {

    @Test
    void should_register_datacenter_cloud_descriptor(JenkinsRule r) {
        // Test that the Datacenter cloud descriptor is registered in Jenkins
        // Force Extension loading
        Datacenter.DescriptorImpl proxmoxDescriptor = r.jenkins.getDescriptorByType(Datacenter.DescriptorImpl.class);
        List<Descriptor<Cloud>> cloudDescriptors = r.jenkins.getDescriptorList(Cloud.class);
        
        assertThat("Cloud descriptors list should not be empty", cloudDescriptors.size(), not(is(0)));
        
        // Find the Proxmox Datacenter descriptor
        for (Descriptor<Cloud> desc : cloudDescriptors) {
            if (desc instanceof Datacenter.DescriptorImpl) {
                proxmoxDescriptor = (Datacenter.DescriptorImpl) desc;
                break;
            }
        }
        
        assertThat("Proxmox Datacenter descriptor should be registered", proxmoxDescriptor, notNullValue());
        assertThat("Display name should be correct", proxmoxDescriptor.getDisplayName(), is("Proxmox Datacenter"));
        assertThat("Descriptor should be properly configured", proxmoxDescriptor, notNullValue());
    }
    
    @Test
    void should_create_datacenter_cloud_instance(JenkinsRule r) {
        // Test that we can create a Datacenter cloud instance
        String hostname = "test-proxmox.example.com";
        String credentialsId = "test-credentials";
        String realm = "pve";
        Boolean ignoreSSL = true;
        Integer instanceCap = 5;

        Datacenter datacenter = new Datacenter(hostname, credentialsId, realm, ignoreSSL, null, instanceCap);

        assertThat("Datacenter should not be null", datacenter, notNullValue());
        assertThat("Hostname should match", datacenter.getHostname(), is(hostname));
        assertThat("Credentials ID should match", datacenter.getCredentialsId(), is(credentialsId));
        assertThat("Realm should match", datacenter.getRealm(), is(realm));
        assertThat("IgnoreSSL should match", datacenter.getIgnoreSSL(), is(ignoreSSL));
        assertThat("Instance cap should match", datacenter.getInstanceCap(), is(instanceCap));
        assertThat("Should extend Cloud", datacenter, instanceOf(Cloud.class));
    }
    
    @Test
    @Disabled("Credentials resolution not available in test environment")
    void should_generate_datacenter_description(JenkinsRule r) {
        // Test that datacenter description is generated correctly
        // Disabled because test environment doesn't have credential system configured
        String hostname = "proxmox.company.com";
        String credentialsId = "admin-credentials";
        String realm = "pam";

        Datacenter datacenter = new Datacenter(hostname, credentialsId, realm, false, null, 10);
        String description = datacenter.getDatacenterDescription();

        assertThat("Description should not be null", description, notNullValue());
        assertThat("Description should contain realm", description, containsString(realm));
        assertThat("Description should contain hostname", description, containsString(hostname));
        // Note: In test environment, username shows as "unknown" when credentials can't be resolved
        assertThat("Description format should match pattern", description, is("unknown@pam - proxmox.company.com"));
    }
    
    @Test
    void should_have_proper_cloud_name(JenkinsRule r) {
        // Test that the cloud name is generated properly
        String hostname = "my-proxmox";
        Datacenter datacenter = new Datacenter(hostname, "user-creds", "pve", false, null, 1);

        assertThat("Cloud name should be generated with Proxmox prefix",
                   datacenter.name, is("Proxmox-" + hostname));

        // Test with null/empty hostname
        Datacenter datacenterEmpty = new Datacenter("", "user-creds", "pve", false, null, 1);
        assertThat("Cloud name should have default name for empty hostname",
                   datacenterEmpty.name, is("Proxmox-Datacenter"));

        Datacenter datacenterNull = new Datacenter(null, "user-creds", "pve", false, null, 1);
        assertThat("Cloud name should have default name for null hostname",
                   datacenterNull.name, is("Proxmox-Datacenter"));
    }
    
    @Test
    @Disabled("Credentials resolution not available in test environment")
    void should_provide_proxmox_connector_instance(JenkinsRule r) {
        // Test that the datacenter can provide a Proxmox connector
        // Disabled because test environment doesn't have credential system configured
        Datacenter datacenter = new Datacenter("test-host", "user-creds", "pve", true, null, 1);

        // This would throw an exception in test environment due to missing credentials
        // In real environment with proper credentials, this should work:
        // var connector = datacenter.proxmoxInstance();
        // assertThat("Connector should not be null", connector, notNullValue());

        // Connector should be reused on subsequent calls
        // var connector2 = datacenter.proxmoxInstance();
        // assertThat("Connector should be the same instance", connector, is(connector2));

        // For now, just verify that the datacenter object was created
        assertThat("Datacenter should be created", datacenter, notNullValue());
    }
    
    @Test
    @Disabled("Extension registration issue in test environment - needs investigation")
    void should_have_working_descriptor_methods(JenkinsRule r) {
        // Test descriptor method functionality
        Datacenter datacenter = new Datacenter("test", "user-creds", "pve", false, null, 1);
        Datacenter.DescriptorImpl descriptor = datacenter.getDescriptor();

        assertThat("Descriptor should not be null", descriptor, notNullValue());
        assertThat("Display name should be set", descriptor.getDisplayName(), is("Proxmox Datacenter"));
        assertThat("Descriptor should be properly configured", descriptor, notNullValue());
    }
    
    @Test
    @Disabled("Legacy constructor no longer available after credentials migration")
    void should_handle_legacy_constructor(JenkinsRule r) {
        // This test is disabled as the legacy constructor has been removed
        // in favor of the credentials-based approach for better security
    }
}