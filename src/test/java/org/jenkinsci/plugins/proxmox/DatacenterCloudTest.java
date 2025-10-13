package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import java.util.List;
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
    
}