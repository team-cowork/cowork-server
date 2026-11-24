package com.cowork.applet.global.outbound

import java.net.InetAddress

class PublicAddressesTest extends munit.FunSuite:

  private def isPublic(literal: String): Boolean = PublicAddresses.isPublic(InetAddress.getByName(literal))

  test("isPublic / 공개 IPv4와 IPv6 주소 / 허용한다") {
    // Given
    val addresses = Seq("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111")

    // When / Then
    addresses.foreach(address => assert(isPublic(address), address))
  }

  test("isPublic / 루프백, 사설망, 링크 로컬, 메타데이터 주소 / 거부한다") {
    // Given
    val addresses = Seq("127.0.0.1", "10.0.0.144", "172.16.5.4", "192.168.0.1", "169.254.169.254", "0.0.0.0", "::1", "fe80::1")

    // When / Then
    addresses.foreach(address => assert(!isPublic(address), address))
  }

  test("isPublic / CGNAT, 문서용, 예약 대역, 멀티캐스트 / 거부한다") {
    // Given
    val addresses = Seq("100.64.0.1", "192.0.2.1", "198.18.0.1", "203.0.113.9", "240.0.0.1", "255.255.255.255", "224.0.0.1")

    // When / Then
    addresses.foreach(address => assert(!isPublic(address), address))
  }

  test("isPublic / IPv6 ULA, NAT64, 문서용 대역 / 거부한다") {
    // Given
    val addresses = Seq("fd00::1", "fc12::1", "64:ff9b::a00:1", "2001:db8::1")

    // When / Then
    addresses.foreach(address => assert(!isPublic(address), address))
  }

  test("isPublic / 사설 IPv4를 담은 IPv6 주소 / IPv4 기준으로 거부한다") {
    // Given
    val addresses = Seq("::ffff:127.0.0.1", "::ffff:10.0.0.1", "::ffff:169.254.169.254")

    // When / Then
    addresses.foreach(address => assert(!isPublic(address), address))
  }
