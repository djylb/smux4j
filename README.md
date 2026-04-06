# smux4j

[English](./README.md) | [中文](./README.zh.md)

A Java implementation of [xtaci/smux](https://github.com/xtaci/smux). `smux4j` lets you multiplex many independent logical streams over one underlying duplex connection (for example, one TCP socket).

## Installation

Gradle Kotlin DSL:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

dependencies {
    implementation("com.github.djylb:smux4j:v0.1.1")
}
```

Gradle Groovy DSL:

```groovy
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}

dependencies {
    implementation 'com.github.djylb:smux4j:v0.1.1'
}
```

Current version: `v0.1.1`

## Concepts

- **Session**: wraps one physical connection and manages stream lifecycle, flow control, keepalive, and shutdown.
- **Stream**: a virtual full-duplex channel inside a session; each stream behaves similarly to a lightweight socket.

## Quick Start

### Server

```java
import io.github.djylb.smux4j.Session;
import io.github.djylb.smux4j.Smux;
import io.github.djylb.smux4j.Stream;

import java.net.Socket;

Socket socket = ...; // e.g. from ServerSocket.accept()

try (Session session = Smux.server(socket)) {
    try (Stream stream = session.acceptStream()) {
        byte[] buffer = new byte[1024];
        int n = stream.read(buffer);
        if (n != -1) {
            stream.write(buffer, 0, n);
        }
    }
}
```

### Client

```java
import io.github.djylb.smux4j.Session;
import io.github.djylb.smux4j.Smux;
import io.github.djylb.smux4j.Stream;

import java.net.Socket;
import java.nio.charset.StandardCharsets;

Socket socket = ...;

try (Session session = Smux.client(socket);
     Stream stream = session.openStream()) {

    stream.write("hello".getBytes(StandardCharsets.UTF_8));
    stream.closeWrite(); // optional half-close

    byte[] buffer = new byte[1024];
    int n = stream.read(buffer);
}
```

## Config

```java
import io.github.djylb.smux4j.Config;
import io.github.djylb.smux4j.Session;
import io.github.djylb.smux4j.Smux;

import java.net.Socket;
import java.time.Duration;

Socket socket = ...;

Config config = Config.builder()
        .version(Config.VERSION_2)
        .keepAliveInterval(Duration.ofSeconds(10))
        .keepAliveTimeout(Duration.ofSeconds(30))
        .maxFrameSize(32 * 1024)
        .maxReceiveBuffer(4 * 1024 * 1024)
        .maxStreamBuffer(64 * 1024)
        .buildValidated();

Session session = Smux.client(socket, config);
```

## Supported underlying I/O types

Both `Smux.client(...)` and `Smux.server(...)` provide overloads for:

- `Socket`
- `InputStream` + `OutputStream`
- `ByteChannel`
- `ReadableByteChannel` + `WritableByteChannel`
- `SmuxConnection`

---

## API Reference (core)

### `Smux`

- `defaultConfig()`
- `verifyConfig(Config)`
- Client factories:
  - `client(SmuxConnection)`
  - `client(SmuxConnection, Config)`
  - `client(InputStream, OutputStream)`
  - `client(InputStream, OutputStream, Config)`
  - `client(ByteChannel)`
  - `client(ByteChannel, Config)`
  - `client(ReadableByteChannel, WritableByteChannel)`
  - `client(ReadableByteChannel, WritableByteChannel, Config)`
  - `client(Socket)`
  - `client(Socket, Config)`
- Server factories:
  - `server(SmuxConnection)`
  - `server(SmuxConnection, Config)`
  - `server(InputStream, OutputStream)`
  - `server(InputStream, OutputStream, Config)`
  - `server(ByteChannel)`
  - `server(ByteChannel, Config)`
  - `server(ReadableByteChannel, WritableByteChannel)`
  - `server(ReadableByteChannel, WritableByteChannel, Config)`
  - `server(Socket)`
  - `server(Socket, Config)`

### `Session` (`Flushable`, `Channel`)

- Stream lifecycle:
  - `openStream()` / `open()`
  - `acceptStream()` / `accept()`
  - `acceptStream(Duration)` / `accept(Duration)`
- State & metrics:
  - `numStreams()` / `getStreamCount()`
  - `isClosed()` / `isOpen()`
  - `isClient()`
- Address & config:
  - `getConfig()`
  - `getConnection()`
  - `getLocalAddress()` / `getRemoteAddress()`
- Async lifecycle:
  - `closeFuture()` / `getCloseFuture()`
- Deadlines:
  - `setDeadline(Instant)` / `getDeadline()` (for `accept*`)
- IO & close:
  - `flush()`
  - `close()`

### `Stream` (`ByteChannel`, `Flushable`)

- Read:
  - `read(byte[])`
  - `read(byte[], int, int)`
  - `read(ByteBuffer)`
- Write:
  - `write(int)`
  - `write(byte[])`
  - `write(byte[], int, int)`
  - `write(ByteBuffer)`
- Stream/view helpers:
  - `getInputStream()` / `getOutputStream()`
  - `transferTo(OutputStream)` / `writeTo(OutputStream)`
- Half/close:
  - `closeWrite()`
  - `close()`
- State:
  - `isClosed()` / `isOpen()`
  - `isWriteClosed()`
  - `isRemoteClosed()`
- Deadlines:
  - `setReadDeadline(Instant)` / `getReadDeadline()`
  - `setWriteDeadline(Instant)` / `getWriteDeadline()`
  - `setDeadline(Instant)`
- Context/meta:
  - `getId()`
  - `getSession()`
  - `getLocalAddress()` / `getRemoteAddress()`
  - `closeFuture()` / `getCloseFuture()`
  - `flush()`

### `Config`

- Constants:
  - `VERSION_1`, `VERSION_2`
- Factories:
  - `defaultConfig()`
  - `builder()`
- Copy/build:
  - `copy()`
  - `toBuilder()`
  - `validate()`
- Getters/setters:
  - `getVersion()` / `setVersion(int)`
  - `isKeepAliveDisabled()` / `setKeepAliveDisabled(boolean)`
  - `getKeepAliveInterval()` / `setKeepAliveInterval(Duration)`
  - `getKeepAliveIntervalMillis()` / `setKeepAliveIntervalMillis(long)`
  - `getKeepAliveTimeout()` / `setKeepAliveTimeout(Duration)`
  - `getKeepAliveTimeoutMillis()` / `setKeepAliveTimeoutMillis(long)`
  - `getMaxFrameSize()` / `setMaxFrameSize(int)`
  - `getMaxReceiveBuffer()` / `setMaxReceiveBuffer(int)`
  - `getMaxStreamBuffer()` / `setMaxStreamBuffer(int)`
- Builder methods:
  - `version(int)`
  - `keepAliveDisabled(boolean)`
  - `keepAliveInterval(Duration)` / `keepAliveIntervalMillis(long)`
  - `keepAliveTimeout(Duration)` / `keepAliveTimeoutMillis(long)`
  - `maxFrameSize(int)`
  - `maxReceiveBuffer(int)`
  - `maxStreamBuffer(int)`
  - `build()` / `buildValidated()`

### `SmuxConnection` and `SmuxConnections`

`SmuxConnection` is the transport abstraction:

- `getInputStream()`
- `getOutputStream()`
- `flush()`
- `getLocalAddress()`
- `getRemoteAddress()`
- `close()`

`SmuxConnections` adapters:

- `of(Socket)`
- `of(InputStream, OutputStream)`
- `of(ByteChannel)`
- `of(ReadableByteChannel, WritableByteChannel)`
- `of(InputStream, OutputStream, SocketAddress, SocketAddress)`
