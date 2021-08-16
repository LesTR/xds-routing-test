package org.example.xds.routing;

import io.grpc.stub.StreamObserver;
import org.example.grpc.service.HelloRequest;
import org.example.grpc.service.HelloResponse;
import org.example.grpc.service.HelloServiceGrpc.HelloServiceImplBase;

public class MyHelloService extends HelloServiceImplBase {
  private final String name;

  public MyHelloService(String name) {
    this.name = name;
  }

  @Override
  public void hello(HelloRequest request, StreamObserver<HelloResponse> responseObserver) {
    responseObserver.onNext(
        HelloResponse.newBuilder()
            .setResponse(String.format("Hello %s from %s!", request.getFrom(), name))
            .build());
    responseObserver.onCompleted();
  }
}
