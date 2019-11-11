/*
 * Copyright © 2019 David Connelly (dconnelly@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.dconnelly.jfrpc;

import io.grpc.BindableService;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.util.MutableHandlerRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

class GrpcServerExtension implements BeforeAllCallback, AfterAllCallback {
  private ManagedChannel channel;
  private Server server;
  private String serverName;
  private MutableHandlerRegistry handlerRegistry;

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  public MutableHandlerRegistry handlerRegistry() {
    if (handlerRegistry == null) {
      handlerRegistry = new MutableHandlerRegistry();
    }
    return handlerRegistry;
  }

  /**
   * Adds a new service to the server handler registry.
   * @param service the service to be added
   */
  public GrpcServerExtension addService(BindableService service) {
    handlerRegistry().addService(service);
    return this;
  }

  /**
   * Returns the client channel for sending requests.
   */
  public Channel channel() {
    return channel;
  }

  @Override
  public void beforeAll(ExtensionContext extensionContext) throws Exception {
    serverName = UUID.randomUUID().toString();
    server = InProcessServerBuilder.forName(serverName)
        .fallbackHandlerRegistry(handlerRegistry()).build().start();
    channel = InProcessChannelBuilder.forName(serverName).build();
  }

  @Override
  public void afterAll(ExtensionContext extensionContext) {
    serverName = null;
    handlerRegistry = null;
    channel.shutdown();
    server.shutdown();
    try {
      channel.awaitTermination(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
      server.awaitTermination(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted!", e);
    } finally {
      channel.shutdownNow();
      server.shutdownNow();
      channel = null;
      server = null;
    }
  }
}
