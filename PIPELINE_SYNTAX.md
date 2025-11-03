# Proxmox Plugin Pipeline Syntax

The Proxmox plugin now follows the **vSphere plugin pattern**, providing a unified `proxmox` step that wraps all build step operations.

## Basic Syntax

```groovy
proxmox(
    datacenterDescription: 'your-datacenter-name',
    buildStep: [$class: 'BuildStepClassName', /* build step parameters */]
)
```

## Complete Example Pipeline

```groovy
pipeline {
    agent any

    parameters {
        string(name: 'DATACENTER_DESCRIPTION', defaultValue: 'my-proxmox')
        string(name: 'DATACENTER_NODE', defaultValue: 'pve1')
        string(name: 'VM_ID', defaultValue: '100')
    }

    stages {
        stage('Create Safety Snapshot') {
            steps {
                echo "Creating safety snapshot before updates..."
                proxmox(
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    buildStep: [$class: 'CreateSnapshot',
                        datacenterNode: "${params.DATACENTER_NODE}",
                        vmId: "${params.VM_ID}",
                        snapshotName: "pre-update-${BUILD_NUMBER}",
                        description: "Safety snapshot before updates - Build ${BUILD_NUMBER}",
                        includeRam: false
                    ]
                )
            }
        }

        stage('Start VM') {
            steps {
                proxmox(
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    buildStep: [$class: 'StartVirtualMachine',
                        datacenterNode: "${params.DATACENTER_NODE}",
                        vmId: "${params.VM_ID}",
                        waitForServices: true,
                        startupWaitSeconds: 30
                    ]
                )
            }
        }

        stage('Run Updates') {
            steps {
                proxmox(
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    buildStep: [$class: 'RunCommand',
                        datacenterNode: "${params.DATACENTER_NODE}",
                        vmId: "${params.VM_ID}",
                        command: "apt-get update && apt-get upgrade -y",
                        timeoutSeconds: 300,
                        waitForCompletion: true,
                        failOnError: true
                    ]
                )
            }
        }

        stage('Stop VM') {
            steps {
                proxmox(
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    buildStep: [$class: 'StopVirtualMachine',
                        datacenterNode: "${params.DATACENTER_NODE}",
                        vmId: "${params.VM_ID}",
                        stopType: "shutdown",
                        waitForCompletion: true,
                        timeoutSeconds: 120
                    ]
                )
            }
        }

        stage('Delete Safety Snapshot') {
            steps {
                proxmox(
                    datacenterDescription: "${params.DATACENTER_DESCRIPTION}",
                    buildStep: [$class: 'DeleteSnapshot',
                        datacenterNode: "${params.DATACENTER_NODE}",
                        vmId: "${params.VM_ID}",
                        snapshotName: "pre-update-${BUILD_NUMBER}"
                    ]
                )
            }
        }
    }
}
```

## Available Build Steps

### Snapshot Operations

#### CreateSnapshot
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'CreateSnapshot',
        datacenterNode: 'pve1',
        vmId: '100',
        snapshotName: 'my-snapshot',
        description: 'Snapshot description',
        includeRam: false
    ]
)
```

#### DeleteSnapshot
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'DeleteSnapshot',
        datacenterNode: 'pve1',
        vmId: '100',
        snapshotName: 'my-snapshot'
    ]
)
```

#### RevertToSnapshot
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'RevertToSnapshot',
        datacenterNode: 'pve1',
        vmId: '100',
        snapshotName: 'my-snapshot',
        startAfterRevert: true
    ]
)
```

### VM Power Management

#### StartVirtualMachine
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'StartVirtualMachine',
        datacenterNode: 'pve1',
        vmId: '100',
        waitForServices: true,
        startupWaitSeconds: 30
    ]
)
```

#### StopVirtualMachine
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'StopVirtualMachine',
        datacenterNode: 'pve1',
        vmId: '100',
        stopType: 'shutdown',  // or 'stop' for hard stop
        waitForCompletion: true,
        timeoutSeconds: 120
    ]
)
```

#### PauseVirtualMachine
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'PauseVirtualMachine',
        datacenterNode: 'pve1',
        vmId: '100',
        waitForCompletion: true
    ]
)
```

#### ResumeVirtualMachine
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'ResumeVirtualMachine',
        datacenterNode: 'pve1',
        vmId: '100',
        waitSeconds: 30
    ]
)
```

#### HibernateVirtualMachine
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'HibernateVirtualMachine',
        datacenterNode: 'pve1',
        vmId: '100',
        waitForCompletion: true
    ]
)
```

### VM Operations

#### CloneVirtualMachine
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'CloneVirtualMachine',
        datacenterNode: 'pve1',
        vmId: '100',
        cloneName: 'my-clone',
        targetVmId: '101',
        fullClone: true,
        startAfterClone: false
    ]
)
```

#### ConvertToTemplate
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'ConvertToTemplate',
        datacenterNode: 'pve1',
        vmId: '100'
    ]
)
```

#### RunCommand
```groovy
proxmox(
    datacenterDescription: 'my-proxmox',
    buildStep: [$class: 'RunCommand',
        datacenterNode: 'pve1',
        vmId: '100',
        command: 'systemctl status nginx',
        timeoutSeconds: 60,
        waitForCompletion: true,
        failOnError: true
    ]
)
```

## Scripted Pipeline Syntax

The same syntax works in scripted pipelines:

```groovy
node {
    stage('Create Snapshot') {
        proxmox(
            datacenterDescription: 'my-proxmox',
            buildStep: [$class: 'CreateSnapshot',
                datacenterNode: 'pve1',
                vmId: '100',
                snapshotName: 'my-snapshot',
                description: 'Snapshot description',
                includeRam: false
            ]
        )
    }
}
```

## Using the Pipeline Snippet Generator

1. Go to your pipeline job → **Pipeline Syntax**
2. In the **Sample Step** dropdown, select **proxmox: Proxmox Build Step**
3. Select your **Datacenter** from the dropdown
4. In the **Proxmox Action** dropdown, select the build step you want:
   - Proxmox: Clone Virtual Machine
   - Proxmox: Clone Virtual Machine (Advanced)
   - Proxmox: Convert to Template
   - Proxmox: Create Snapshot
   - Proxmox: Delete Snapshot
   - Proxmox: Hibernate Virtual Machine
   - Proxmox: Pause Virtual Machine
   - Proxmox: Resume Virtual Machine
   - Proxmox: Revert to Snapshot
   - **Run Command on Proxmox VM (Guest Agent)** ← Now available!
   - Proxmox: Start Virtual Machine
   - Proxmox: Stop Virtual Machine
5. Fill in the parameters for your selected action
6. Click **Generate Pipeline Script**

The generator will create the complete `proxmox()` step syntax for you.

**Note**: All 12 Proxmox build steps are now properly filtered and available in the dropdown. The filtering ensures only Proxmox-specific actions appear, not other Jenkins build steps.

## Notes

1. **Datacenter Description**: Must match the datacenter name configured in Jenkins → Manage Jenkins → Configure System → Clouds

2. **Datacenter Node**: The Proxmox node name (e.g., 'pve1', 'pve2')

3. **VM ID**: The virtual machine ID as a string (e.g., '100', '101')

4. **Symbol**: The `proxmox` step is registered with the symbol `@Symbol("proxmox")` for use in pipelines

5. **Build Steps**: All build step class names follow the pattern `ClassName` (e.g., `CreateSnapshot`, not `proxmoxCreateSnapshot`)

6. **After Installation**: You must **restart Jenkins completely** (not just reload configuration) for the new `proxmox` step to appear in the Pipeline Snippet Generator
