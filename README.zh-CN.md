# smux4j

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
    implementation("com.github.djylb:smux4j:v0.1.0")
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
    implementation 'com.github.djylb:smux4j:v0.1.0'
}
```

当前版本：`v0.1.0`

## 使用

默认配置：

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
    stream.closeWrite();

    byte[] buffer = new byte[1024];
    int n = stream.read(buffer);
}
```

自定义配置：

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
        .buildValidated();

Session session = Smux.client(socket, config);
```

服务端：

```java
import io.github.djylb.smux4j.Session;
import io.github.djylb.smux4j.Smux;
import io.github.djylb.smux4j.Stream;

import java.net.Socket;

Socket socket = ...;

try (Session session = Smux.server(socket)) {
    try (Stream stream = session.acceptStream()) {
        byte[] buffer = new byte[1024];
        int n = stream.read(buffer);
        stream.write(buffer, 0, n);
    }
}
```

`Smux.client(...)` 和 `Smux.server(...)` 也支持 `InputStream`/`OutputStream`、`ByteChannel`，以及分开的 `ReadableByteChannel`/`WritableByteChannel`。

原版 smux：[xtaci/smux](https://github.com/xtaci/smux)
