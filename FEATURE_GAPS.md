# Feature Gap Analysis

Comparison of the Jenkins Proxmox plugin against other Jenkins virtualization plugins:
- [vSphere Cloud Plugin](https://github.com/jenkinsci/vsphere-cloud-plugin) (VMware vSphere)
- [Libvirt Agent Plugin](https://github.com/jenkinsci/libvirt-agent-plugin) (KVM/QEMU/Xen via libvirt)
- [EC2 Plugin](https://github.com/jenkinsci/ec2-plugin) (AWS EC2)

Features are compared across all plugins. A :white_check_mark: indicates the feature is supported, :x: indicates it is missing.

## Cloud / Hypervisor Configuration

| Feature | Proxmox | vSphere | Libvirt | EC2 |
|---------|---------|---------|---------|-----|
| Multiple cloud instances | :x: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Credentials plugin integration | :x: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Test connection | :white_check_mark: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Ignore SSL errors | :white_check_mark: | :x: | N/A | N/A |
| Custom port | :white_check_mark: | :white_check_mark: | :white_check_mark: | N/A |

### Multiple cloud instances

The Proxmox `Datacenter` class hard-codes its cloud name to `Datacenter(proxmox)` (see `Datacenter.java` constructor: `super("Datacenter(proxmox)")`), meaning only a single Proxmox datacenter can be configured. All three comparable plugins allow multiple cloud instances, each with a unique name.

### Credentials plugin integration

The Proxmox plugin stores the password directly as a `Secret` field rather than referencing a credential managed through the [Jenkins Credentials Plugin](https://plugins.jenkins.io/credentials/). The vSphere, libvirt, and EC2 plugins all integrate with the Credentials Plugin, which provides centralized credential management, credential rotation, and access control.

In addition, Proxmox VE supports [API Token authentication](https://pve.proxmox.com/wiki/User_Management#pveum_tokens) as a more secure alternative to password-based authentication. The plugin should support this as well.

## Agent Provisioning

| Feature | Proxmox | vSphere | Libvirt | EC2 |
|---------|---------|---------|---------|-----|
| On-demand provisioning | :x: | :white_check_mark: | :x: | :white_check_mark: |
| Agent templates | :x: | :white_check_mark: | :x: | :white_check_mark: |
| Instance cap | :x: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Clone from template/image | :x: | :white_check_mark: | :x: | :white_check_mark: |

### On-demand provisioning

The Proxmox `Datacenter.canProvision()` always returns `false` and `provision()` returns an empty collection. There is no ability for Jenkins to automatically spin up new VMs when the build queue grows. The vSphere and EC2 plugins both implement full on-demand provisioning, where Jenkins can clone VMs from templates or launch new instances to meet demand and terminate them when idle.

### Agent templates

Without on-demand provisioning there is no template system. The vSphere plugin provides `vSphereCloudSlaveTemplate` with fields for clone name prefix, master image, snapshot, linked clone, cluster, resource pool, datastore, folder, customization spec, instance cap, and launch configuration. The EC2 plugin provides AMI-based templates with instance types, security groups, availability zones, and more.

### Instance cap

There is no way to limit the number of concurrent agents per datacenter. The vSphere plugin provides instance caps at both the cloud and template level. The libvirt plugin offers `maxOnlineSlaves` to cap concurrent agents per hypervisor. The EC2 plugin provides per-AMI and per-cloud instance caps.

## VM Lifecycle

| Feature | Proxmox | vSphere | Libvirt | EC2 |
|---------|---------|---------|---------|-----|
| Start VM | :white_check_mark: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Graceful shutdown | :white_check_mark: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Forced stop | :white_check_mark: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Suspend VM | :x: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Configurable shutdown method | :x: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Reboot after run | :x: | :x: | :white_check_mark: | :x: |
| Connection retry on failure | :x: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| LXC container support | :x: | N/A | N/A | N/A |

### Configurable shutdown method

The Proxmox plugin only supports graceful shutdown with a fallback to forced stop. The libvirt plugin allows the user to choose from `destroy`, `shutdown`, or `suspend`. The vSphere plugin supports power off and suspend. Adding a shutdown method selector would give users control over how their VMs are handled.

### Connection retry on failure

The Proxmox plugin has no retry mechanism if the agent fails to connect after a VM start. The libvirt plugin provides a configurable `timesToRetryOnFailure` with a waiting period between retries. The vSphere launcher retries with configurable delays.

### LXC container support

Proxmox VE supports both QEMU virtual machines and LXC containers, but the plugin only supports QEMU VMs. Supporting LXC would be a significant differentiator as the other plugins have no equivalent.

## Snapshot Management

| Feature | Proxmox | vSphere | Libvirt | EC2 |
|---------|---------|---------|---------|-----|
| Revert to snapshot | :white_check_mark: | :white_check_mark: | :white_check_mark: | N/A |
| Take snapshot | :x: | :white_check_mark: | :x: | N/A |
| Delete snapshot | :x: | :white_check_mark: | :x: | N/A |
| Rename snapshot | :x: | :white_check_mark: | :x: | N/A |
| Before-job snapshot (node level) | :white_check_mark: | :x: | :white_check_mark: | N/A |
| Before-job snapshot (job level) | :x: | :x: | :white_check_mark: | N/A |
| After-connect snapshot revert | :white_check_mark: | :white_check_mark: | :x: | N/A |
| No snapshot revert option | :x: | :white_check_mark: | :white_check_mark: | N/A |
| Test rollback | :white_check_mark: | :x: | :x: | N/A |

### Before-job snapshot at job level

The libvirt plugin has `BeforeJobSnapshotJobProperty` which allows each job to specify which snapshot to revert to before running. The Proxmox plugin only supports snapshot configuration at the node level. This would allow different jobs to revert to different snapshots on the same agent.

### No snapshot revert option

Currently listed as a limitation in the README: there is no option to avoid rolling back to a snapshot on agent startup. Both the vSphere and libvirt plugins allow snapshot revert to be fully optional.

## Build Steps / Pipeline

| Feature | Proxmox | vSphere | Libvirt | EC2 |
|---------|---------|---------|---------|-----|
| Pipeline (Workflow) support | :x: | :white_check_mark: | :x: | :white_check_mark: |
| VM power on build step | :x: | :white_check_mark: | :x: | :x: |
| VM power off build step | :x: | :white_check_mark: | :x: | :x: |
| Snapshot build steps | :x: | :white_check_mark: | :x: | :x: |
| Clone VM build step | :x: | :white_check_mark: | :x: | :x: |
| Delete VM build step | :x: | :white_check_mark: | :x: | :x: |
| Reconfigure VM build step | :x: | :white_check_mark: | :x: | :x: |
| Expose guest info build step | :x: | :white_check_mark: | :x: | :x: |

### Pipeline support

The vSphere plugin provides `vSphereStep` for use in Jenkins Pipeline scripts. The EC2 plugin is also fully usable from pipelines. The Proxmox plugin has no pipeline step support.

### Build steps

The vSphere plugin offers a rich set of build steps that can be added to freestyle jobs: PowerOn, PowerOff, SuspendVm, TakeSnapshot, RevertToSnapshot, DeleteSnapshot, RenameSnapshot, Clone, Deploy, Delete, Rename, Reconfigure (CPU, Memory, Disk, Network, Annotations), ConvertToTemplate, ConvertToVm, and ExposeGuestInfo. These allow full VM lifecycle management within build jobs. The Proxmox plugin has no build step support.

## Robustness

| Feature | Proxmox | vSphere | Libvirt | EC2 |
|---------|---------|---------|---------|-----|
| Task status verification | :x: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Proper error propagation | :x: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| VM readiness checking | :x: | :white_check_mark: | :white_check_mark: | :white_check_mark: |
| Retry logic for API calls | :x: | :white_check_mark: | :white_check_mark: | :white_check_mark: |

### Task status verification

The Proxmox plugin waits for tasks to finish but does not verify success/failure status after snapshot rollback (noted with a `TODO` in the source code). The task status is logged but not acted upon. Other plugins verify task outcomes and handle failures appropriately.

### VM readiness checking

Listed as a limitation in the README. After a snapshot rollback or VM start, the plugin relies solely on a configurable wait period rather than actually checking if the VM is ready to accept connections. Other plugins use guest tools, SSH probing, or agent connectivity checks.

## Summary of Prioritized Gaps

### Critical

1. **Multiple cloud instances** — Cannot use more than one Proxmox datacenter.
2. **On-demand provisioning with agent templates** — The most important cloud plugin feature, allowing Jenkins to automatically scale agents based on build queue demand.
3. **Instance cap** — Without capacity limits, there is no protection against resource exhaustion.

### High

4. **Credentials plugin integration** — Follows Jenkins security best practices and enables API token authentication.
5. **LXC container support** — Unique to Proxmox and a significant differentiator.
6. **Connection retry on failure** — Improves reliability in real-world deployments.
7. **Pipeline support** — Modern Jenkins installations rely heavily on pipelines.

### Medium

8. **Configurable shutdown method** — Adds suspend/destroy options.
9. **No snapshot revert option** — Addresses a documented limitation.
10. **Before-job snapshot at job level** — Adds flexibility for multi-job agents.
11. **Task status verification and error propagation** — Improves robustness.
12. **VM readiness checking** — Addresses a documented limitation.
13. **Reboot after run** — Clean state without full snapshot revert overhead.

### Low

14. **Build step support** — Enables VM management within build jobs.
15. **Snapshot management (take/delete/rename)** — Advanced snapshot operations.
