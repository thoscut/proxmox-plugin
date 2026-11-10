# Roadmap

Findings from the expert reviews that are worth doing but too large or too risky for a
bug-fix pull request. Ordered roughly by value; the size in brackets is a rough estimate.

## Robustness

- **User-chosen cloud name, then a stable datacenter reference for agents.** The cloud name
  is derived from the hostname and the credential, so it changes with the credential, the name
  typed in "New cloud" is ignored (core logs "should be updated to support user provided name"),
  and the cloud URL and the Configuration as Code export move. Add `name` to the constructor and
  the form (falling back to the derived name for old configurations), then let agents refer to
  the cloud by name and migrate the `user@realm - hostname` reference in `readResolve`. Until
  then the hostname fallback in `Datacenter.findByDescription` covers the common cases. (M)
- **Typed exceptions for configuration and authentication errors.** `IllegalStateException`
  signals a missing credential or datacenter and `LoginException` an authentication failure, so
  callers catch `RuntimeException` and `IllegalStateException` broadly, and a failing credentials
  provider breaks the Clouds page. Introduce `ProxmoxConfigurationException` and
  `ProxmoxAuthException extends ProxmoxException` and catch only those. (S; dropping
  `LoginException` from `Connector` is a 1.0 API change)
- **Dropdown errors are swallowed.** When loading nodes, VMs or snapshots fails, the dropdowns
  stay empty and the cause is only in the Jenkins log. Once the `Datacenter` methods propagate a
  typed exception, show an `[Error: ...]` entry instead. (S, after the item above)
- **Refresh credentials on change.** Resolved credentials are cached for a minute. A
  `SaveableListener` on the credentials store could invalidate the cache immediately. (S)
- **Explicit "wait for agent" option.** The launcher waits for an inbound agent for the
  configured period (default 300 s) and sleeps the period for other launchers even when the
  snapshot is `current` and the VM was already running. A dedicated option, separate from the
  boot delay for SSH agents, and skipping the wait when the VM was untouched would make the
  behaviour explicit. (S)
- **Shutting down VMs on Jenkins restart.** With "After connect", every disconnect, including a
  controller restart (`Jenkins.isTerminating()`), shuts the VM down. An option to skip the
  shutdown on restart would keep agents warm. (S)

## Features

- **Node-independent VM lookup.** VMs are addressed by node and vmid; with migrations or HA the
  node changes. Resolve the current node via `cluster/resources?type=vm` and make the node field
  optional. (S/M)
- **LXC containers.** Only QEMU VMs are supported; the `nodes/{node}/lxc` endpoints offer the
  same start/stop/snapshot operations. (M)
- **Cloud provisioning.** `Datacenter.provision` returns nothing; agents are static. Provision
  agents on demand from a template VM (clone + snapshot) when the label has excess workload. (L)

## Code health

- **Single owner for the agent's Proxmox settings.** `VirtualMachineSlave` stores a copy of
  every launcher field and calls a `Slave` constructor deprecated since 2.184. Use
  `Slave(String, String, ComputerLauncher)` with `@DataBoundSetter`s, let the slave's getters
  delegate to `VirtualMachineLauncher`, and migrate the old fields in `readResolve`. Extend the
  Configuration as Code tests for the new constructor shape. (M)
- **Reuse the core agent form.** `configure-entries.jelly` copies the node fields from core
  (`DumbSlave`). Use `f:dropdownDescriptorSelector` for the delegate launcher with descriptors
  from `DelegatingComputerLauncher.DescriptorImpl.applicableDescriptors` (the launcher
  descriptor should extend that class), and include the core page where possible. (S/M, after
  the item above)
- **A `TaskStatus` value type instead of Unirest's `JSONObject`.** `VirtualMachineLauncher`
  imports `kong.unirest.json.JSONObject` for task results. A small value type in `pve2api`
  keeps the HTTP library inside `Connector`. (S, prerequisite for the next item)
- **Replace Unirest 3.x.** It is in maintenance and bundles Apache HttpClient 4. Use the JDK
  `java.net.http.HttpClient` or the `apache-httpcomponents-client-5-api` plugin. (M)
- **Connector tests against a fake server.** The Connector is only unit-tested for parsing and
  URL building; the `setTaskTimeoutMs` hook exists but is unused. A WireMock HTTPS server (or a
  package-private base-URL constructor and a plain `HttpServer`) would cover login, ticket
  renewal, 401 retry, task polling and error mapping. (M)
- **Mark internals `@Restricted(NoExternalUse.class)`.** The `pve2api` package, `ConnectorPool`,
  `VirtualMachineLauncher.findDatacenterInstance` and `VirtualMachineSlave.getDelegateLauncher`
  are public only because other packages or Jelly need them. A `package-info.java` and two
  annotations document that they are not API. (S)
- **Internationalisation.** Display names and validation messages are hard-coded; move them to
  `Messages.properties`. (S/M)

## 1.0

- **Treat a missing `startVM` as true.** Old configurations and YAML that omits `startVM` have
  no value and are treated as `false`, while the UI default is `true`. (Configuration as Code
  does export `startVM: false`, because it is a constructor parameter.) The README documents
  `false`, so change the semantics only with a release note. (S)
- **Remove deprecated API.** The 5-argument `Datacenter` constructor,
  `VirtualMachineLauncher.getDelegate()`, the legacy `delegate`/`WAIT_TIME_MS` fields, the
  username/password `Connector` constructors and the `LoginException` on `Connector` only exist
  for old `config.xml` files and tests. (S)
