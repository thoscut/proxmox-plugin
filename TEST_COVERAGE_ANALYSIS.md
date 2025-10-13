# Test Coverage Analysis - Proxmox Plugin

## Current Test Coverage Summary

### Existing Tests (46 total)
1. **InjectedTest** (11 tests) - Auto-generated injection tests
2. **CloudMenuGenerationTest** (7 tests) - Cloud UI menu generation
3. **CloudRegistrationRegressionTest** (5 tests) - Cloud registration
4. **DatacenterCloudTest** (3 tests) - Datacenter basic functionality
5. **BuildStep Simple Tests** (19 tests) - Build step constructors/basic validation
6. **ConfigurationAsCodeTest** (0 tests) - JCasC compatibility check

### Coverage Gaps Analysis

## Core Components - Missing Test Coverage

### 1. **VirtualMachineSlave** - 0% Coverage
**File**: `VirtualMachineSlave.java`
**Critical Functions Not Tested**:
- `incrementBuildsExecuted()` - Limited builds lifecycle
- `_terminate()` - Termination logic (NEW - cloud-stats integration)
- `getId()` / `setProvisioningId()` - Cloud-stats tracking (NEW)
- VM deletion after limited builds reached
- Channel closing before node removal

**Proposed Tests**:
```java
@Test
void should_track_provisioning_id_for_cloud_stats()
@Test
void should_increment_builds_executed_counter()
@Test
void should_terminate_vm_when_limited_builds_reached()
@Test
void should_close_channel_before_node_removal()
@Test
void should_delegate_terminate_to_datacenter()
@Test
void should_handle_terminate_when_datacenter_not_found()
```

### 2. **VirtualMachineSlaveComputer** - 0% Coverage
**File**: `VirtualMachineSlaveComputer.java`
**Critical Functions Not Tested**:
- `getId()` - Cloud-stats integration (NEW)
- `tryReconnect()` - Reconnection logic with snapshot revert handling
- `taskAccepted()` - BEFORE_JOB snapshot revert
- `taskCompleted()` - AFTER_JOB snapshot revert
- `waitForAgentReconnection()` - Channel management after VM restart
- Snapshot revert concurrency control

**Proposed Tests**:
```java
@Test
void should_return_provisioning_id_from_slave()
@Test
void should_skip_reconnect_when_reverting_snapshot()
@Test
void should_skip_reconnect_when_permanently_offline()
@Test
void should_perform_before_job_snapshot_revert()
@Test
void should_perform_after_job_snapshot_revert()
@Test
void should_wait_for_agent_reconnection_after_revert()
@Test
void should_close_old_channel_before_reconnection()
@Test
void should_prevent_concurrent_snapshot_reverts()
```

### 3. **ProxmoxPlannedNode** - 0% Coverage (NEW)
**File**: `ProxmoxPlannedNode.java`
**Critical Functions Not Tested**:
- Constructor with provisioning ID
- `getId()` - Returns provisioning activity ID
- Integration with TrackedPlannedNode

**Proposed Tests**:
```java
@Test
void should_create_planned_node_with_provisioning_id()
@Test
void should_return_correct_provisioning_id()
@Test
void should_extend_tracked_planned_node()
@Test
void should_store_cloud_and_template_names()
```

### 4. **Datacenter** - Minimal Coverage (~10%)
**File**: `Datacenter.java`
**Critical Functions Not Tested**:
- `provision()` - Main provisioning logic with cloud-stats (MODIFIED)
- `canProvision()` - Capacity and template checks
- `terminate()` - VM termination logic
- `cleanupOrphanedNodes()` - Node cleanup
- VM stopping/deletion sequence
- Statistics recording

**Proposed Tests**:
```java
@Test
void should_create_provisioning_activity_with_id()
@Test
void should_return_proxmox_planned_node_on_provision()
@Test
void should_set_provisioning_id_on_virtual_machine_slave()
@Test
void should_record_provisioning_success_with_duration()
@Test
void should_respect_instance_cap_when_provisioning()
@Test
void should_cleanup_orphaned_nodes_before_provisioning()
@Test
void should_terminate_vm_and_remove_from_jenkins()
@Test
void should_stop_vm_before_deletion()
@Test
void should_handle_vm_deletion_failure_gracefully()
```

### 5. **ProxmoxCloudSlaveTemplate** - Minimal Coverage (~5%)
**File**: `ProxmoxCloudSlaveTemplate.java`
**Critical Functions Not Tested**:
- `provision()` - VM cloning and creation
- `waitForGuestAgentReady()` - Guest agent readiness (NEW)
- `executePostCloneCommand()` - Post-clone script execution
- Clone operation with full/linked mode
- Template validation

**Proposed Tests**:
```java
@Test
void should_clone_vm_from_template()
@Test
void should_start_cloned_vm_if_configured()
@Test
void should_wait_for_guest_agent_if_enabled()
@Test
void should_execute_post_clone_command_if_configured()
@Test
void should_handle_guest_agent_timeout()
@Test
void should_create_full_clone_when_configured()
@Test
void should_create_linked_clone_by_default()
```

### 6. **ProxmoxCloudStatistics** - 0% Coverage
**File**: `ProxmoxCloudStatistics.java`
**Critical Functions Not Tested**:
- Statistics collection and reporting
- Node health monitoring
- Provisioning success/failure tracking
- getCurrentSlaveCount()
- Health summary generation

**Proposed Tests**:
```java
@Test
void should_track_provisioning_attempts()
@Test
void should_record_provisioning_success_with_duration()
@Test
void should_record_provisioning_failures()
@Test
void should_calculate_success_rate()
@Test
void should_count_online_and_offline_slaves()
@Test
void should_monitor_node_health()
@Test
void should_generate_health_summary()
```

### 7. **VirtualMachineLauncher** - 0% Coverage
**File**: `VirtualMachineLauncher.java`
**Critical Functions Not Tested**:
- `launch()` - VM launching logic
- `revertSnapshot()` - Snapshot revert implementation
- VM start with guest agent waiting
- Launcher delegation logic

**Proposed Tests**:
```java
@Test
void should_launch_vm_with_delegate_launcher()
@Test
void should_start_vm_if_configured()
@Test
void should_revert_to_snapshot()
@Test
void should_wait_for_vm_to_start()
@Test
void should_handle_launch_failure()
```

### 8. **QemuGuestAgentLauncher** - 0% Coverage
**File**: `QemuGuestAgentLauncher.java`
**Critical Functions Not Tested**:
- Connection establishment via guest agent
- Direct connection mode
- Instance identity mode
- WebSocket support
- Connection retry logic

**Proposed Tests**:
```java
@Test
void should_connect_via_guest_agent()
@Test
void should_use_direct_connection_when_enabled()
@Test
void should_use_instance_identity_when_enabled()
@Test
void should_use_websocket_when_enabled()
@Test
void should_retry_connection_up_to_max_retries()
@Test
void should_timeout_after_configured_seconds()
```

### 9. **Connector (PVE API)** - 0% Coverage
**File**: `pve2api/Connector.java`
**Critical Functions Not Tested**:
- Proxmox API authentication
- VM operations (start, stop, clone, delete)
- Snapshot operations
- Guest agent availability check
- Task status polling

**Proposed Tests** (Integration Tests):
```java
@Test
void should_authenticate_with_proxmox_api()
@Test
void should_start_qemu_machine()
@Test
void should_stop_qemu_machine()
@Test
void should_clone_qemu_machine()
@Test
void should_delete_qemu_machine()
@Test
void should_check_guest_agent_availability()
@Test
void should_wait_for_task_completion()
@Test
void should_rollback_snapshot()
```

## Priority Recommendations

### High Priority (Critical for Production)
1. **VirtualMachineSlave** - Limited builds and termination logic
2. **VirtualMachineSlaveComputer** - Snapshot revert and reconnection
3. **Datacenter** - Provisioning and cloud-stats integration
4. **ProxmoxPlannedNode** - New cloud-stats integration component

### Medium Priority (Important for Reliability)
5. **ProxmoxCloudSlaveTemplate** - Template provisioning and guest agent
6. **ProxmoxCloudStatistics** - Statistics accuracy
7. **VirtualMachineLauncher** - Launch and snapshot operations

### Lower Priority (Good to Have)
8. **QemuGuestAgentLauncher** - Connection methods
9. **Connector** - API integration (consider integration tests)

## Test Types Needed

### Unit Tests
- Component isolation with mocks
- Business logic validation
- Error handling

### Integration Tests
- Datacenter + Template + Slave interaction
- Cloud-stats plugin integration
- Proxmox API communication (with mock server)

### End-to-End Tests
- Full provisioning lifecycle
- Snapshot revert with job execution
- Limited builds termination flow

## Estimated Coverage Impact

Current: ~15% estimated coverage
With proposed tests: ~70-80% estimated coverage

### Key Gaps Remaining
- Proxmox API integration (requires mock/test server)
- Jenkins UI interaction tests
- Network failure scenarios
- Concurrent provisioning stress tests

## Next Steps

1. Create test utilities:
   - Mock Proxmox API server
   - Test fixtures for common scenarios
   - Helper methods for creating test objects

2. Implement high-priority tests first:
   - Focus on cloud-stats integration (NEW code)
   - Cover critical paths (provisioning, termination)
   - Test error handling

3. Add integration tests:
   - Full provisioning flow
   - Snapshot revert workflow
   - Limited builds lifecycle

4. Set up coverage reporting:
   - Configure JaCoCo properly
   - Set coverage goals (>70%)
   - Add to CI pipeline
