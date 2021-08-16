package org.example.xds.routing;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.ClientCalls;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.example.grpc.service.HelloRequest;
import org.example.grpc.service.HelloResponse;
import org.example.grpc.service.HelloServiceGrpc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


@Slf4j
class XdsServerTest {

  private static Server xdsServer;

  private static Closeable generateClientConfig(String cluster)
      throws IOException {
    final String config =
        """
        {
          "xds_servers": [
            {
              "server_uri": "%s:%d",
              "channel_creds": [
                {
                  "type": "insecure"
                }
              ]
            }
          ],
          "node": {
            "cluster": "%s"
          }
        }
        """;
    // Create XDS config and setup environment.
    final Path xdsPath = Files.createTempFile("xds-", ".json");
    Files.writeString(
        xdsPath, String.format(config, XdsServer.LOCALHOST, XdsServer.XDS_PORT, cluster));
    System.setProperty("io.grpc.xds.bootstrap", xdsPath.toString());
    return () -> {
      System.clearProperty("io.grpc.xds.bootstrap");
      Files.delete(xdsPath);
    };
  }

  @BeforeAll
  public static void setup() throws IOException {
    xdsServer = XdsServer.createAndStartXdsServer();
  }

  @AfterAll
  public static void tearDown() {
    xdsServer.shutdown();
  }


  @Test
  void callEndpointAsPriority() throws IOException {
    try(final Closeable ignored = generateClientConfig(XdsServer.PRIORITY_GROUP)) {

      final ManagedChannel channel =
          NettyChannelBuilder.forTarget("xds:///" + XdsServer.XDS_DOMAIN).usePlaintext().build();
      HelloResponse response = ClientCalls.blockingUnaryCall(
          channel.newCall(HelloServiceGrpc.getHelloMethod(), CallOptions.DEFAULT), HelloRequest.newBuilder().setFrom("java").build());

      log.info("Api response: {}", response);
      channel.shutdown();

    }
  }
}