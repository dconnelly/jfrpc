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

import static io.dconnelly.jfrpc.Converters.parseDuration;
import static io.dconnelly.jfrpc.Converters.parseSize;

import com.google.common.base.Strings;
import com.google.common.net.HostAndPort;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Command(
    name = "jfrpc",
    mixinStandardHelpOptions = true,
    version = "1.0",
    description = "Java Flight Recorder RPC Client CLI"
)
public class FlightRecorderCommand implements Runnable {
  @Spec
  private CommandSpec spec;

  @Option(names = {"-a", "--address"}, description = "Server address", defaultValue = "localhost")
  private String address;

  private static final JsonFormat.Printer jsonPrinter = JsonFormat.printer();

  private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

  @Override
  public void run() {
    throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand");
  }

  @Command(description = "Create new recording")
  void create(
      @Option(names = {"-n", "--name"}, description = "Recording name")
          String name,
      @Option(names = "--max-size", description = "Maximum recording size in bytes")
          String maxSize,
      @Option(names = "--max-age", description = "Maximum age of recording data")
          String maxAge,
      @Option(names = "--destination", description = "Server file location to save recording data")
          String destination,
      @Option(names = "--dump-on-exit", description = "Dump recording data on exit")
          boolean dumpOnExit,
      @Option(names = "--duration", description = "Duration of recording")
          String duration,
      @Option(names = {"-c", "--config"},
          description = "Name of pre-defined configuration to use")
          String config) {
    var request = FlightRecorderProto.CreateRecordingRequest.newBuilder()
        .setName(Strings.nullToEmpty(name))
        .setMaxSize(parseSize(maxSize))
        .setDestination(Strings.nullToEmpty(destination))
        .setDumpOnExit(dumpOnExit)
        .setDisk(true);
    if (maxAge != null) {
      request.setMaxAge(toProto(parseDuration(maxAge)));
    }
    if (duration != null) {
      request.setDuration(toProto(parseDuration(duration)));
    }
    if (config != null) {
      request.setConfigurationName(config);
    }
    withClient(client -> {
      var recording = client.createRecording(request.build());
      out().println(toString(recording.toBuilder().clearSettings()));
    });
  }

  @Command(description = "Start recording")
  void start(
      @Parameters(paramLabel = "<id>", description = "Recording id") long id,
      @Option(names = {"-d", "--delay"}, description = "Schedule start after delay") String delay) {
    withClient(client -> {
      var recording = delay != null
          ? client.scheduleRecording(id, parseDuration(delay))
          : client.startRecording(id);
      out().println(toString(recording.toBuilder().clearSettings()));
    });
  }

  @Command(description = "Stop recording")
  void stop(@Parameters(paramLabel = "<id>", description = "Recording id") long id) {
    withClient(client -> {
      var recording = client.stopRecording(id);
      out().println(toString(recording.toBuilder().clearSettings()));
    });
  }

  @Command(description = "Close recording")
  void close(@Parameters(description = "Recording id") long id) {
    withClient(client -> {
      var recording = client.closeRecording(id);
      out().println(toString(recording.toBuilder().clearSettings()));
    });
  }

  @Command(description = "Get recording info")
  void get(@Parameters(description = "Recording id") long id) {
    withClient(client -> {
      var recording = client.getRecording(id);
      out().println(toString(recording));
    });
  }

  // TODO Method must be public. See https://github.com/remkop/picocli/issues/905.
  @Command(description = "Take recording snapshot")
  public void snapshot() {
    withClient(client -> {
      var recording = client.takeSnapshot();
      out().println(toString(recording.toBuilder().clearSettings()));
    });
  }

  @Command(name = "data", description = "Get recording data")
  void data(
      @Parameters(description = "Recording id") long id,
      @Parameters(description = "Output file name") Path file,
      @Option(names = "--start", description = "Stream start time") Instant startTime,
      @Option(names = "--end", description = "Stream end time") Instant endTime)
      throws IOException {
    try (var out = Files.newOutputStream(file)) {
      getData(id, out, startTime, endTime);
    }
  }

  private void getData(long id, OutputStream out, Instant startTime, Instant endTime) {
    withClient(client -> {
      var publisher = client.streamRecordingBytes(id, startTime, endTime);
      var future = Flows.consume(publisher, bytes -> {
        try {
          bytes.getBytes().writeTo(out);
        } catch (IOException e) {
          throw new RuntimeException("Write error", e);
        }
      });
      try {
        future.get();
      } catch (InterruptedException e) {
        future.cancel(false);
        throw new IllegalStateException("Interrupted!", e);
      } catch (ExecutionException e) {
        throw new IllegalStateException("Error getting recording data", e.getCause());
      }
    });
  }

  // TODO Method must be public. See https://github.com/remkop/picocli/issues/905.
  @Command(description = "List available event types")
  public void events() {
    withClient(client -> {
      var events = client.getEventTypes();
      out().println(toString(events));
    });
  }

  // TODO Method must be public. See https://github.com/remkop/picocli/issues/905.
  @Command(description = "List active recordings")
  public void recordings() {
    withClient(client -> {
      var recordings = client.getRecordings().stream()
          // Exclude settings from summary list
          .map(r -> r.toBuilder().clearSettings())
          .collect(Collectors.toList());
      out().println(toString(recordings));
    });
  }

  // TODO Method must be public. See https://github.com/remkop/picocli/issues/905.
  @Command(description = "List available pre-defined configurations")
  public void configs() {
    withClient(client -> {
      var configs = client.getConfigurations();
      out().println(toString(configs));
    });
  }

  private PrintWriter out() {
    return spec.commandLine().getOut();
  }

  private static String toString(List<? extends MessageOrBuilder> messages) {
    var array = messages.stream()
        .map(m -> JsonParser.parseString(toString(m)))
        .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
    return gson.toJson(array);
  }

  private static String toString(MessageOrBuilder message) {
    try {
      return jsonPrinter.print(message);
    } catch (InvalidProtocolBufferException e) {
      throw new IllegalStateException("JSON encoding error", e);
    }
  }

  private static com.google.protobuf.Duration toProto(Duration duration) {
    return com.google.protobuf.Duration.newBuilder()
        .setSeconds(duration.getSeconds())
        .setNanos(duration.getNano())
        .build();
  }

  private void withClient(Consumer<FlightRecorderClient> handler) {
    var channel = channel();
    try {
      var client = FlightRecorderClient.create(FlightRecorderGrpc.newBlockingStub(channel));
      handler.accept(client);
    } finally {
      channel.shutdownNow();
    }
  }

  @SuppressWarnings("UnstableApiUsage")
  private ManagedChannel channel() {
    var addr = HostAndPort.fromString(address).withDefaultPort(9090);
    return ManagedChannelBuilder.forAddress(addr.getHost(), addr.getPort())
        .directExecutor()
        .usePlaintext()
        .build();
  }

  public static void main(String... args) {
    var exitCode = new CommandLine(new FlightRecorderCommand()).execute(args);
    System.exit(exitCode);
  }

}
