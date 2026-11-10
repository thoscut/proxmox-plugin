# Roadmap

Findings from the expert reviews that are worth doing but too large or too risky for a
bug-fix pull request. Ordered roughly by value.

## Robustness

- **Stable datacenter reference for agents.** Agents store `user@realm - hostname` and match
  it against the datacenter's description, which now depends on the credential. Store the
  cloud name (`Cloud.name`) instead and migrate old agents in `readResolve`. Until then the
  hostname fallback in `Datacenter.findByDescription` covers the common cases.
- **Refresh credentials on change.** Resolved credentials are cached for a minute. A
  `SaveableListener` on the credentials store could invalidate the cache immediately.
- **Inbound agents and "After connect".** The launcher waits for the inbound agent for the
  configured period. A dedicated "wait for agent" option, separate from the boot delay for
  SSH agents, would make the behaviour explicit.
- **Shutting down VMs on Jenkins restart.** With "After connect", every disconnect, including a
  controller restart, shuts the VM down. An option to skip the shutdown on restart would keep
  agents warm.

## Features

- **Cloud provisioning.** `Datacenter.provision` returns nothing; agents are static. Provision
  agents on demand from a template VM (clone + snapshot) when the label has excess workload.
- **LXC containers.** Only QEMU VMs are supported; the `nodes/{node}/lxc` endpoints offer the
  same start/stop/snapshot operations.
- **Node-independent VM lookup.** VMs are addressed by node and vmid; with migrations or HA the
  node changes. Resolve the current node via `cluster/resources`.

## Code health

- **Replace Unirest 3.x.** It is in maintenance and bundles Apache HttpClient 4. Use the JDK
  `java.net.http.HttpClient` or the `apache-httpcomponents-client-5-api` plugin.
- **Connector tests against a fake server.** The Connector is only unit-tested for parsing and
  URL building. A WireMock HTTPS server (or a package-private base-URL constructor and a plain
  `HttpServer`) would cover login, ticket renewal, 401 retry, task polling and error mapping.
- **Internationalisation.** Display names and validation messages are hard-coded; move them to
  `Messages.properties`.
