# Jenkins Proxmox Plugin

Use Proxmox VE virtual machines as Jenkins agents: either existing virtual machines that are
rolled back to a snapshot, or virtual machines that are cloned from a template on demand.

[![Proxmox Plugin](https://img.shields.io/jenkins/plugin/v/proxmox.svg)](https://plugins.jenkins.io/proxmox)
[![ChangeLog](https://img.shields.io/github/release/jenkinsci/proxmox-plugin.svg?label=changelog)](https://github.com/jenkinsci/proxmox-plugin/releases/latest)
[![Installs](https://img.shields.io/jenkins/plugin/i/proxmox.svg?color=blue)](https://plugins.jenkins.io/proxmox)
[![License](https://img.shields.io/github/license/jenkinsci/proxmox-plugin.svg)](LICENSE)
[![Build Status](https://ci.jenkins.io/job/Plugins/job/proxmox-plugin/job/master/badge/icon)](https://ci.jenkins.io/job/Plugins/job/proxmox-plugin/job/master/)

## Description

The plugin connects Jenkins to one or more Proxmox datacenters and offers three things:

-   **Agents on existing virtual machines.** An agent is bound to one virtual machine. The plugin
    rolls the machine back to a snapshot, starts it and shuts it down again, depending on the
    revert policy.
-   **Agents provisioned on demand.** A datacenter can have agent templates. When a build needs
    an agent with a matching label, the template's virtual machine is cloned, started and
    connected; the clone is deleted again when its agent is removed.
-   **Build steps** to start, stop, pause, resume, hibernate, clone, snapshot, revert and convert
    virtual machines, and to run commands in them through the QEMU guest agent. They are available
    in freestyle jobs and, through the `proxmox` step, in Pipeline.

Notes:

-   Only Qemu virtual machines are supported (no LXC containers).
-   To never roll back the virtual machine of an agent, select the snapshot `current` or the
    revert policy "Never revert".
-   A failed rollback or start of the virtual machine aborts the agent launch (or the job,
    with the revert policy "Before every job").
-   With the revert policy "After connect" the virtual machine is shut down whenever the agent
    disconnects, including controller restarts.
-   Only virtual machines that the plugin cloned itself are ever deleted. A virtual machine that
    was assigned to an agent by hand is never deleted, also not when its agent is deleted.

## Configuration

### Datacenter

Use a dedicated Proxmox user for Jenkins with the least privileges needed on the
agent VMs (or their pool): `VM.Audit`, `VM.PowerMgmt` and `VM.Snapshot.Rollback`.
Grant them on `/vms/<vmid>` (or the pool path); no `Sys.Audit` is needed. With *Privilege
Separation*, grant the same ACL to the token itself (`user@realm!tokenid`).
Everyone who may configure Jenkins agents can select VMs and snapshots of that account.

Provisioning agents from templates and the build steps need more privileges, depending on what
is used: `VM.Clone` and `VM.Allocate` (plus `Datastore.AllocateSpace` on the target storage) to
clone and delete virtual machines, `VM.Snapshot` to create and delete snapshots, and the
privileges your Proxmox version requires for the QEMU guest agent.

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

### Agents on existing virtual machines

To add agents go to "Manage Jenkins" > "Nodes" and click "New Node". Select the
node type "Agent virtual machine running on a Proxmox datacenter" and
enter a name for the node.

The revert policy decides when the virtual machine is rolled back to the selected snapshot:

| Policy           | Behaviour                                                                                 |
|------------------|-------------------------------------------------------------------------------------------|
| After connect    | Rolled back when the agent is launched; shut down when the agent disconnects.              |
| Before every job | Rolled back each time a build is assigned to the agent. Requires exactly one executor.     |
| After every job  | Rolled back each time a build finished, so the next build finds a clean machine.           |
| Never revert     | The agent neither rolls back nor starts or stops the virtual machine.                      |

### Agents provisioned on demand

Add one or more agent templates to the datacenter ("Agent Templates" in the cloud
configuration) and set the instance cap of the datacenter. A template describes:

-   **Template Name** and **Labels**: builds that ask for one of the labels get an agent from
    this template.
-   **Datacenter Node**, **Template VM ID** and optionally a **Snapshot**: the virtual machine
    to clone. A running virtual machine can only be cloned from a snapshot. A linked clone is
    used if selected and supported by the storage, otherwise a full clone.
-   **Remote FS**, **Executors**, **Launcher** and **Retention strategy** of the agents.
-   **Start VM**, **Startup wait** and **Wait for guest agent**: how the clone is brought up.
-   **Post-clone command**: optional command that is run in the clone through the guest agent.
-   **Limited builds**: optionally remove the agent, and delete its clone, after that many builds.

Clones are named after the template. An hourly task removes provisioned agents whose virtual
machine no longer exists in Proxmox.

The launcher "Launch agent via QEMU Guest Agent" starts the agent process inside the virtual
machine through the guest agent, so no SSH access is needed.

### Build steps and Pipeline

In freestyle jobs the operations are available as build steps. In Pipeline they are wrapped
by the `proxmox` step:

```groovy
proxmox(
    datacenterDescription: 'jenkins@pve - proxmox.example.com',
    buildStep: [$class: 'RevertToSnapshot',
        datacenterNode: 'pve1',
        vmId: '100',
        snapshotName: 'clean'
    ]
)
```

See [PIPELINE_SYNTAX.md](PIPELINE_SYNTAX.md) for all steps and their parameters and
[PIPELINE_EXAMPLES.md](PIPELINE_EXAMPLES.md) for complete pipelines.

### Configuration as Code

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

`launcher` is the agent's own launch method (`jnlp`, `ssh`, ...); the plugin wraps it in its
virtual machine launcher. `delegateLauncher` is accepted as an alias.
`startVM` defaults to `false` when omitted.

Proxmox agents are cloud agents for Jenkins, so Configuration as Code reads them from the YAML
file but does not include them in an export.

## Manually Installing
 1. Clone this repo.
 2. Run ``mvn clean package`` (requires JDK 17 or 21).
 3. Go to Jenkins in a web browser.
 4. Go to *"Manage Jenkins"* > *"Plugins"* > *"Advanced settings"*.
 5. Upload the file `target/proxmox.hpi` under the *"Deploy Plugin"* section.

To run directly a Jenkins test instance with the plugin, run ``mvn hpi:run``.

## ChangeLog
-   For recent versions, see [GitHub Releases](https://github.com/jenkinsci/proxmox-plugin/releases)
-   For versions 0.2.1 and older, see the [Wiki page](https://wiki.jenkins.io/display/JENKINS/Proxmox+Plugin)
