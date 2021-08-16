## Simple test for grpc routing via XDS

This is a simple XDS server for reproducing routing problem from C++ client library.

### Desired behaviour
```
   ┌────────────────────────┐             ┌────────────────────────┐
   │ Client (Normal)        │             │ Client (Priority)      │
   │ calls xds:///my-domain │             │ calls xds:///my-domain │
   └──────────────────────┬─┘             └─────────────────────┬──┘
                          │                                     │
                          │                                     │
                          │                                     │
                          │                                     │
                          │                                     │
                          │ Routed to normal                    │ Routed to priority
                          │    group                            │      group
                          │                                     │
                          │                                     │
                          │                                     │
┌─────────────────────────┼─────────────────────────────────────┼────────────────┐
│   XDS server            │                                     │                │
│                         │                                     │                │
│  ┌──────────────────────┼─────────────────────────────────────┼─────────────┐  │
│  │  SimpleCache<String> │                                     │             │  │
│  │                      │                                     │             │  │
│  │ ┌────────────────────┼────────────┐    ┌───────────────────┼──────────┐  │  │
│  │ │  Normal group      │            │    │  Priority group   │          │  │  │
│  │ │   ┌────────────────▼────┐       │    │  ┌────────────────▼───┐      │  │  │
│  │ │   │  xds:///my-domain   │       │    │  │ xds:///my-domain   │      │  │  │
│  │ │   │    listener         │       │    │  │    listener        │      │  │  │
│  │ │   └─────────────────────┘       │    │  └────────────────────┘      │  │  │
│  │ │                                 │    │                              │  │  │
│  │ │  ┌───────┐    ┌─────────┐       │    │  ┌───────┐   ┌────────┐      │  │  │
│  │ │  │Cluster│    │ Routes  │       │    │  │Cluster│   │ Routes │      │  │  │
│  │ │  │       │    │         │       │    │  │       │   │        │      │  │  │
│  │ │  └───────┘    └─────────┘       │    │  └───────┘   └────────┘      │  │  │
│  │ │                                 │    │                              │  │  │
│  │ │                                 │    │                              │  │  │
│  │ ├─────────────────────────────────┤    ├──────────────────────────────┤  │  │
│  └─┴─────────────────────────────────┴────┴──────────────────────────────┴──┘  │
│                                                                                │
└────────────────────────────────────────────────────────────────────────────────┘
```
### Current behavior
Versions:
* GRPC 1.39.0
* XDS 0.1.28
```java
new NodeGroup<>() {
  @Override
  public String hash(Node node) {
    return hashByNodeCluster(node.getCluster());
  }

  @Override
  public String hash(io.envoyproxy.envoy.config.core.v3.Node node) {
    return hashByNodeCluster(node.getCluster());
  }

  private String hashByNodeCluster(String cluster) {
    if (cluster.equalsIgnoreCase(PRIORITY_GROUP)) {
      log.info("Routing [{}] to {} group.", cluster, PRIORITY_GROUP);
      return PRIORITY_GROUP;
    }
    log.info("Routing [{}] to {} group.", cluster, NORMAL_GROUP);
    return NORMAL_GROUP;
  }
}
```
### Java client (Works as expected)
```
[grpc-default-executor-0] INFO org.example.xds.routing.XdsServer - Routing [priority] to priority group.
[grpc-default-executor-1] INFO org.example.xds.routing.XdsServer - Routing [priority] to priority group.
[grpc-default-executor-1] INFO org.example.xds.routing.XdsServer - Routing [priority] to priority group.
[grpc-default-executor-0] INFO org.example.xds.routing.XdsServer - Routing [priority] to priority group.
[grpc-default-executor-1] INFO org.example.xds.routing.XdsServer - Routing [priority] to priority group.
[grpc-default-executor-2] INFO org.example.xds.routing.XdsServer - Routing [priority] to priority group.
[grpc-default-executor-1] INFO org.example.xds.routing.XdsServer - Routing [priority] to priority group.
[grpc-default-executor-2] INFO org.example.xds.routing.XdsServer - Routing [priority] to priority group.
```
### C++ client (Missing cluster identification)
```
[grpc-default-executor-0] INFO org.example.xds.routing.XdsServer - Routing [priority] to priority group.
[grpc-default-executor-0] INFO org.example.xds.routing.XdsServer - Routing [] to normal group.
[grpc-default-executor-0] INFO org.example.xds.routing.XdsServer - Routing [] to normal group.
```
Looks like C++ implementation sends client cluster identification just in first request.

## How to test
* build server via `./gradlew build`
* run server via `./gradlew run`
* call endpoint from grpc-cli
  * setup client config (see client configuration bellow)
  * `GRPC_XDS_BOOTSTRAP=<PATH TO CLIENT CONFIG>`
  * `grpc_cli call xds:///my-test-domain org.example.HelloService.Hello "from: 'cli'"`

## Client configuration
### For normal cluster (WORKS)
```json
{
  "xds_servers": [
    {
      "server_uri": "localhost:8000",
      "channel_creds": [
        {
          "type": "insecure"
        }
      ]
    }
  ],
  "node": {
    "cluster": "normal"
  }
}
```
### Priority cluster (FAIL)
```json
{
  "xds_servers": [
    {
      "server_uri": "localhost:8000",
      "channel_creds": [
        {
          "type": "insecure"
        }
      ]
    }
  ],
  "node": {
    "cluster": "priority"
  }
}
```