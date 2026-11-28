package com.cowork.applet.global.outbound

import java.net.{Inet4Address, Inet6Address, InetAddress}

// Applet requests may reach only public internet addresses. Internal VMs, loopback Envoy listeners,
// and cloud metadata endpoints stay unreachable even when a public host name resolves to them.
object PublicAddresses:

  def isPublic(address: InetAddress): Boolean =
    !(address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
      address.isSiteLocalAddress || address.isMulticastAddress) && (address match
      case v4: Inet4Address => isPublicV4(v4.getAddress)
      case v6: Inet6Address => isPublicV6(v6.getAddress)
      case _                => false
    )

  private def isPublicV4(bytes: Array[Byte]): Boolean =
    val a = bytes(0) & 0xff
    val b = bytes(1) & 0xff
    val c = bytes(2) & 0xff
    !(a == 0 ||                                  // this network
      (a == 100 && b >= 64 && b <= 127) ||       // carrier-grade NAT
      (a == 192 && b == 0 && (c == 0 || c == 2)) || // protocol assignments, documentation
      (a == 198 && (b == 18 || b == 19)) ||      // benchmarking
      (a == 198 && b == 51 && c == 100) ||       // documentation
      (a == 203 && b == 0 && c == 113) ||        // documentation
      a >= 240)                                  // reserved and broadcast

  private def isPublicV6(bytes: Array[Byte]): Boolean =
    val first = bytes(0) & 0xff
    val second = bytes(1) & 0xff
    val embedsV4 = bytes.take(10).forall(_ == 0) && (bytes(10) & 0xff) == 0xff && (bytes(11) & 0xff) == 0xff
    val nat64 = bytes.take(12).sameElements(Array(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0).map(_.toByte))
    val documentation = first == 0x20 && second == 0x01 && (bytes(2) & 0xff) == 0x0d && (bytes(3) & 0xff) == 0xb8
    !((first & 0xfe) == 0xfc || embedsV4 || nat64 || documentation)
