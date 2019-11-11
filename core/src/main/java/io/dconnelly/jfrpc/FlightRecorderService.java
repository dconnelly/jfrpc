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

import static io.dconnelly.jfrpc.Grpc.checkArgument;
import static io.dconnelly.jfrpc.Grpc.failedPrecondition;
import static io.dconnelly.jfrpc.Grpc.isServerError;

import com.google.common.base.Strings;
import com.google.protobuf.ByteString;
import com.google.protobuf.Empty;
import com.google.protobuf.util.Durations;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jdk.jfr.Configuration;
import jdk.jfr.EventType;
import jdk.jfr.FlightRecorder;
import jdk.jfr.FlightRecorderListener;
import jdk.jfr.Recording;
import jdk.jfr.RecordingState;
import jdk.jfr.SettingDescriptor;

/**
 * Service providing a gRPC interface to the Java Flight Recorder API.
 * @see FlightRecorder
 */
public class FlightRecorderService extends FlightRecorderGrpc.FlightRecorderImplBase {
  private final FlightRecorder flightRecorder;

  private static final Logger logger = Logger.getLogger(FlightRecorderService.class.getName());

  // Data chunk size for streaming
  private static final int DATA_CHUNK_SIZE = 64 * 1024;

  /**
   * Creates a new FlightRecorderService.
   * @throws IllegalStateException if flight recorder is not available on this platform
   */
  public FlightRecorderService() {
    flightRecorder = FlightRecorder.getFlightRecorder();
  }

  @Override
  public void createRecording(
      FlightRecorderProto.CreateRecordingRequest request,
      StreamObserver<FlightRecorderProto.Recording> responseObserver) {
    try {
      var name = request.getConfigurationName();
      var recording = name.isEmpty() ? new Recording() : new Recording(getConfiguration(name));
      if (!request.getName().isEmpty()) {
        recording.setName(request.getName());
      }
      checkArgument(request.getMaxSize() >= 0, "Negative max size");
      recording.setMaxSize(request.getMaxSize());
      if (request.hasMaxAge()) {
        checkArgument(!Durations.isNegative(request.getMaxAge()), "Negative max age");
        recording.setMaxAge(Protobuf.fromProto(request.getMaxAge()));
      }
      if (request.hasDuration()) {
        checkArgument(!Durations.isNegative(request.getDuration()), "Negative duration");
        recording.setDuration(Protobuf.fromProto(request.getDuration()));
      }
      recording.setDumpOnExit(request.getDumpOnExit());
      recording.setToDisk(request.getDisk());
      if (!request.getDestination().isEmpty()) {
        try {
          recording.setDestination(Paths.get(request.getDestination()));
        } catch (IOException e) {
          throw failedPrecondition("Destination path not writeable", e);
        }
      }
      if (!request.getSettingsMap().isEmpty()) {
        var settings = recording.getSettings();
        settings.putAll(request.getSettingsMap());
        recording.setSettings(settings);
      }
      responseObserver.onNext(toProto(recording));
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  private static Configuration getConfiguration(String name) {
    try {
      return Configuration.getConfiguration(name);
    } catch (Exception e) {
      throw failedPrecondition("Unable to load configuration '" + name + "'", e);
    }
  }

  @Override
  public void startRecording(
      FlightRecorderProto.RecordingId request,
      StreamObserver<FlightRecorderProto.Recording> responseObserver) {
    try {
      var recording = findRecording(request.getId());
      try {
        recording.start();
      } catch (IllegalStateException e) {
        throw failedPrecondition(e.getMessage(), e);
      }
      responseObserver.onNext(toProto(recording));
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  @Override
  public void scheduleRecording(
      FlightRecorderProto.ScheduleRecordingRequest request,
      StreamObserver<FlightRecorderProto.Recording> responseObserver) {
    try {
      var recording = findRecording(request.getId());
      try {
        checkArgument(!Durations.isNegative(request.getDelay()), "Negative delay");
        recording.scheduleStart(Protobuf.fromProto(request.getDelay()));
      } catch (IllegalStateException e) {
        throw failedPrecondition(e.getMessage(), e);
      }
      responseObserver.onNext(toProto(recording));
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  @Override
  public void stopRecording(
      FlightRecorderProto.RecordingId request,
      StreamObserver<FlightRecorderProto.Recording> responseObserver) {
    try {
      var recording = findRecording(request.getId());
      try {
        recording.stop();
      } catch (IllegalStateException e) {
        throw failedPrecondition(e.getMessage(), e);
      }
      responseObserver.onNext(toProto(recording));
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  @Override
  public void closeRecording(
      FlightRecorderProto.RecordingId request,
      StreamObserver<FlightRecorderProto.Recording> responseObserver) {
    try {
      var recording = findRecording(request.getId());
      recording.close();
      responseObserver.onNext(toProto(recording));
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  @Override
  public void getRecording(
      FlightRecorderProto.RecordingId request,
      StreamObserver<FlightRecorderProto.Recording> responseObserver) {
    try {
      responseObserver.onNext(toProto(findRecording(request.getId())));
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  @Override
  public void getRecordings(
      Empty request, StreamObserver<FlightRecorderProto.Recordings> responseObserver) {
    try {
      responseObserver.onNext(
          FlightRecorderProto.Recordings.newBuilder()
              .addAllRecordings(
                  flightRecorder.getRecordings().stream()
                      .map(FlightRecorderService::toProto)
                      .collect(Collectors.toList()))
              .build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      logError(e);
      responseObserver.onError(e);
    }
  }

  @Override
  public void takeSnapshot(
      Empty request, StreamObserver<FlightRecorderProto.Recording> responseObserver) {
    try {
      responseObserver.onNext(toProto(flightRecorder.takeSnapshot()));
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  @Override
  public void dumpRecording(
      FlightRecorderProto.DumpRecordingRequest request, StreamObserver<Empty> responseObserver) {
    try {
      if (request.getDestination().isEmpty()) {
        throw Grpc.invalidArgument("Destination path required");
      }
      var recording = findRecording(request.getId());
      try {
        recording.dump(Paths.get(request.getDestination()));
      } catch (IOException e) {
        throw failedPrecondition(e.getMessage(), e);
      }
      responseObserver.onNext(Empty.getDefaultInstance());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  @Override
  public void streamRecordingBytes(
      FlightRecorderProto.StreamRecordingBytesRequest request,
      StreamObserver<FlightRecorderProto.RecordingBytes> responseObserver) {
    try {
      var startTime = request.hasStartTime() ? Protobuf.toInstant(request.getStartTime()) : null;
      var endTime = request.hasEndTime() ? Protobuf.toInstant(request.getEndTime()) : null;
      var recording = findRecording(request.getId());
      // getStream will return null if there is no data recorded
      var is = recording.getStream(startTime, endTime);
      if (is != null) {
        try {
          var bytes = new byte[DATA_CHUNK_SIZE];
          int len;
          while ((len = is.read(bytes)) > 0) {
            responseObserver.onNext(
                FlightRecorderProto.RecordingBytes.newBuilder()
                    .setBytes(ByteString.copyFrom(bytes, 0, len))
                    .build());
          }
        } finally {
          is.close();
        }
      }
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  @Override
  public void getConfigurations(
      Empty request, StreamObserver<FlightRecorderProto.Configurations> responseObserver) {
    try {
      var configs = Configuration.getConfigurations().stream()
          .map(FlightRecorderService::toProto)
          .collect(Collectors.toList());
      responseObserver.onNext(
          FlightRecorderProto.Configurations.newBuilder().addAllConfigurations(configs).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  @Override
  public void getEventTypes(
      Empty request, StreamObserver<FlightRecorderProto.EventTypes> responseObserver) {
    try {
      var types = flightRecorder.getEventTypes().stream()
          .map(FlightRecorderService::toProto)
          .collect(Collectors.toList());
      responseObserver.onNext(
          FlightRecorderProto.EventTypes.newBuilder().addAllEventTypes(types).build());
      responseObserver.onCompleted();
    } catch (Exception e) {
      responseObserver.onError(e);
      logError(e);
    }
  }

  @Override
  public void streamRecordingChanges(
      Empty request, StreamObserver<FlightRecorderProto.Recording> responseObserver) {
    var observer = (ServerCallStreamObserver<FlightRecorderProto.Recording>) responseObserver;
    var listener = new FlightRecorderListener() {
      @Override
      public void recordingStateChanged(Recording recording) {
        try {
          responseObserver.onNext(toProto(recording));
        } catch (Exception e) {
          if (!Grpc.isCancelled(e)) {
            logger.log(Level.WARNING, "Error sending notification", e);
          }
        }
      }
    };
    observer.setOnCancelHandler(() -> FlightRecorder.removeListener(listener));
    FlightRecorder.addListener(listener);
  }

  private void logError(Throwable e) {
    if (isServerError(Status.fromThrowable(e).getCode())) {
      logger.log(Level.SEVERE, "Server error", e);
    }
  }

  private Recording findRecording(long id) {
    return flightRecorder.getRecordings().stream()
        .filter(r -> r.getId() == id)
        .findAny()
        .orElseThrow(() -> Grpc.notFound("Recording not found"));
  }

  private static FlightRecorderProto.Recording toProto(Recording recording) {
    var builder = FlightRecorderProto.Recording.newBuilder()
        .setId(recording.getId())
        .setName(recording.getName())
        .setState(toProto(recording.getState()))
        .setDisk(recording.isToDisk())
        .setDumpOnExit(recording.getDumpOnExit())
        .setSize(recording.getSize())
        .setMaxSize(recording.getMaxSize())
        .setDestination(
            Optional.ofNullable(recording.getDestination()).map(Path::toString).orElse(""))
        .putAllSettings(recording.getSettings());
    Optional.ofNullable(recording.getMaxAge())
        .ifPresent(maxAge -> builder.setMaxAge(Protobuf.toProto(maxAge)));
    Optional.ofNullable(recording.getDuration())
        .ifPresent(duration -> builder.setDuration(Protobuf.toProto(duration)));
    Optional.ofNullable(recording.getStartTime())
        .ifPresent(startTime -> builder.setStartTime(Protobuf.toTimestamp(startTime)));
    Optional.ofNullable(recording.getStopTime())
        .ifPresent(stopTime -> builder.setStopTime(Protobuf.toTimestamp(stopTime)));
    return builder.build();
  }

  private static FlightRecorderProto.RecordingState toProto(RecordingState state) {
    switch (state) {
      case NEW:
        return FlightRecorderProto.RecordingState.STATE_NEW;
      case DELAYED:
        return FlightRecorderProto.RecordingState.STATE_DELAYED;
      case RUNNING:
        return FlightRecorderProto.RecordingState.STATE_RUNNING;
      case STOPPED:
        return FlightRecorderProto.RecordingState.STATE_STOPPED;
      case CLOSED:
        return FlightRecorderProto.RecordingState.STATE_CLOSED;
      default:
        return FlightRecorderProto.RecordingState.UNRECOGNIZED;
    }
  }

  private static FlightRecorderProto.Configuration toProto(Configuration config) {
    return FlightRecorderProto.Configuration.newBuilder()
        .putAllSettings(config.getSettings())
        .setName(Strings.nullToEmpty(config.getName()))
        .setLabel(Strings.nullToEmpty(config.getLabel()))
        .setDescription(Strings.nullToEmpty(config.getDescription()))
        .setProvider(Strings.nullToEmpty(config.getProvider()))
        .setContents(Strings.nullToEmpty(config.getContents()))
        .build();
  }

  private static FlightRecorderProto.EventType toProto(EventType eventType) {
    return FlightRecorderProto.EventType.newBuilder()
        .setName(eventType.getName())
        .setLabel(Strings.nullToEmpty(eventType.getLabel()))
        .setDescription(Strings.nullToEmpty(eventType.getDescription()))
        .setId(eventType.getId())
        .setEnabled(eventType.isEnabled())
        .addAllCategoryNames(eventType.getCategoryNames())
        .addAllSettingDescriptors(
            eventType.getSettingDescriptors().stream()
                .map(FlightRecorderService::toProto)
                .collect(Collectors.toList()))
        .build();
  }

  private static FlightRecorderProto.SettingDescriptor toProto(SettingDescriptor sd) {
    return FlightRecorderProto.SettingDescriptor.newBuilder()
        .setName(sd.getName())
        .setLabel(Strings.nullToEmpty(sd.getLabel()))
        .setDescription(Strings.nullToEmpty(sd.getDescription()))
        .setContentType(Strings.nullToEmpty(sd.getContentType()))
        .setTypeName(sd.getTypeName())
        .setDefaultValue(sd.getDefaultValue())
        .build();
  }
}
