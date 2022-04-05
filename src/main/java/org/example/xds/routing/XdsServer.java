package org.example.xds.routing;

import com.google.common.base.Preconditions;
import com.google.protobuf.Any;
import com.google.protobuf.UInt32Value;
import io.envoyproxy.controlplane.cache.NodeGroup;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V2DiscoveryServer;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.api.v2.core.Node;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthStatus;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.ApiListener;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.ProtoReflectionService;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class XdsServer {
  public static final String XDS_DOMAIN = "my-test-domain";
  public static final String PRIORITY_GROUP = "priority";
  public static final String NORMAL_GROUP = "normal";
  public static final int XDS_PORT = 8000;
  public static final String LOCALHOST = "127.0.0.1";

  private static final Context.Key<String> GRPC_CLIENT_SOCKET_KEY = Context.key("client_socket");
  private static final Map<String, String> CLIENTS_CLUSTER_CACHE = new ConcurrentHashMap<>();

  private static final int PRIORITY_SERVER_PORT = 12000;
  private static final int NORMAL_SERVER_PORT = 14000;
  private static final ConfigSource.Builder AGG_CONFIG_SOURCE =
      ConfigSource.newBuilder().setAds(AggregatedConfigSource.getDefaultInstance());

  public static void main(String... args) throws IOException, InterruptedException {
    createAndStartXdsServer().awaitTermination();
  }

  static Server createAndStartXdsServer() throws IOException {
    final SimpleCache<String> cache =
        new SimpleCache<>(
            new NodeGroup<>() {
              @Override
              public String hash(Node node) {
                return hashByNodeCluster(node.getCluster());
              }

              @Override
              public String hash(io.envoyproxy.envoy.config.core.v3.Node node) {
                return hashByNodeCluster(node.getCluster());
              }

              private String hashByNodeCluster(final String cluster) {
                final String currentClient = GRPC_CLIENT_SOCKET_KEY.get();
                String currentCLuster = cluster;
                if (!cluster.isEmpty()) {
                  CLIENTS_CLUSTER_CACHE.computeIfAbsent(currentClient, name -> cluster);
                }
                if (CLIENTS_CLUSTER_CACHE.containsKey(currentClient) && cluster.isEmpty()) {
                  currentCLuster = CLIENTS_CLUSTER_CACHE.get(currentClient);
                  log.info(
                      "Cluster [{}] information restored from cache for client {}.",
                      currentCLuster,
                      currentClient);
                }
                if (currentCLuster.equalsIgnoreCase(PRIORITY_GROUP)) {
                  log.info("Routing [{}] to {} group.", currentCLuster, PRIORITY_GROUP);
                  return PRIORITY_GROUP;
                }
                log.info("Routing [{}] to {} group.", currentCLuster, NORMAL_GROUP);
                return NORMAL_GROUP;
              }
            });

    cache.setSnapshot(
        PRIORITY_GROUP,
        Snapshot.create(
            Collections.singletonList(createCluster(PRIORITY_GROUP)),
            Collections.singletonList(
                createClusterLoadAssignment(PRIORITY_GROUP, PRIORITY_SERVER_PORT)),
            Collections.singletonList(createListener(PRIORITY_GROUP + "_route")),
            Collections.singletonList(
                createRouteConfiguration(PRIORITY_GROUP + "_route", PRIORITY_GROUP)),
            Collections.emptyList(),
            "version1"));

    cache.setSnapshot(
        NORMAL_GROUP,
        Snapshot.create(
            Collections.singletonList(createCluster(NORMAL_GROUP)),
            Collections.singletonList(
                createClusterLoadAssignment(NORMAL_GROUP, NORMAL_SERVER_PORT)),
            Collections.singletonList(createListener(NORMAL_GROUP + "_route")),
            Collections.singletonList(
                createRouteConfiguration(NORMAL_GROUP + "_route", NORMAL_GROUP)),
            Collections.emptyList(),
            "version2"));

    V2DiscoveryServer discoveryServerV2 = new V2DiscoveryServer(cache);
    V3DiscoveryServer discoveryServerV3 = new V3DiscoveryServer(cache);

    Server server =
        NettyServerBuilder.forPort(XDS_PORT)
            .intercept(
                new ServerInterceptor() {
                  @Override
                  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                      ServerCall<ReqT, RespT> call,
                      Metadata headers,
                      ServerCallHandler<ReqT, RespT> next) {
                    java.net.SocketAddress remoteAddress =
                        call.getAttributes().get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
                    Preconditions.checkNotNull(
                        remoteAddress, "Unable to obtain client remote address.");
                    final Context currentContext =
                        Context.current()
                            .withValue(GRPC_CLIENT_SOCKET_KEY, remoteAddress.toString());
                    final ServerCall.Listener<ReqT> listener =
                        Contexts.interceptCall(currentContext, call, headers, next);
                    return new ForwardingServerCallListener.SimpleForwardingServerCallListener<>(
                        listener) {

                      @Override
                      public void onCancel() {
                        log.info("onCancel");
                        clearCachedCluster();
                        super.onCancel();
                      }

                      private void clearCachedCluster() {
                        log.info("Clearing cluster cache for client {}.", remoteAddress);
                        CLIENTS_CLUSTER_CACHE.remove(remoteAddress.toString());
                      }
                    };
                  }
                })
            .intercept(
                new ServerInterceptor() {
                  @Override
                  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                      ServerCall<ReqT, RespT> call,
                      Metadata headers,
                      ServerCallHandler<ReqT, RespT> next) {
                    return next.startCall(
                        new SimpleForwardingServerCall<>(call) {

                          @Override
                          public void close(Status status, Metadata trailers) {
                            super.close(status, trailers);
                            if (!status.isOk() && !status.getCode().equals(Code.CANCELLED)) {
                              log.error(
                                  "Error during GRPC call. Code: {} Description: {}.",
                                  status.getCode(),
                                  status.getDescription(),
                                  status.asException(trailers));
                            }
                          }
                        },
                        headers);
                  }
                })
            .addService(discoveryServerV2.getAggregatedDiscoveryServiceImpl())
            .addService(discoveryServerV3.getAggregatedDiscoveryServiceImpl())
            .addService(discoveryServerV2.getClusterDiscoveryServiceImpl())
            .addService(discoveryServerV3.getClusterDiscoveryServiceImpl())
            .addService(discoveryServerV2.getListenerDiscoveryServiceImpl())
            .addService(discoveryServerV3.getListenerDiscoveryServiceImpl())
            .addService(discoveryServerV2.getRouteDiscoveryServiceImpl())
            .addService(discoveryServerV3.getRouteDiscoveryServiceImpl())
            .addService(ProtoReflectionService.newInstance())
            .build();
    server.start();
    log.info("XDS server running on port {}", XDS_PORT);
    final Server priorityServer =
        createServer(new InetSocketAddress(LOCALHOST, PRIORITY_SERVER_PORT), PRIORITY_GROUP);
    final Server normalServer =
        createServer(new InetSocketAddress(LOCALHOST, NORMAL_SERVER_PORT), NORMAL_GROUP);
    priorityServer.start();
    normalServer.start();
    log.info("Servers running.");
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  log.info("Stopping servers.");
                  priorityServer.shutdown();
                  normalServer.shutdown();
                  server.shutdown();
                }));
    return server;
  }

  private static Server createServer(InetSocketAddress address, String name) {
    return NettyServerBuilder.forAddress(address)
        .addService(new MyHelloService(name))
        .addService(ProtoReflectionService.newInstance())
        .build();
  }

  private static Cluster createCluster(String name) {
    final EdsClusterConfig edsClusterConfig =
        EdsClusterConfig.newBuilder().setEdsConfig(AGG_CONFIG_SOURCE).build();
    return Cluster.newBuilder()
        .setName(name)
        .setType(DiscoveryType.EDS)
        .setEdsClusterConfig(edsClusterConfig)
        .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
        .build();
  }

  private static ClusterLoadAssignment createClusterLoadAssignment(String clusterName, int port) {
    return ClusterLoadAssignment.newBuilder()
        .setClusterName(clusterName)
        .addEndpoints(
            LocalityLbEndpoints.newBuilder()
                .setLoadBalancingWeight(UInt32Value.newBuilder().setValue(10))
                .setLocality(Locality.newBuilder().setZone("zone").build())
                .addLbEndpoints(
                    LbEndpoint.newBuilder()
                        .setHealthStatus(HealthStatus.HEALTHY)
                        .setEndpoint(
                            Endpoint.newBuilder()
                                .setAddress(
                                    Address.newBuilder()
                                        .setSocketAddress(
                                            SocketAddress.newBuilder()
                                                .setAddress(LOCALHOST)
                                                .setPortValue(port))))))
        .build();
  }

  private static Listener createListener(String routeName) {
    return Listener.newBuilder()
        .setName(XDS_DOMAIN)
        .addFilterChains(
            FilterChain.newBuilder()
                .addFilters(
                    Filter.newBuilder()
                        .setName("envoy.filters.network.http_connection_manager")
                        .setTypedConfig(
                            Any.pack(
                                HttpConnectionManager.newBuilder()
                                    .addHttpFilters(
                                        HttpFilter.newBuilder()
                                            .setName("envoy.filters.http.router")
                                            .setTypedConfig(Any.pack(Router.getDefaultInstance())))
                                    .build()))))
        .setApiListener(
            ApiListener.newBuilder()
                .setApiListener(
                    Any.pack(
                        HttpConnectionManager.newBuilder()
                            .addHttpFilters(
                                HttpFilter.newBuilder()
                                    .setName("envoy.filters.http.router")
                                    .setTypedConfig(Any.pack(Router.getDefaultInstance())))
                            .setRds(
                                Rds.newBuilder()
                                    .setConfigSource(AGG_CONFIG_SOURCE)
                                    .setRouteConfigName(routeName))
                            .build())))
        .build();
  }

  private static RouteConfiguration createRouteConfiguration(
      String routeName, String targetCluster) {
    return RouteConfiguration.newBuilder()
        .setName(routeName)
        .addVirtualHosts(
            VirtualHost.newBuilder()
                .addDomains("*")
                .setName("virtualhost")
                .addRoutes(
                    Route.newBuilder()
                        .setName("default-route")
                        .setMatch(RouteMatch.newBuilder().setPrefix("/").build())
                        .setRoute(RouteAction.newBuilder().setCluster(targetCluster))))
        .build();
  }
}
