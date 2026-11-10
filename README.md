# Jenkins Proxmox Plugin

Use Proxmox virtual machines as agents in Jenkins

[![Proxmox Plugin](https://img.shields.io/jenkins/plugin/v/proxmox.svg)](https://plugins.jenkins.io/proxmox)
[![ChangeLog](https://img.shields.io/github/release/jenkinsci/proxmox-plugin.svg?label=changelog)](https://github.com/jenkinsci/proxmox-plugin/releases/latest)
[![Installs](https://img.shields.io/jenkins/plugin/i/proxmox.svg?color=blue)](https://plugins.jenkins.io/proxmox)
[![License](https://img.shields.io/github/license/jenkinsci/proxmox-plugin.svg)](LICENSE)
[![Build Status](https://ci.jenkins.io/job/Plugins/job/proxmox-plugin/job/master/badge/icon)](https://ci.jenkins.io/job/Plugins/job/proxmox-plugin/job/master/)

## Description

This plugin allows the use of Proxmox virtual machines as agents in Jenkins.

## Limitations

-   Only Qemu virtual machines supported (at the moment).
-   To never roll back the virtual machine, select the snapshot `current`.
-   A failed rollback or start of the virtual machine aborts the agent launch (or the job,
    with the revert policy "Before every job").

## Configuration

#### Datacenter cloud

Use a dedicated Proxmox user for Jenkins with the least privileges needed on the
agent VMs (or their pool): `VM.Audit`, `VM.PowerMgmt` and `VM.Snapshot.Rollback`.
Everyone who may configure Jenkins agents can select VMs and snapshots of that account.

Authentication uses a Jenkins credential: either a *Username with password* credential
or a *Secret text* holding a Proxmox API token (`user@realm!tokenid=secret`, recommended).
Create the token with *Privilege Separation* enabled and give the token itself only the
permissions listed above. Datacenters configured before credentials were supported keep
working with the username and password stored in the cloud.

Agents refer to their datacenter by `user@realm - hostname`. If that no longer matches
(for example after switching the credential to another user), the agent falls back to the
only datacenter with the same hostname.

To add a new Proxmox datacenter cloud, go to "Manage Jenkins" > "Clouds",
click "New cloud" and select "Proxmox Datacenter".

#### Virtual machine agents

To add agents go to "Manage Jenkins" > "Nodes" and click "New Node". Select the
node type "Agent virtual machine running on a Proxmox datacenter." and
enter a name for the node.

#### Configuration as Code

```yaml
jenkins:
  clouds:
    - datacenter:
        hostname: "proxmox.example.com"
        credentialsId: "proxmox-api-token"   # secret text or username/password credential
        realm: "pve"                         # only used if the credential has no realm
        ignoreSSL: false
  nodes:
    - virtualMachineSlave:
        name: "proxmox-vm"
        datacenterDescription: "jenkins@pve - proxmox.example.com"
        datacenterNode: "pve1"
        virtualMachineId: 100
        snapshotName: "clean"
        startVM: true
        startupWaitingPeriodSeconds: 60
        revertPolicy: AFTER_CONNECT
        numExecutors: 1
        remoteFS: "/home/jenkins"
        launcher:
          jnlp
```

## Manually Installing
 1. Clone this repo.
 2. Run ``mvn clean package``. 
 3. Go to Jenkins in a web browser.
 4. Go to *"Manage Jenkins"* > *"Plugins"* > *"Advanced settings"*.
 5. Upload the file `target/proxmox.hpi` under the *"Deploy Plugin"* section.
 
To run directly a Jenkins test instance with the plugin, run ``mvn hpi:run``.


## ChangeLog
-   For recent versions, see [GitHub Releases](https://github.com/jenkinsci/proxmox-plugin/releases)
-   For versions 0.2.1 and older, see the [Wiki page](https://wiki.jenkins.io/display/JENKINS/Proxmox+Plugin)

