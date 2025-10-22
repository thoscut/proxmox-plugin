# Jenkins Pipeline Examples for Proxmox Plugin

This document provides examples of Jenkins Declarative Pipelines using the Proxmox plugin.

## Example 1: Windows VM Update and Template Creation

This pipeline demonstrates a complete workflow for updating a Windows VM and creating a template:

1. Creates a safety snapshot
2. Starts the VM
3. Runs Puppet configuration
4. Installs Windows Updates
5. Reboots the VM
6. Waits for user confirmation
7. Shuts down the VM
8. Clones the updated VM
9. Converts the clone to a template
10. Deletes the safety snapshot

```groovy
pipeline {
    agent any

    parameters {
        string(name: 'DATACENTER_DESCRIPTION', defaultValue: 'user@pve - pve-host', description: 'Proxmox datacenter description')
        string(name: 'DATACENTER_NODE', defaultValue: 'pve-node1', description: 'Proxmox node name')
        string(name: 'VM_ID', defaultValue: '100', description: 'VM ID to update')
        string(name: 'TEMPLATE_VM_ID', defaultValue: '9000', description: 'VM ID for the new template')
        string(name: 'TEMPLATE_NAME', defaultValue: 'windows-server-2022-template', description: 'Name for the template')
    }

    stages {
        stage('Create Safety Snapshot') {
            steps {
                echo "Creating safety snapshot before updates..."
                step([
                    $class: 'CreateSnapshot',
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    datacenterNode: "${params.DATACENTER_NODE}",
                    vmId: "${params.VM_ID}",
                    snapshotName: "pre-update-${BUILD_NUMBER}",
                    description: "Safety snapshot before updates - Build ${BUILD_NUMBER}",
                    includeRam: false
                ])
            }
        }

        stage('Start VM') {
            steps {
                echo "Starting VM ${params.VM_ID}..."
                step([
                    $class: 'StartVirtualMachine',
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    datacenterNode: "${params.DATACENTER_NODE}",
                    vmId: "${params.VM_ID}"
                ])

                // Wait for VM to fully boot and guest agent to be ready
                echo "Waiting for VM to boot..."
                sleep(time: 60, unit: 'SECONDS')
            }
        }

        stage('Run Puppet Agent') {
            steps {
                echo "Running Puppet configuration..."
                step([
                    $class: 'RunCommand',
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    datacenterNode: "${params.DATACENTER_NODE}",
                    vmId: "${params.VM_ID}",
                    command: 'puppet agent -t',
                    timeoutSeconds: 600,
                    waitForCompletion: true,
                    failOnError: false  // Puppet may return exit code 2 on changes
                ])
            }
        }

        stage('Install Windows Updates') {
            steps {
                echo "Installing Windows Updates..."

                // Option 1: Using PSWindowsUpdate module
                step([
                    $class: 'RunCommand',
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    datacenterNode: "${params.DATACENTER_NODE}",
                    vmId: "${params.VM_ID}",
                    command: 'powershell.exe -Command "Install-WindowsUpdate -AcceptAll -AutoReboot:$false -Verbose"',
                    timeoutSeconds: 3600,
                    waitForCompletion: true,
                    failOnError: false
                ])

                // Option 2: Using Windows Update API (alternative)
                // step([
                //     $class: 'RunCommand',
                //     datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                //     datacenterNode: "${params.DATACENTER_NODE}",
                //     vmId: "${params.VM_ID}",
                //     command: 'powershell.exe -Command "Get-WindowsUpdate -Install -AcceptAll -IgnoreReboot"',
                //     timeoutSeconds: 3600,
                //     waitForCompletion: true,
                //     failOnError: false
                // ])
            }
        }

        stage('Reboot VM') {
            steps {
                echo "Rebooting VM to apply updates..."
                step([
                    $class: 'RunCommand',
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    datacenterNode: "${params.DATACENTER_NODE}",
                    vmId: "${params.VM_ID}",
                    command: 'shutdown /r /t 10 /c "Rebooting to apply updates"',
                    timeoutSeconds: 30,
                    waitForCompletion: false,
                    failOnError: false
                ])

                // Wait for reboot to complete
                echo "Waiting for VM to reboot..."
                sleep(time: 120, unit: 'SECONDS')

                // Optional: Verify VM is back online
                echo "Verifying VM is online..."
                retry(5) {
                    sleep(time: 30, unit: 'SECONDS')
                    step([
                        $class: 'RunCommand',
                        datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                        datacenterNode: "${params.DATACENTER_NODE}",
                        vmId: "${params.VM_ID}",
                        command: 'echo VM is online',
                        timeoutSeconds: 10,
                        waitForCompletion: true,
                        failOnError: true
                    ])
                }
            }
        }

        stage('User Verification') {
            steps {
                echo "VM has been updated and rebooted."
                echo "Please verify the VM is working correctly before proceeding."

                input(
                    message: 'VM Update Complete - Verify and Approve',
                    ok: 'Continue to Create Template',
                    submitter: 'admin,release-team'
                )
            }
        }

        stage('Shutdown VM') {
            steps {
                echo "Shutting down VM gracefully..."
                step([
                    $class: 'StopVirtualMachine',
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    datacenterNode: "${params.DATACENTER_NODE}",
                    vmId: "${params.VM_ID}"
                ])

                // Wait for shutdown to complete
                sleep(time: 30, unit: 'SECONDS')
            }
        }

        stage('Clone VM') {
            steps {
                echo "Cloning VM to ID ${params.TEMPLATE_VM_ID}..."
                step([
                    $class: 'CloneVirtualMachine',
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    datacenterNode: "${params.DATACENTER_NODE}",
                    vmId: "${params.VM_ID}",
                    targetVmId: "${params.TEMPLATE_VM_ID}",
                    cloneName: "${params.TEMPLATE_NAME}",
                    fullClone: true
                ])
            }
        }

        stage('Convert to Template') {
            steps {
                echo "Converting cloned VM to template..."
                step([
                    $class: 'ConvertToTemplate',
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    datacenterNode: "${params.DATACENTER_NODE}",
                    vmId: "${params.TEMPLATE_VM_ID}"
                ])
            }
        }

        stage('Delete Safety Snapshot') {
            steps {
                echo "Cleaning up safety snapshot..."
                step([
                    $class: 'DeleteSnapshot',
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    datacenterNode: "${params.DATACENTER_NODE}",
                    vmId: "${params.VM_ID}",
                    snapshotName: "pre-update-${BUILD_NUMBER}"
                ])
            }
        }
    }

    post {
        success {
            echo "Successfully created template ${params.TEMPLATE_NAME} (VM ID: ${params.TEMPLATE_VM_ID})"
            echo "Original VM ${params.VM_ID} has been updated and is currently shut down"
        }
        failure {
            echo "Pipeline failed! Safety snapshot 'pre-update-${BUILD_NUMBER}' is still available for recovery"
            echo "You can manually revert the VM to the snapshot if needed"
        }
        always {
            echo "Pipeline execution completed"
        }
    }
}
```

## Example 2: Scripted Pipeline Version

For users preferring scripted pipelines:

```groovy
node {
    def datacenter = 'user@pve - pve-host'
    def node = 'pve-node1'
    def vmId = '100'
    def templateVmId = '9000'
    def templateName = 'windows-server-2022-template'
    def snapshotName = "pre-update-${BUILD_NUMBER}"

    try {
        stage('Create Safety Snapshot') {
            step([$class: 'CreateSnapshot',
                  datacenterDescription: datacenter,
                  datacenterNode: node,
                  vmId: vmId,
                  snapshotName: snapshotName,
                  description: "Safety snapshot - Build ${BUILD_NUMBER}",
                  includeRam: false])
        }

        stage('Start VM') {
            step([$class: 'StartVirtualMachine',
                  datacenterDescription: datacenter,
                  datacenterNode: node,
                  vmId: vmId])
            sleep 60
        }

        stage('Run Puppet') {
            step([$class: 'RunCommand',
                  datacenterDescription: datacenter,
                  datacenterNode: node,
                  vmId: vmId,
                  command: 'puppet agent -t',
                  timeoutSeconds: 600,
                  waitForCompletion: true,
                  failOnError: false])
        }

        stage('Install Updates') {
            step([$class: 'RunCommand',
                  datacenterDescription: datacenter,
                  datacenterNode: node,
                  vmId: vmId,
                  command: 'powershell.exe -Command "Install-WindowsUpdate -AcceptAll -AutoReboot:$false"',
                  timeoutSeconds: 3600,
                  waitForCompletion: true,
                  failOnError: false])
        }

        stage('Reboot VM') {
            step([$class: 'RunCommand',
                  datacenterDescription: datacenter,
                  datacenterNode: node,
                  vmId: vmId,
                  command: 'shutdown /r /t 10',
                  timeoutSeconds: 30,
                  waitForCompletion: false,
                  failOnError: false])
            sleep 120
        }

        stage('User Verification') {
            input message: 'Verify VM and continue?', ok: 'Proceed'
        }

        stage('Shutdown VM') {
            step([$class: 'StopVirtualMachine',
                  datacenterDescription: datacenter,
                  datacenterNode: node,
                  vmId: vmId])
            sleep 30
        }

        stage('Clone VM') {
            step([$class: 'CloneVirtualMachine',
                  datacenterDescription: datacenter,
                  datacenterNode: node,
                  vmId: vmId,
                  targetVmId: templateVmId,
                  cloneName: templateName,
                  fullClone: true])
        }

        stage('Convert to Template') {
            step([$class: 'ConvertToTemplate',
                  datacenterDescription: datacenter,
                  datacenterNode: node,
                  vmId: templateVmId])
        }

        stage('Cleanup Snapshot') {
            step([$class: 'DeleteSnapshot',
                  datacenterDescription: datacenter,
                  datacenterNode: node,
                  vmId: vmId,
                  snapshotName: snapshotName])
        }

        echo "Template ${templateName} created successfully!"

    } catch (Exception e) {
        echo "Pipeline failed: ${e.message}"
        echo "Safety snapshot '${snapshotName}' is available for recovery"
        throw e
    }
}
```

## Additional Pipeline Examples

### Example 3: Simple VM Snapshot Before Deployment

```groovy
pipeline {
    agent any

    stages {
        stage('Backup Before Deploy') {
            steps {
                step([
                    $class: 'CreateSnapshot',
                    datacenterDescription: 'user@pve - pve-host',
                    datacenterNode: 'pve-node1',
                    vmId: '200',
                    snapshotName: "pre-deploy-${BUILD_NUMBER}",
                    description: "Snapshot before deployment ${BUILD_NUMBER}",
                    includeRam: true
                ])
            }
        }

        stage('Deploy Application') {
            steps {
                // Your deployment steps here
                echo "Deploying application..."
            }
        }
    }

    post {
        failure {
            echo "Deployment failed - snapshot available for rollback"
        }
    }
}
```

### Example 4: Linux VM Configuration Management

```groovy
pipeline {
    agent any

    stages {
        stage('Start VM') {
            steps {
                step([
                    $class: 'StartVirtualMachine',
                    datacenterDescription: 'user@pve - pve-host',
                    datacenterNode: 'pve-node1',
                    vmId: '150'
                ])
                sleep(time: 30, unit: 'SECONDS')
            }
        }

        stage('Update System') {
            steps {
                step([
                    $class: 'RunCommand',
                    datacenterDescription: 'user@pve - pve-host',
                    datacenterNode: 'pve-node1',
                    vmId: '150',
                    command: 'apt-get update && apt-get upgrade -y',
                    timeoutSeconds: 600,
                    waitForCompletion: true,
                    failOnError: true
                ])
            }
        }

        stage('Run Ansible') {
            steps {
                step([
                    $class: 'RunCommand',
                    datacenterDescription: 'user@pve - pve-host',
                    datacenterNode: 'pve-node1',
                    vmId: '150',
                    command: 'ansible-pull -U https://github.com/org/ansible-configs.git',
                    timeoutSeconds: 300,
                    waitForCompletion: true,
                    failOnError: true
                ])
            }
        }
    }
}
```

## Available Build Steps

All Proxmox build steps use the `$class` parameter for pipeline syntax:

- **`StartVirtualMachine`** - Start a VM
- **`StopVirtualMachine`** - Stop a VM gracefully
- **`PauseVirtualMachine`** - Pause a running VM
- **`ResumeVirtualMachine`** - Resume a paused VM
- **`HibernateVirtualMachine`** - Hibernate a VM
- **`CreateSnapshot`** - Create a VM snapshot
- **`DeleteSnapshot`** - Delete a VM snapshot
- **`RevertToSnapshot`** - Revert VM to a snapshot
- **`CloneVirtualMachine`** - Clone a VM
- **`CloneVirtualMachineAdvanced`** - Clone with advanced options
- **`ConvertToTemplate`** - Convert VM to template
- **`RunCommand`** - Execute command in VM via guest agent

## Common Parameters

### Datacenter Connection
- `datacenterDescription` - The datacenter identifier (e.g., "user@pve - hostname")
- `datacenterNode` - The Proxmox node name
- `vmId` - The VM ID to operate on

### RunCommand Options
- `command` - Command to execute in the VM
- `timeoutSeconds` - Timeout for command execution (default: 300)
- `waitForCompletion` - Wait for command to finish (default: true)
- `failOnError` - Fail build if command returns non-zero exit code (default: false)

### Snapshot Options
- `snapshotName` - Name of the snapshot
- `description` - Optional description
- `includeRam` - Include RAM state in snapshot (default: false)

## Prerequisites

1. **QEMU Guest Agent** must be installed and running in the VM for `RunCommand` to work
2. **Credentials** must be configured in Jenkins for Proxmox authentication
3. **VM must be running** for guest agent commands (start it first if needed)
4. **Proper permissions** must be granted to the Proxmox user

## Notes

- Guest agent commands require the QEMU guest agent to be installed and running in the VM
- For Windows VMs, the PSWindowsUpdate PowerShell module may need to be installed first
- Always create a snapshot before making significant changes
- Full clones are recommended for templates to avoid dependency issues
- Shutdown the VM before cloning to ensure data consistency
- Consider using `sleep` steps to allow operations to complete fully

## Troubleshooting

### Command execution fails
- Verify QEMU guest agent is installed: `qm guest cmd <vmid> ping`
- Check agent is running in the VM
- Ensure VM is fully booted before executing commands

### Snapshot creation fails
- Check available disk space on Proxmox storage
- Verify VM is not in an inconsistent state
- RAM snapshots require more space and time

### Clone operation fails
- Ensure target VM ID is not already in use
- Verify sufficient storage space is available
- Check Proxmox user has clone permissions
