# Jenkins Proxmox Plugin

Advanced Jenkins cloud integration for Proxmox virtual environments with dynamic provisioning and comprehensive VM lifecycle management.

[![Proxmox Plugin](https://img.shields.io/jenkins/plugin/v/proxmox.svg)](https://plugins.jenkins.io/proxmox)
[![ChangeLog](https://img.shields.io/github/release/jenkinsci/proxmox-plugin.svg?label=changelog)](https://github.com/jenkinsci/proxmox-plugin/releases/latest)
[![Installs](https://img.shields.io/jenkins/plugin/i/proxmox.svg?color=blue)](https://plugins.jenkins.io/proxmox)
[![License](https://img.shields.io/github/license/jenkinsci/proxmox-plugin.svg)](LICENSE)
[![Build Status](https://ci.jenkins.io/job/Plugins/job/proxmox-plugin/job/master/badge/icon)](https://ci.jenkins.io/job/Plugins/job/proxmox-plugin/job/master/)

## Description

This plugin provides enterprise-grade Jenkins integration with Proxmox virtual environments, enabling dynamic VM provisioning, template-based scaling, and comprehensive virtual machine lifecycle management. Inspired by the vSphere Cloud plugin architecture, it transforms Jenkins into a powerful cloud orchestration platform for Proxmox infrastructure.

## ✨ Key Features

### 🚀 **Dynamic Cloud Provisioning**
- **On-demand VM creation** from templates based on build queue demand
- **Template-based scaling** with configurable instance caps per template
- **Label-based provisioning** for targeted build environments
- **Automatic resource management** with intelligent VM ID allocation
- **Concurrent provisioning** with thread-safe operations

### 🏗️ **Build Pipeline Integration**
Comprehensive build steps for VM lifecycle management:
- **Start Virtual Machine** - Power on VMs with configurable startup delays
- **Stop Virtual Machine** - Graceful shutdown or forced stop options
- **Clone Virtual Machine** - Create new VMs from templates with snapshot support
- **Revert to Snapshot** - Reset VMs to clean states with optional restart

### 📋 **Template Management**
- **Multi-template support** within single datacenter configurations  
- **Snapshot integration** for consistent VM states
- **Capacity planning** with instance limits and resource allocation
- **Automated VM naming** with collision avoidance

### 🔧 **Advanced Configuration**
- **Configuration as Code** support with YAML serialization
- **Dynamic UI elements** with cascading dropdowns for datacenters, nodes, and VMs
- **Form validation** with helpful error messages and warnings
- **Backward compatibility** with existing static configurations

## Configuration

### 🏢 **Datacenter Cloud Setup**

#### Basic Configuration
1. Navigate to **"Manage Jenkins"** → **"Configure System"**
2. In the **"Cloud"** section, click **"Add cloud"** → **"Proxmox Datacenter"**
3. Configure connection details:
   - **Hostname**: Proxmox server address (e.g., `proxmox.company.com:8006`)
   - **Username**: Proxmox user account
   - **Realm**: Authentication realm (typically `pve`)
   - **Password**: User password (stored securely)
   - **Ignore SSL**: Check if using self-signed certificates
   - **Instance Cap**: Global limit for concurrent VMs (0 = unlimited)

#### Template Configuration
Add one or more **Proxmox Cloud Slave Templates** for dynamic provisioning:

- **Template Name**: Descriptive name for the template
- **Labels**: Jenkins labels for targeted builds (e.g., `linux docker maven`)
- **Remote FS**: Jenkins workspace directory on VM (e.g., `/home/jenkins`)
- **Executors**: Number of concurrent builds per VM
- **Datacenter Node**: Target Proxmox node
- **Template VM ID**: Source VM/template to clone from
- **Snapshot Name**: (Optional) Specific snapshot to use
- **Instance Cap**: Max VMs from this template
- **Max Idle Minutes**: VM lifetime when idle
- **Start VM**: Auto-start after cloning
- **Startup Wait**: Delay after VM start (seconds)
- **Revert Policy**: When to reset to snapshot

### 🖥️ **Static Virtual Machine Agents**

For non-dynamic agents:
1. Go to **"Manage Jenkins"** → **"Manage Nodes"**
2. Click **"New Node"** → **"Agent virtual machine running on a Proxmox datacenter"**
3. Configure VM details and connection settings

### 🔧 **Build Steps Configuration**

Add VM management steps to your Jenkins pipeline:

#### Freestyle Projects
1. In build configuration, click **"Add build step"**
2. Select from available Proxmox build steps:
   - **Proxmox: Start Virtual Machine**
   - **Proxmox: Stop Virtual Machine**  
   - **Proxmox: Clone Virtual Machine**
   - **Proxmox: Revert to Snapshot**

#### Pipeline Scripts
```groovy
pipeline {
    agent any
    stages {
        stage('Prepare VM') {
            steps {
                // Start a specific VM
                proxmoxStartVM(
                    datacenterDescription: 'proxmox-user@pve - proxmox.company.com',
                    datacenterNode: 'proxmox-node1',
                    vmId: '100',
                    startupWaitSeconds: 30
                )
                
                // Clone a VM for testing
                proxmoxCloneVM(
                    datacenterDescription: 'proxmox-user@pve - proxmox.company.com',
                    datacenterNode: 'proxmox-node1', 
                    vmId: '999',
                    targetVmId: '1001',
                    cloneName: 'test-vm-build-${BUILD_NUMBER}',
                    startAfterClone: true,
                    snapshotName: 'clean-state'
                )
            }
        }
        stage('Run Tests') {
            agent { label 'proxmox-template' } // Uses dynamic provisioning
            steps {
                // Your test commands here
                sh 'mvn test'
            }
        }
        stage('Cleanup') {
            steps {
                // Revert VM to clean state
                proxmoxRevertToSnapshot(
                    datacenterDescription: 'proxmox-user@pve - proxmox.company.com',
                    datacenterNode: 'proxmox-node1',
                    vmId: '100', 
                    snapshotName: 'clean-state',
                    startAfterRevert: false
                )
            }
        }
    }
}
```

### 📝 **Configuration as Code**

Example YAML configuration:
```yaml
jenkins:
  clouds:
    - proxmoxDatacenter:
        hostname: "proxmox.company.com"
        username: "jenkins-user"
        realm: "pve"
        password: "{ENCRYPTED_PASSWORD}"
        ignoreSSL: true
        instanceCap: 10
        templates:
          - templateName: "linux-builder"
            labels: "linux docker maven"
            remoteFS: "/home/jenkins"
            numExecutors: "2"
            datacenterNode: "proxmox-node1"
            templateVmId: "999"
            snapshotName: "jenkins-ready"
            instanceCap: 5
            maxIdleMinutes: 30
            startVM: true
            startupWaitingPeriodSeconds: 45
            revertPolicy: "BEFORE_JOB"
            launcher:
              ssh:
                host: "${VM_IP}"
                credentialsId: "jenkins-ssh-key"
            retentionStrategy:
              demand:
                idleDelay: 10
                inDemandDelay: 0
```

## Requirements

- **Proxmox VE**: 6.0 or later
- **Jenkins**: 2.401.3 or later  
- **Java**: 17 or later
- **VM Requirements**:
  - QEMU virtual machines only (LXC containers not supported)
  - VM templates with snapshot support recommended
  - Network connectivity between Jenkins and VMs
  - SSH access or JNLP connectivity configured

## Limitations

- Only QEMU virtual machines supported (LXC containers planned for future releases)
- VM ready state checking during operations is basic
- Large-scale deployments may require tuning of timeout values

## 📦 Installation

### From Jenkins Plugin Manager (Recommended)
1. Navigate to **"Manage Jenkins"** → **"Plugin Manager"**
2. Go to the **"Available"** tab
3. Search for **"Proxmox"**
4. Check the plugin and click **"Install without restart"**

### Manual Installation
1. Clone this repository:
   ```bash
   git clone https://github.com/jenkinsci/proxmox-plugin.git
   cd proxmox-plugin
   ```

2. Build the plugin:
   ```bash
   mvn clean package
   ```

3. Install in Jenkins:
   - Go to **"Manage Jenkins"** → **"Plugin Manager"**
   - Click the **"Advanced"** tab  
   - Upload `target/proxmox.hpi` in the **"Upload Plugin"** section
   - Restart Jenkins when prompted

### Development/Testing
To run a Jenkins test instance with the plugin:
```bash
mvn hpi:run
```
Access at: `http://localhost:8080/jenkins`

## 🔄 Migration Guide

### Upgrading from Earlier Versions
The enhanced plugin maintains backward compatibility:

- **Existing static VM agents** continue to work unchanged
- **Existing datacenter configurations** are preserved
- **New cloud features** can be added alongside existing setups

### Breaking Changes
- None in this version - fully backward compatible

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Development Setup
```bash
# Build and test
mvn clean test

# Run integration tests  
mvn clean integration-test

# Start development server
mvn hpi:run
```

## 📋 Changelog

### Latest (Extended Cloud Features)
- ✅ **Dynamic VM provisioning** from templates based on demand
- ✅ **Template-based scaling** with instance caps and resource management
- ✅ **Build pipeline integration** with comprehensive VM operation steps
- ✅ **Snapshot management** for consistent VM states
- ✅ **Configuration as Code** support with YAML serialization
- ✅ **Enhanced UI** with dynamic dropdowns and form validation
- ✅ **Improved error handling** and logging throughout
- ✅ **Thread-safe operations** for concurrent provisioning
- ✅ **Backward compatibility** maintained for existing configurations

### Previous Versions
- For recent versions, see [GitHub Releases](https://github.com/jenkinsci/proxmox-plugin/releases)
- For versions 0.2.1 and older, see the [Wiki page](https://wiki.jenkins.io/display/JENKINS/Proxmox+Plugin)

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🙏 Acknowledgments

- Inspired by the [Jenkins vSphere Cloud Plugin](https://github.com/jenkinsci/vsphere-cloud-plugin) architecture
- Built on the Jenkins Plugin framework
- Proxmox VE REST API integration

