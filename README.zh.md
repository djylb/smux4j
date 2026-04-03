# smux4j

[English](./README.md) | [中文](./README.zh.md)

`smux4j` 是 [xtaci/smux](https://github.com/xtaci/smux) 的 Java 实现。它可以在单个底层双工连接（例如一个 TCP Socket）上并发承载多个彼此隔离的逻辑流。

## 安装

Gradle Kotlin DSL：

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

Gradle Groovy DSL：

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

当前版本：`v0.1.1`

## 核心概念

- **Session（会话）**：对应一个底层物理连接，负责流管理、流控、保活和关闭。
- **Stream（流）**：Session 上的逻辑全双工通道，可看作轻量级虚拟 Socket。

## 快速开始

### 服务端

```java
import io.github.djylb.smux4j.Session;
import io.github.djylb.smux4j.Smux;
import io.github.djylb.smux4j.Stream;

import java.net.Socket;

Socket socket = ...; // 例如来自 ServerSocket.accept()

try (Session session = Smux.server(socket)) {
    try (Stream stream = session.acceptStream()) {
        byte[] buffer = new byte[1024];
        int n = stream.read(buffer);
        stream.write(buffer, 0, n);
    }
}
```

### 客户端

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
    stream.closeWrite(); // 可选：半关闭写端

    byte[] buffer = new byte[1024];
    int n = stream.read(buffer);
}
```

## 自定义配置

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

## 支持的底层 I/O 类型

`Smux.client(...)` 与 `Smux.server(...)` 都支持以下底层连接类型：

- `Socket`
- `InputStream` + `OutputStream`
- `ByteChannel`
- `ReadableByteChannel` + `WritableByteChannel`
- `SmuxConnection`

---

## 核心 API 总览

### `Smux`

- `defaultConfig()`
- `verifyConfig(Config)`
- 客户端工厂方法：
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
- 服务端工厂方法：
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

### `Session`（实现 `Flushable`、`Channel`）

- 流生命周期：
  - `openStream()` / `open()`
  - `acceptStream()` / `accept()`
  - `acceptStream(Duration)` / `accept(Duration)`
- 状态与指标：
  - `numStreams()` / `getStreamCount()`
  - `isClosed()` / `isOpen()`
  - `isClient()`
- 地址与配置：
  - `getConfig()`
  - `getConnection()`
  - `getLocalAddress()` / `getRemoteAddress()`
- 异步生命周期：
  - `closeFuture()` / `getCloseFuture()`
- 超时控制：
  - `setDeadline(Instant)` / `getDeadline()`（用于 `accept*`）
- I/O 与关闭：
  - `flush()`
  - `close()`

### `Stream`（实现 `ByteChannel`、`Flushable`）

- 读取：
  - `read(byte[])`
  - `read(byte[], int, int)`
  - `read(ByteBuffer)`
- 写入：
  - `write(int)`
  - `write(byte[])`
  - `write(byte[], int, int)`
  - `write(ByteBuffer)`
- 视图与传输：
  - `getInputStream()` / `getOutputStream()`
  - `transferTo(OutputStream)` / `writeTo(OutputStream)`
- 关闭：
  - `closeWrite()`
  - `close()`
- 状态：
  - `isClosed()` / `isOpen()`
  - `isWriteClosed()`
  - `isRemoteClosed()`
- 超时控制：
  - `setReadDeadline(Instant)` / `getReadDeadline()`
  - `setWriteDeadline(Instant)` / `getWriteDeadline()`
  - `setDeadline(Instant)`
- 上下文信息：
  - `getId()`
  - `getSession()`
  - `getLocalAddress()` / `getRemoteAddress()`
  - `closeFuture()` / `getCloseFuture()`
  - `flush()`

### `Config`

- 常量：
  - `VERSION_1`、`VERSION_2`
- 工厂方法：
  - `defaultConfig()`
  - `builder()`
- 复制/构建：
  - `copy()`
  - `toBuilder()`
  - `validate()`
- Getter/Setter：
  - `getVersion()` / `setVersion(int)`
  - `isKeepAliveDisabled()` / `setKeepAliveDisabled(boolean)`
  - `getKeepAliveInterval()` / `setKeepAliveInterval(Duration)`
  - `getKeepAliveIntervalMillis()` / `setKeepAliveIntervalMillis(long)`
  - `getKeepAliveTimeout()` / `setKeepAliveTimeout(Duration)`
  - `getKeepAliveTimeoutMillis()` / `setKeepAliveTimeoutMillis(long)`
  - `getMaxFrameSize()` / `setMaxFrameSize(int)`
  - `getMaxReceiveBuffer()` / `setMaxReceiveBuffer(int)`
  - `getMaxStreamBuffer()` / `setMaxStreamBuffer(int)`
- Builder 方法：
  - `version(int)`
  - `keepAliveDisabled(boolean)`
  - `keepAliveInterval(Duration)` / `keepAliveIntervalMillis(long)`
  - `keepAliveTimeout(Duration)` / `keepAliveTimeoutMillis(long)`
  - `maxFrameSize(int)`
  - `maxReceiveBuffer(int)`
  - `maxStreamBuffer(int)`
  - `build()` / `buildValidated()`

### `SmuxConnection` 与 `SmuxConnections`

`SmuxConnection` 是底层传输抽象：

- `getInputStream()`
- `getOutputStream()`
- `flush()`
- `getLocalAddress()`
- `getRemoteAddress()`
- `close()`

`SmuxConnections` 适配器：

- `of(Socket)`
- `of(InputStream, OutputStream)`
- `of(ByteChannel)`
- `of(ReadableByteChannel, WritableByteChannel)`
- `of(InputStream, OutputStream, SocketAddress, SocketAddress)`
