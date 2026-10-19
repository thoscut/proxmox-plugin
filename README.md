# Jenkins Proxmox Plugin

Use Proxmox virtual machines as agents in Jenkins

[![Proxmox Plugin](https://img.shields.io/jenkins/plugin/v/proxmox.svg)](https://plugins.jenkins.io/proxmox)
[![ChangeLog](https://img.shields.io/github/release/jenkinsci/proxmox-plugin.svg?label=changelog)](https://github.com/jenkinsci/proxmox-plugin/releases/latest)
[![Installs](https://img.shields.io/jenkins/plugin/i/proxmox.svg?color=blue)](https://plugins.jenkins.io/proxmox)
[![License](https://img.shields.io/github/license/jenkinsci/proxmox-plugin.svg)](LICENSE)
[![Build Status](https://ci.jenkins.io/job/Plugins/job/proxmox-plugin/job/master/badge/icon)](https://ci.jenkins.io/job/Plugins/job/proxmox-plugin/job/master/)

## Description

This plugin allows the use of Proxmox virtual machines as agents in Jenkins.

> **Note:** This repository is an unofficial fork of
> [jenkinsci/proxmox-plugin](https://github.com/jenkinsci/proxmox-plugin). Its builds are named
> `proxmox_unofficial.hpi` and are not distributed through the Jenkins update center.

## Limitations

-   Only Qemu virtual machines supported (at the moment).
-   No checking on virtual machine ready state after a rollback or start; the "Startup Idle" period is waited instead.

## Configuration

#### Datacenter cloud

To add a new Proxmox datacenter cloud, click on "Manage Jenkins" then
"Clouds" and "New cloud", and select "Proxmox Datacenter".
The hostname may include a port (`host:port`, default 8006).

#### Virtual machine agents

To add agents click on "Manage Jenkins" then "Nodes". Select the
node type "Agent virtual machine running on a Proxmox datacenter." and
enter a name for the node. Configuring these agents requires the
Overall/Administer permission, because they control virtual machines with
the datacenter's credentials.

-   **Revert Snapshot**: the snapshot to roll back to. Select `current` to never roll back.
-   **Revert policy**: roll back after connecting the agent, or before every job.
-   If the rollback or start of the virtual machine fails, the agent launch is aborted.

## Manually Installing
 1. Clone this repo.
 2. Run ``mvn clean package``. 
 3. Go to Jenkins in a web browser.
 4. Click on *"Manage Jenkins"*, select *"Plugins"*. 
 5. Click on *"Advanced settings"* then upload the file `target/proxmox.hpi` under the *"Deploy Plugin"* section.
 
To run directly a Jenkins test instance with the plugin, run ``mvn hpi:run``.


## ChangeLog
-   For recent versions, see [GitHub Releases](https://github.com/jenkinsci/proxmox-plugin/releases)
-   For versions 0.2.1 and older, see the [Wiki page](https://wiki.jenkins.io/display/JENKINS/Proxmox+Plugin)

