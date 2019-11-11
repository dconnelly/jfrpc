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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static io.dconnelly.jfrpc.FlightRecorderProto.CreateRecordingRequest;
import static io.dconnelly.jfrpc.FlightRecorderProto.Recording;
import static io.dconnelly.jfrpc.FlightRecorderProto.RecordingState;

import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import com.google.protobuf.util.Durations;
import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import jdk.jfr.FlightRecorder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class FlightRecorderTest {
  private static FlightRecorderClient client;
  private static final FlightRecorder flightRecorder = FlightRecorder.getFlightRecorder();

  private static final Duration TIMEOUT = Duration.ofSeconds(5);

  @RegisterExtension
  static final GrpcServerExtension server =
      new GrpcServerExtension().addService(new FlightRecorderService());

  @BeforeAll
  static void init() {
    client = FlightRecorderClient.create(FlightRecorderGrpc.newBlockingStub(server.channel()));
  }

  @Test
  void createRecording() {
    var request = CreateRecordingRequest.newBuilder()
        .setName("Test Recording")
        .setMaxSize(100 * 1024 * 1024)
        .setMaxAge(Durations.fromHours(24))
        .setDuration(Durations.fromMinutes(5))
        .setDumpOnExit(true)
        .setDisk(true)
        .setDestination("/tmp/test.jfr")
        .putSettings("jdk.ObjectCount#enabled", "true")
        .build();
    createRecording(request, recording -> {
      assertThat(recording.getName()).isEqualTo(request.getName());
      assertThat(recording.getMaxSize()).isEqualTo(request.getMaxSize());
      assertThat(recording.getDuration()).isEqualTo(request.getDuration());
      assertThat(recording.getMaxAge()).isEqualTo(request.getMaxAge());
      assertThat(recording.getState()).isEqualTo(RecordingState.STATE_NEW);
      assertThat(recording.getDumpOnExit()).isEqualTo(request.getDumpOnExit());
      assertThat(recording.getDisk()).isTrue();
      assertThat(recording.getDestination()).isEqualTo(request.getDestination());
      assertThat(recording.getSettingsMap()).isEqualTo(request.getSettingsMap());
      assertThat(recording.hasStartTime()).isFalse();
      assertThat(recording.hasStopTime()).isFalse();
      assertThat(client.getRecording(recording.getId())).isEqualTo(recording);
    });
  }

  private void createRecording(CreateRecordingRequest request, Consumer<Recording> handler) {
    var recording = client.createRecording(request);
    try {
      handler.accept(recording);
    } finally {
      closeQuietly(recording.getId());
    }
  }

  private void createRecording(Consumer<Recording> handler) {
    var recording = client.createRecording();
    try {
      handler.accept(recording);
    } finally {
      closeQuietly(recording.getId());
    }
  }

  @Test
  void createRecordingWithDefaults() {
    var request = CreateRecordingRequest.newBuilder()
        .setConfigurationName("default")
        .putSettings("custom.additional#enabled", "true")
        .build();
    createRecording(request, recording -> {
      assertThat(recording.getSettingsMap()).isEqualTo(jfr(recording.getId()).getSettings());
      assertThat(recording.getSettingsMap()).containsAtLeastEntriesIn(request.getSettingsMap());
    });
  }

  @Test
  void createRecordingInvalidDuration() {
    var request = CreateRecordingRequest.newBuilder().setDuration(Durations.fromDays(-1)).build();
    try {
      client.createRecording(request);
      Assertions.fail("Should not have been able to create recording with negative duration");
    } catch (Exception e) {
      assertThat(Status.fromThrowable(e).getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT);
    }
  }

  @Test
  void startAndStopRecording() {
    createRecording(recording -> {
      var started = client.startRecording(recording.getId());
      assertThat(started.getId()).isEqualTo(recording.getId());
      assertThat(started.getState()).isEqualTo(RecordingState.STATE_RUNNING);
      assertThat(started.hasStartTime()).isTrue();
      assertThat(Protobuf.toInstant(started.getStartTime()))
          .isEqualTo(jfr(started.getId()).getStartTime());
      assertThat(started.hasStopTime()).isFalse();
      sleep(Duration.ofMillis(500));
      var stopped = client.stopRecording(recording.getId());
      assertThat(stopped.getState()).isEqualTo(RecordingState.STATE_STOPPED);
      assertThat(stopped.getStartTime()).isEqualTo(started.getStartTime());
      assertThat(stopped.hasStopTime()).isTrue();
      assertThat(Protobuf.toInstant(stopped.getStopTime()))
          .isEqualTo(jfr(stopped.getId()).getStopTime());
      assertThat(Timestamps.compare(stopped.getStartTime(), stopped.getStopTime()))
          .isLessThan(0);
    });
  }

  @Test
  void scheduleRecording() {
    createRecording(recording -> {
      var scheduled = client.scheduleRecording(recording.getId(), Duration.ofHours(1));
      assertThat(scheduled.getState()).isEqualTo(RecordingState.STATE_DELAYED);
    });
  }

  @Test
  void streamRecordingBytes() {
    createRecording(recording -> {
      client.startRecording(recording.getId());
      sleep(Duration.ofMillis(500));
      var stopped = client.stopRecording(recording.getId());
      byte[] bytes = getRecordingBytes(recording.getId(), null, null);
      assertThat(bytes).isEqualTo(jfrBytes(recording.getId()));
      byte[] bytes2 = getRecordingBytes(
          recording.getId(),
          Protobuf.toInstant(stopped.getStartTime()),
          Protobuf.toInstant(stopped.getStopTime()));
      assertThat(bytes).isEqualTo(bytes2);
    });
  }

  private byte[] getRecordingBytes(long id, Instant startTime, Instant endTime) {
    var bytes = new ByteArrayOutputStream();
    var future = Flows.consume(client.streamRecordingBytes(id, startTime, endTime),
        response -> {
          try {
            response.getBytes().writeTo(bytes);
          } catch (IOException e) {
            throw new AssertionError();
          }
        });
    Futures.getUnchecked(future);
    return bytes.toByteArray();
  }

  @Test
  void takeSnapshot() {
    var recording = client.takeSnapshot();
    try {
      assertThat(recording.getState()).isEqualTo(RecordingState.STATE_STOPPED);
      assertThat(jfr(recording.getId()).getState()).isEqualTo(jdk.jfr.RecordingState.STOPPED);
    } finally {
      closeQuietly(recording.getId());
    }
  }

  @Test
  void dumpRecording() {
    createRecording(recording -> {
      try {
        var file = Files.createTempFile("test", ".jfr");
        try {
          client.startRecording(recording.getId());
          sleep(Duration.ofMillis(500));
          var stopped = client.stopRecording(recording.getId());
          client.dumpRecording(recording.getId(), file.toString());
          assertThat(Files.size(file)).isEqualTo(stopped.getSize());
        } finally {
          Files.deleteIfExists(file);
        }
      } catch (IOException e) {
        throw new AssertionError("I/O error", e);
      }
    });
  }

  @Test
  void getRecordings() {
    createRecording(one ->
        createRecording(two -> assertThat(client.getRecordings()).containsAtLeast(one, two)));
  }

  @Test
  void getConfigurations() {
    assertThat(client.getConfigurations())
        .comparingElementsUsing(TestHelper.configurationEquivalence())
        .containsExactlyElementsIn(jdk.jfr.Configuration.getConfigurations());
  }

  @Test
  void getEventTypes() {
    assertThat(client.getEventTypes())
        .comparingElementsUsing(TestHelper.eventTypeEquivalence())
        .containsExactlyElementsIn(flightRecorder.getEventTypes());
  }

  @Test
  void streamRecordingChanges() {
    var recordings = new LinkedBlockingQueue<Recording>();
    var future = Flows.consume(client.streamRecordingChanges(), recordings::add);
    try {
      createRecording(recording -> {
        var started = client.startRecording(recording.getId());
        assertThat(poll(recordings)).isEqualTo(started);
        var stopped = client.stopRecording(recording.getId());
        assertThat(poll(recordings)).isEqualTo(stopped);
        var closed = client.closeRecording(recording.getId());
        assertThat(poll(recordings)).isEqualTo(closed);
      });
    } finally {
      future.cancel(true);
    }
  }

  private void closeQuietly(long id) {
    try {
      var closed = client.closeRecording(id);
      assertThat(closed.getState()).isEqualTo(RecordingState.STATE_CLOSED);
    } catch (StatusRuntimeException e) {
      if (e.getStatus().getCode() != Status.Code.NOT_FOUND) {
        throw e;
      }
    }
  }

  private jdk.jfr.Recording jfr(long id) {
    return flightRecorder.getRecordings().stream()
        .filter(r -> r.getId() == id)
        .findAny()
        .orElseThrow(() -> new AssertionError("Recording not found: id = " + id));
  }

  private byte[] jfrBytes(long id) {
    try {
      var is = jfr(id).getStream(null, null);
      return is != null ? ByteStreams.toByteArray(is) : new byte[0];
    } catch (IOException e) {
      throw new AssertionError("Error reading byte stream", e);
    }
  }

  private static <E> E poll(BlockingQueue<E> queue) {
    try {
      var e = queue.poll(TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
      assertThat(e).isNotNull();
      return e;
    } catch (InterruptedException e) {
      throw new AssertionError("Interrupted!", e);
    }
  }

  private static void sleep(Duration duration) {
    try {
      TimeUnit.NANOSECONDS.sleep(duration.toNanos());
    } catch (InterruptedException e) {
      throw new AssertionError("Interrupted!", e);
    }
  }
}
