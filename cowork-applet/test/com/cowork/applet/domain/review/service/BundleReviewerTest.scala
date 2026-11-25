package com.cowork.applet.domain.review.service

import com.cowork.applet.domain.applet.model.{BundleFile, FileEncoding}
import com.cowork.applet.domain.review.model.ReviewSeverity
import java.util.Base64

class BundleReviewerTest extends munit.FunSuite:

  private val entry = BundleFile("index.html", "<!doctype html><script src=\"app.js\"></script>")

  private val png = Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  private def asset(path: String, bytes: Array[Byte]): BundleFile =
    BundleFile(path, Base64.getEncoder.encodeToString(bytes), FileEncoding.BASE64)

  private def ruleIds(files: BundleFile*): Seq[String] = BundleReviewer.review(files).map(_.ruleId)

  test("review / 안전한 HTML, CSS, JS 번들 / 결과 없이 통과한다") {
    // Given
    val files = Seq(entry, BundleFile("app.js", "document.body.textContent = 'hi';"), BundleFile("css/app.css", "body { color: red; }"))

    // When
    val findings = BundleReviewer.review(files)

    // Then
    assertEquals(findings, Nil)
    assert(BundleReviewer.passes(findings))
  }

  test("review / 빈 번들 / EMPTY_BUNDLE로 거부한다") {
    // Given
    val files = Seq.empty[BundleFile]

    // When
    val findings = BundleReviewer.review(files)

    // Then
    assertEquals(findings.map(_.ruleId), List("EMPTY_BUNDLE"))
    assert(!BundleReviewer.passes(findings))
  }

  test("review / index.html이 없는 번들 / MISSING_ENTRY로 거부한다") {
    // Given
    val file = BundleFile("main.html", "<p>hi</p>")

    // When
    val ids = ruleIds(file)

    // Then
    assertEquals(ids, List("MISSING_ENTRY"))
  }

  test("review / 파일 수 초과 / 내용을 검사하지 않고 TOO_MANY_FILES로 거부한다") {
    // Given
    val files = entry +: (1 to BundleReviewer.MaxFiles).map(i => BundleFile(s"f$i.js", "eval('x')"))

    // When
    val ids = BundleReviewer.review(files).map(_.ruleId)

    // Then
    assertEquals(ids, List("TOO_MANY_FILES"))
  }

  test("review / 상위 경로와 숨김 파일과 절대 경로 / INVALID_PATH로 거부한다") {
    // Given
    val files = Seq(entry, BundleFile("../x.js", ""), BundleFile(".env.js", ""), BundleFile("/abs.js", ""), BundleFile("a/../b.js", ""))

    // When
    val ids = BundleReviewer.review(files).map(_.ruleId)

    // Then
    assertEquals(ids, List.fill(4)("INVALID_PATH"))
  }

  test("review / 대소문자만 다른 중복 경로 / DUPLICATE_PATH로 거부한다") {
    // Given
    val files = Seq(entry, BundleFile("App.js", ""), BundleFile("app.js", ""))

    // When
    val ids = BundleReviewer.review(files).map(_.ruleId)

    // Then
    assertEquals(ids, List("DUPLICATE_PATH"))
  }

  test("review / 허용하지 않는 확장자 / UNSUPPORTED_FILE_TYPE으로 거부한다") {
    // Given
    val files = Seq(entry, BundleFile("logo.svg", "<svg/>"), BundleFile("run.sh", "rm -rf /"))

    // When
    val ids = BundleReviewer.review(files).map(_.ruleId)

    // Then
    assertEquals(ids, List("UNSUPPORTED_FILE_TYPE", "UNSUPPORTED_FILE_TYPE"))
  }

  test("review / 1MB를 넘는 파일 / FILE_TOO_LARGE로 거부한다") {
    // Given
    val large = BundleFile("big.js", "a" * (BundleReviewer.MaxFileBytes.toInt + 1))

    // When
    val ids = ruleIds(entry, large)

    // Then
    assertEquals(ids, List("FILE_TOO_LARGE"))
  }

  test("review / 전체 크기 초과 / BUNDLE_TOO_LARGE로 거부한다") {
    // Given
    val part = "a" * (BundleReviewer.MaxFileBytes.toInt - 10)
    val files = entry +: (1 to 4).map(i => BundleFile(s"part$i.js", part))

    // When
    val ids = BundleReviewer.review(files).map(_.ruleId)

    // Then
    assertEquals(ids, List("BUNDLE_TOO_LARGE"))
  }

  test("review / NUL 문자가 있는 파일 / BINARY_CONTENT로 거부한다") {
    // Given
    val binary = BundleFile("data.json", "{\u0000}")

    // When
    val ids = ruleIds(entry, binary)

    // Then
    assertEquals(ids, List("BINARY_CONTENT"))
  }

  test("review / eval과 Function 생성자 / DYNAMIC_CODE로 거부한다") {
    // Given
    val script = BundleFile("app.js", "eval(code);\nconst f = new Function('a', 'return a');\nFunction('return this')();")

    // When
    val findings = BundleReviewer.review(Seq(entry, script))

    // Then
    assertEquals(findings.map(_.ruleId), List("DYNAMIC_CODE", "DYNAMIC_CODE", "DYNAMIC_CODE"))
    assertEquals(findings.flatMap(_.line), List(1, 2, 3))
    assert(findings.forall(_.severity == ReviewSeverity.BLOCK))
  }

  test("review / eval이 들어간 다른 이름 / 거부하지 않는다") {
    // Given
    val script = BundleFile("app.js", "math.eval(x); evaluate(y); const retrieval = 1;")

    // When
    val ids = ruleIds(entry, script)

    // Then
    assertEquals(ids, Nil)
  }

  test("review / 문자열을 전달한 타이머 / STRING_TIMER로 거부한다") {
    // Given
    val script = BundleFile("app.js", "setTimeout(\"run()\", 10); setInterval(() => run(), 10);")

    // When
    val ids = ruleIds(entry, script)

    // Then
    assertEquals(ids, List("STRING_TIMER"))
  }

  test("review / 외부 스크립트와 외부 모듈 / 각각 거부한다") {
    // Given
    val html = BundleFile("index.html", "<script src=\"https://cdn.example.com/x.js\"></script><script SRC=//evil.com/a.js></script>")
    val module = BundleFile("app.mjs", "import x from 'https://esm.sh/x';\nimport('//evil.com/y.js');\nimportScripts('w.js');")

    // When
    val ids = ruleIds(html, module)

    // Then
    assertEquals(ids, List("EXTERNAL_SCRIPT", "EXTERNAL_SCRIPT", "EXTERNAL_MODULE", "EXTERNAL_MODULE", "EXTERNAL_MODULE"))
  }

  test("review / 번들 안의 상대 경로 모듈 / 거부하지 않는다") {
    // Given
    val module = BundleFile("app.mjs", "import x from './lib/x.mjs';\nimport('./y.mjs');")

    // When
    val ids = ruleIds(entry, module)

    // Then
    assertEquals(ids, Nil)
  }

  test("review / 외부 스타일시트 / EXTERNAL_STYLESHEET로 거부한다") {
    // Given
    val html = BundleFile("index.html", "<link rel=\"stylesheet\" href=\"https://fonts.example.com/a.css\">")
    val css = BundleFile("app.css", "@import url('https://fonts.example.com/b.css');")

    // When
    val ids = ruleIds(html, css)

    // Then
    assertEquals(ids, List("EXTERNAL_STYLESHEET", "EXTERNAL_STYLESHEET"))
  }

  test("review / 샌드박스 탈출 시도 / 규칙별로 거부한다") {
    // Given
    val html = BundleFile(
      "index.html",
      """<a href="javascript:alert(1)">x</a>
        |<base href="/">
        |<meta http-equiv="refresh" content="0;url=/">
        |<iframe src="a.html"></iframe>""".stripMargin
    )
    val script = BundleFile("app.js", "document.cookie; top.location = '/'; window.opener;")

    // When
    val ids = ruleIds(html, script).toSet

    // Then
    assertEquals(ids, Set("JAVASCRIPT_URL", "BASE_TAG", "META_REFRESH", "EMBEDDED_FRAME", "COOKIE_ACCESS", "FRAME_ESCAPE"))
  }

  test("review / 채굴 코드 서명 / CRYPTO_MINER로 거부한다") {
    // Given
    val script = BundleFile("app.js", "new CoinHive.Anonymous('key').start();")

    // When
    val ids = ruleIds(entry, script)

    // Then
    assertEquals(ids, List("CRYPTO_MINER"))
  }

  test("review / 샌드박스가 차단하는 API / WARN으로 기록하고 통과시킨다") {
    // Given
    val script = BundleFile("app.js", "fetch('/x'); localStorage.getItem('a'); WebAssembly.instantiate(b);")
    val css = BundleFile("app.css", "body { background: url(https://img.example.com/a.png); }")

    // When
    val findings = BundleReviewer.review(Seq(entry, script, css))

    // Then
    assertEquals(findings.map(_.ruleId).toSet, Set("NETWORK_API", "BROWSER_STORAGE", "WEB_ASSEMBLY", "EXTERNAL_RESOURCE"))
    assert(findings.forall(_.severity == ReviewSeverity.WARN))
    assert(BundleReviewer.passes(findings))
  }

  test("review / CSS 파일의 스크립트 패턴 / 파일 형식에 맞지 않는 규칙은 적용하지 않는다") {
    // Given
    val css = BundleFile("app.css", "/* eval( document.cookie */ body { color: red; }")

    // When
    val ids = ruleIds(entry, css)

    // Then
    assertEquals(ids, Nil)
  }

  test("review / 한 파일에 같은 패턴이 많음 / 규칙마다 다섯 개까지만 기록한다") {
    // Given
    val script = BundleFile("app.js", "eval(a);\n" * 20)

    // When
    val findings = BundleReviewer.review(Seq(entry, script))

    // Then
    assertEquals(findings.size, 5)
  }

  test("review / 시그니처가 맞는 PNG, JPEG, WebP, WOFF2 / 통과한다") {
    // Given
    val files = Seq(
      entry,
      asset("img/a.png", png ++ Array.fill[Byte](10)(1)),
      asset("img/b.jpg", Array(0xff, 0xd8, 0xff, 0xe0).map(_.toByte)),
      asset("img/c.webp", "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".getBytes("ISO-8859-1")),
      asset("font/d.woff2", "wOF2rest".getBytes("ISO-8859-1"))
    )

    // When
    val findings = BundleReviewer.review(files)

    // Then
    assertEquals(findings, Nil)
  }

  test("review / 확장자와 내용이 다른 자산 / ASSET_TYPE_MISMATCH로 거부한다") {
    // Given
    val disguised = asset("img/a.png", "<script>alert(1)</script>".getBytes("UTF-8"))

    // When
    val ids = ruleIds(entry, disguised)

    // Then
    assertEquals(ids, List("ASSET_TYPE_MISMATCH"))
  }

  test("review / 인코딩이 형식과 맞지 않음 / ENCODING_MISMATCH로 거부한다") {
    // Given
    val textAsset = BundleFile("img/a.png", "plain")
    val base64Script = BundleFile("app.js", "YQ==", FileEncoding.BASE64)

    // When
    val ids = ruleIds(entry, textAsset, base64Script)

    // Then
    assertEquals(ids, List("ENCODING_MISMATCH", "ENCODING_MISMATCH"))
  }

  test("review / 잘못된 base64 / INVALID_ENCODING으로 거부한다") {
    // Given
    val broken = BundleFile("img/a.png", "@@not-base64@@", FileEncoding.BASE64)

    // When
    val ids = ruleIds(entry, broken)

    // Then
    assertEquals(ids, List("INVALID_ENCODING"))
  }

  test("review / 256KB를 넘는 자산 / ASSET_TOO_LARGE로 거부한다") {
    // Given
    val large = asset("img/a.png", png ++ Array.fill[Byte](BundleReviewer.MaxAssetBytes)(0))

    // When
    val ids = ruleIds(entry, large)

    // Then
    assertEquals(ids, List("ASSET_TOO_LARGE"))
  }

  test("review / 자산 11개 / TOO_MANY_ASSETS로 거부한다") {
    // Given
    val assets = (1 to BundleReviewer.MaxAssets + 1).map(i => asset(s"img/$i.png", png))

    // When
    val ids = BundleReviewer.review(entry +: assets).map(_.ruleId)

    // Then
    assertEquals(ids, List("TOO_MANY_ASSETS"))
  }

  test("review / 자산 합계가 1MB를 넘음 / ASSETS_TOO_LARGE로 거부한다") {
    // Given
    val assets = (1 to 5).map(i => asset(s"img/$i.png", png ++ Array.fill[Byte](BundleReviewer.MaxAssetBytes - png.length)(0)))

    // When
    val ids = BundleReviewer.review(entry +: assets).map(_.ruleId)

    // Then
    assertEquals(ids, List("ASSETS_TOO_LARGE"))
  }

  test("review / SVG 파일 / 스크립트를 담을 수 있어 거부한다") {
    // Given
    val svg = BundleFile("img/a.svg", "<svg onload=\"alert(1)\"/>")

    // When
    val ids = ruleIds(entry, svg)

    // Then
    assertEquals(ids, List("UNSUPPORTED_FILE_TYPE"))
  }
