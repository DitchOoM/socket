[![Contributors][contributors-shield]][contributors-url]
[![Forks][forks-shield]][forks-url]
[![Stargazers][stars-shield]][stars-url]
[![Issues][issues-shield]][issues-url]
[![MIT License][license-shield]][license-url]
[![LinkedIn][linkedin-shield]][linkedin-url]

<!-- PROJECT LOGO -->

<br />
<p align="center">
<h3 align="center">Socket</h3>

<p align="center">
A kotlin multiplatform library that allows you send network data via sockets</a>
<br />
<!-- <a href="https://github.com/DitchOoM/socket"><strong>Explore the docs »</strong></a> -->
<br />
<br />
<!-- <a href="https://github.com/DitchOoM/socket">View Demo</a>
· -->
<a href="https://github.com/DitchOoM/socket/issues">Report Bug</a>
·
<a href="https://github.com/DitchOoM/socket/issues">Request Feature</a>
</p>


<details open="open">
  <summary>Table of Contents</summary>
  <ol>
    <li>
      <a href="#about-the-project">About The Project</a>
      <ul>
        <li><a href="#runtime-dependencies">Runtime Dependencies</a></li>
      </ul>
      <ul>
        <li><a href="#supported-platforms">Supported Platforms</a></li>
      </ul>
    </li>
    <li><a href="#installation">Installation</a></li>
    <li>
      <a href="#usage">Usage</a>
      <ul>
        <li><a href="#suspend-connect-read-write-and-close">Suspend connect, read, write and close</a></li>
        <li><a href="#TLS-support">TLS Support</a></li>
      </ul>
    </li>
    <li>
      <a href="#building-locally">Building Locally</a>
    </li>
    <li><a href="#getting-started">Getting Started</a></li>
    <li><a href="#roadmap">Roadmap</a></li>
    <li><a href="#contributing">Contributing</a></li>
    <li><a href="#license">License</a></li>
  </ol>
</details>

## About The Project

Managing metwork calls can be slightly different based on each platform. This project aims to make
it **easier to manage sockets in a cross platform way using kotlin multiplatform**. This was
originally created as a side project for a kotlin multiplatform mqtt data sync solution.

### Runtime Dependencies

* [Buffer](https://github.com/DitchOoM/buffer)

### [Supported Platforms](https://kotlinlang.org/docs/reference/mpp-supported-platforms.html)

| Platform | 🛠Builds🛠 + 🔬Tests🔬 |                                                                                                    Native Wrapper For                                                                                                     |  
| :---: | :---: |:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|
| `JVM` 1.8 |🚀|                                        [AsynchronousSocketChannel](https://docs.oracle.com/en/java/javase/12/docs/api/java.base/java/nio/channels/AsynchronousSocketChannel.html)                                         |
| `Node.js` |🚀|                                                                                 [Socket](https://nodejs.org/api/net.html#class-netsocket)                                                                                 |
| `Browser` (Chrome) |🚀|                                                                                                        unavailable                                                                                                        |
| `Android` |🚀| [AsynchronousSocketChannel](https://developer.android.com/reference/java/nio/channels/AsynchronousSocketChannel) falling back to [SocketChannel](https://developer.android.com/reference/java/nio/channels/SocketChannel) |
| `iOS` |🚀|                                                               Custom wrapped [NWConnection](https://developer.apple.com/documentation/network/nwconnection)                                                               |
| `WatchOS` |🚀|                                                               Custom wrapped [NWConnection](https://developer.apple.com/documentation/network/nwconnection)                                                               |
| `TvOS` |🚀|                                                               Custom wrapped [NWConnection](https://developer.apple.com/documentation/network/nwconnection)                                                               |
| `MacOS` |🚀|                                                               Custom wrapped [NWConnection](https://developer.apple.com/documentation/network/nwconnection)                                                               |
| `Linux X64` |🔮|                                                                                                            WIP                                                                                                            |
| `Windows X64` |🔮|                                                                                                       WIP                                                                                                                 |

## Installation

- Add `implementation("com.ditchoom:socket:$version")` to your `build.gradle` dependencies

## Client Socket Usage

### Suspend connect read write and close

```kotlin
// Run in a coroutine scope
val socket = ClientSocket.connect(
    port = 80, // no default
    hostname = "example.com", // null is default which points to localhost
    timeout = 15.seconds, // default
    socketOptions = null, // default
)
val isOpen = socket.isOpen()
val localPort = socket.localPort()
val remotePort = socket.remotePort()
val stringRead = socket.readString(com.ditchoom.buffer.Charset.UTF8) // read a utf8 string
val readBuffer = socket.read() // read a ReadBuffer as defined in the buffer module
val bytesWritten = socket.write(buffer) // write the buffer to the socket
socket.close() // close the socket
```

Or use lambda which auto closes the socket

```kotlin
// Run in a coroutine scope, same defaults as the other `connect` method
val response = ClientSocket.connect(80, hostname = "example.com") { socket ->
    val request =
        """
GET / HTTP/1.1
Host: example.com
Connection: close

"""
    val bytesWritten = socket.write(request)
    socket.read() // can throw a SocketClosedException
}
// response is populated, no need to call socket.close()
```

## TLS support

```kotlin
// Simply add tls=true to your ClientSocket.connect or ClientSocket.allocate
val response = ClientSocket.connect(port, hostname, tls = true) { socket ->
    // do something
}
```

## Building Locally

- `git clone git@github.com:DitchOoM/socket.git`
- Open cloned directory with [Intellij IDEA](https://www.jetbrains.com/idea/download).
    - Be sure
      to [open with gradle](https://www.jetbrains.com/help/idea/gradle.html#gradle_import_project_start)

## Roadmap

See the [open issues](https://github.com/DitchOoM/socket/issues) for a list of proposed features (
and known issues).

## Contributing

Contributions are what make the open source community such an amazing place to be learn, inspire,
and create. Any contributions you make are **greatly appreciated**.

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## License

Distributed under the Apache 2.0 License. See `LICENSE` for more information.

[contributors-shield]: https://img.shields.io/github/contributors/DitchOoM/socket.svg?style=for-the-badge

[contributors-url]: https://github.com/DitchOoM/socket/graphs/contributors

[forks-shield]: https://img.shields.io/github/forks/DitchOoM/socket.svg?style=for-the-badge

[forks-url]: https://github.com/DitchOoM/socket/network/members

[stars-shield]: https://img.shields.io/github/stars/DitchOoM/socket.svg?style=for-the-badge

[stars-url]: https://github.com/DitchOoM/socket/stargazers

[issues-shield]: https://img.shields.io/github/issues/DitchOoM/socket.svg?style=for-the-badge

[issues-url]: https://github.com/DitchOoM/socket/issues

[license-shield]: https://img.shields.io/github/license/DitchOoM/socket.svg?style=for-the-badge

[license-url]: https://github.com/DitchOoM/socket/blob/master/LICENSE.md

[linkedin-shield]: https://img.shields.io/badge/-LinkedIn-black.svg?style=for-the-badge&logo=linkedin&colorB=555

[linkedin-url]: https://www.linkedin.com/in/thebehera

[byte-socket-api]: https://docs.oracle.com/javase/8/docs/api/java/nio/Bytesocket.html

[maven-central]: https://search.maven.org/search?q=com.ditchoom

[npm]: https://www.npmjs.com/search?q=ditchoom-socket

[cocoapods]: https://cocoapods.org/pods/DitchOoM-socket

[apt]: https://packages.ubuntu.com/search?keywords=ditchoom&searchon=names&suite=groovy&section=all

[yum]: https://pkgs.org/search/?q=DitchOoM-socket

[chocolately]: https://chocolatey.org/packages?q=DitchOoM-socket
