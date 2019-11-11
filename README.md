# JFRPC - Java Flight Recorder RPC

A simple gRPC based interface to the 
[Java Flight Recorder API](https://docs.oracle.com/en/java/javase/11/docs/api/jdk.jfr/jdk/jfr/FlightRecorder.html).

This can be used as an alternative to JMX for creating, starting, stopping and downloading
Java Flight Recordings remotely.

## Build Requirements

- Requires Java 11+ (JFR API was introduced in Java 11)
- Gradle 6.0+
