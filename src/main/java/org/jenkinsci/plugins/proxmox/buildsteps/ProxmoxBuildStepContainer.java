package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.DescriptorExtensionList;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.tasks.SimpleBuildStep;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.proxmox.Datacenter;
import org.kohsuke.stapler.DataBoundConstructor;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Container for Proxmox build steps. This wraps individual build steps to make them available
 * as a unified "proxmox" step in pipelines, following the vSphere plugin pattern.
 */
public class ProxmoxBuildStepContainer extends Builder implements SimpleBuildStep {

    private final ProxmoxBuildStep buildStep;

    @DataBoundConstructor
    public ProxmoxBuildStepContainer(final ProxmoxBuildStep buildStep) {
        this.buildStep = buildStep;
    }

    public ProxmoxBuildStep getBuildStep() {
        return buildStep;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath filePath, @NonNull Launcher launcher, @NonNull TaskListener listener) throws InterruptedException, IOException {
        try {
            startLogs(listener.getLogger());
            buildStep.perform(run instanceof AbstractBuild ? (AbstractBuild<?, ?>) run : null, launcher, listener instanceof BuildListener ? (BuildListener) listener : null);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
        try {
            startLogs(listener.getLogger());
            return buildStep.perform(build, launcher, listener);
        } catch (Exception e) {
            throw new AbortException(e.getMessage());
        }
    }

    private void startLogs(PrintStream logger) {
        logger.println("[Proxmox] Starting: " + buildStep.getDescriptor().getDisplayName());
    }

    @Extension
    @Symbol("proxmox")
    public static final class ProxmoxBuildStepContainerDescriptor extends BuildStepDescriptor<Builder> {

        @Override
        public String getDisplayName() {
            return "Proxmox Build Step";
        }

        public java.util.List<Descriptor<Builder>> getBuildSteps() {
            // Filter to only Proxmox build steps (subclasses of ProxmoxBuildStep)
            java.util.List<Descriptor<Builder>> proxmoxSteps = new java.util.ArrayList<>();
            for (Descriptor<Builder> descriptor : Jenkins.get().getDescriptorList(Builder.class)) {
                if (descriptor.clazz != null && ProxmoxBuildStep.class.isAssignableFrom(descriptor.clazz)) {
                    proxmoxSteps.add(descriptor);
                }
            }
            return proxmoxSteps;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}
