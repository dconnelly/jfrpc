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

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Empty;
import io.dconnelly.jfrpc.FlightRecorderGrpc.FlightRecorderBlockingStub;
import io.dconnelly.jfrpc.FlightRecorderProto.Configuration;
import io.dconnelly.jfrpc.FlightRecorderProto.CreateRecordingRequest;
import io.dconnelly.jfrpc.FlightRecorderProto.DumpRecordingRequest;
import io.dconnelly.jfrpc.FlightRecorderProto.EventType;
import io.dconnelly.jfrpc.FlightRecorderProto.Recording;
import io.dconnelly.jfrpc.FlightRecorderProto.RecordingBytes;
import io.dconnelly.jfrpc.FlightRecorderProto.RecordingId;
import io.dconnelly.jfrpc.FlightRecorderProto.ScheduleRecordingRequest;
import io.dconnelly.jfrpc.FlightRecorderProto.StreamRecordingBytesRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Flow;
import javax.annotation.Nullable;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;

/**
 * Simple Java Flight Recorder gRPC client that uses a blocking stub.
 */
public class FlightRecorderClient {
  private final FlightRecorderBlockingStub stub;

  public static final String DEFAULT_CONFIGURATION = "default";

  /**
   * Creates a new FlightRecordingClient for the specified blocking stub.
   * @param stub the blocking stub
   * @return the FlightRecorderClient
   */
  public static FlightRecorderClient create(FlightRecorderBlockingStub stub) {
    return new FlightRecorderClient(stub);
  }

  private FlightRecorderClient(FlightRecorderBlockingStub stub) {
    this.stub = stub;
  }

  /**
   * Creates a new recording. The recording will be in the NEW state.
   * @param request the recording request options
   * @return the new recording
   * @throws io.grpc.StatusRuntimeException if request options were invalid
   * @see jdk.jfr.Recording
   */
  public Recording createRecording(CreateRecordingRequest request) {
    return stub.createRecording(request);
  }

  /**
   * Creates a new recording with default settings. Recording will have disk buffering enabled
   * and will also have settings obtained from the pre-defined configuration named "default".
   * @return the new recording with default settings
   * @see jdk.jfr.Recording
   */
  public Recording createRecording() {
    return createRecording(
        CreateRecordingRequest.newBuilder()
            .setConfigurationName(DEFAULT_CONFIGURATION)
            .setDisk(true)
            .build());
  }

  /**
   * Starts a recording. The recording must be in the NEW state, and will then becoming RUNNING.
   * @param id the recording id
   * @return the updated recording
   * @throws io.grpc.StatusRuntimeException if recording not found or not in NEW state
   * @see jdk.jfr.Recording#start()
   */
  public Recording startRecording(long id) {
    return stub.startRecording(recordingId(id));
  }

  /**
   * Schedules a recording to be started after a specified delay. The recording must be
   * in the NEW state, and will then become DELAYED.
   * @param id the recording id
   * @param delay the recording delay
   * @return the updated recording
   * @throws io.grpc.StatusRuntimeException if recording not found or not in NEW state
   * @see jdk.jfr.Recording#scheduleStart(Duration)
   */
  public Recording scheduleRecording(long id, Duration delay) {
    return stub.scheduleRecording(
        ScheduleRecordingRequest.newBuilder().setId(id).setDelay(Protobuf.toProto(delay)).build());
  }

  /**
   * Stops a recording. The recording must be in the RUNNING state, and will then become STOPPED.
   * @param id the recording id
   * @return the updated recording
   * @throws io.grpc.StatusRuntimeException if recording not found or not in RUNNING state
   * @see jdk.jfr.Recording#stop()
   */
  public Recording stopRecording(long id) {
    return stub.stopRecording(recordingId(id));
  }

  /**
   * Closes a recording. The recording will then become CLOSED.
   * @param id the recording id
   * @return the updated recording
   * @throws io.grpc.StatusRuntimeException if recording not found or already closed
   * @see jdk.jfr.Recording#close()
   */
  public Recording closeRecording(long id) {
    return stub.closeRecording(recordingId(id));
  }

  /**
   * Returns information about a recording.
   * @param id the recording id
   * @return the recording information
   * @throws io.grpc.StatusRuntimeException if recording not found
   * @see FlightRecorder#getRecordings()
   */
  public Recording getRecording(long id) {
    return stub.getRecording(recordingId(id));
  }

  /**
   * Returns an unmodifiable list of the currently active recordings.
   * @return the list of active recordings
   * @see FlightRecorder#getRecordings()
   */
  public List<Recording> getRecordings() {
    return ImmutableList.copyOf(stub.getRecordings(Empty.getDefaultInstance()).getRecordingsList());
  }

  /**
   * Takes a recording snapshot.
   * @return the recording snapshot
   * @see FlightRecorder#takeSnapshot()
   */
  public Recording takeSnapshot() {
    return stub.takeSnapshot(Empty.getDefaultInstance());
  }

  /**
   * Dumps recording data to a file.
   * @param id the recording id
   * @param destination the destination file path on the server
   * @throws io.grpc.StatusRuntimeException if recording not found or not in STOPPED state
   * @see jdk.jfr.Recording#dump(Path)
   */
  public void dumpRecording(long id, String destination) {
    var empty = stub.dumpRecording(
        DumpRecordingRequest.newBuilder().setId(id).setDestination(destination).build());
    assert empty != null;
  }

  /**
   * Returns a publisher that can be used to stream recording data for the specified recording id.
   * The recording must be in the STOPPED state.
   * @param id the recording id
   * @param startTime the start time for the stream, otherwise uses time when recording was started
   * @param endTime the stop time for the stream, otherwise uses time when recording was stopped
   * @return a flow publisher for the recording bytes
   * @throws io.grpc.StatusRuntimeException if recording not found or not in STOPPED state
   * @see jdk.jfr.Recording#getStream(Instant, Instant)
   */
  public Flow.Publisher<RecordingBytes> streamRecordingBytes(
      long id, @Nullable Instant startTime, @Nullable Instant endTime) {
    var request = StreamRecordingBytesRequest.newBuilder().setId(id);
    Optional.ofNullable(startTime)
        .ifPresent(t -> request.setStartTime(Protobuf.toTimestamp(t)));
    Optional.ofNullable(endTime)
        .ifPresent(t -> request.setEndTime(Protobuf.toTimestamp(t)));
    return ClientCalls.serverStreamingCall(
        () -> stub.getChannel().newCall(
            FlightRecorderGrpc.getStreamRecordingBytesMethod(), stub.getCallOptions()),
        request.build());
  }

  /**
   * Returns a publisher that can be used to monitor any recording state changes.
   * The subscriber's {@link java.util.concurrent.Flow.Subscriber#onNext} method will be called
   * for each recording state change.
   * @return the flow publisher for recording changes
   * @see FlightRecorderListener
   */
  public Flow.Publisher<Recording> streamRecordingChanges() {
    return ClientCalls.serverStreamingCall(
        () -> stub.getChannel().newCall(
            FlightRecorderGrpc.getStreamRecordingChangesMethod(), stub.getCallOptions()),
        Empty.getDefaultInstance());
  }

  /**
   * Returns an unmodifiable list of all available pre-defined configurations.
   * @return the list of configurations
   * @see jdk.jfr.Configuration#getConfigurations()
   */
  public List<Configuration> getConfigurations() {
    return ImmutableList.copyOf(
        stub.getConfigurations(Empty.getDefaultInstance()).getConfigurationsList());
  }

  /**
   * Returns an unmodifiable list of all the available event types.
   * @return the list of event types
   * @see FlightRecorder#getEventTypes()
   */
  public List<EventType> getEventTypes() {
    return ImmutableList.copyOf(stub.getEventTypes(Empty.getDefaultInstance()).getEventTypesList());
  }

  private RecordingId recordingId(long id) {
    return RecordingId.newBuilder().setId(id).build();
  }
}
