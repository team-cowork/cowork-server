package com.cowork.applet.domain.review.service

import com.cowork.applet.domain.applet.model.{BundleFile, FileEncoding}
import com.cowork.applet.domain.review.model.ReviewSeverity.{BLOCK, WARN}
import com.cowork.applet.domain.review.model.{ReviewFinding, ReviewSeverity}
import java.nio.charset.StandardCharsets.{US_ASCII, UTF_8}
import java.util.Base64
import scala.collection.mutable
import scala.util.matching.Regex

// Static review gate for uploaded bundles. It rejects code that the client sandbox would block anyway or that
// tries to leave the sandbox, so authors get the reason before publishing. The sandboxed iframe and its CSP remain
// the real defense: a pattern scan cannot prove that JavaScript is safe.
object BundleReviewer:
  val EntryPath = "index.html"
  val MaxFiles = 200
  val MaxFileBytes: Long = 1024L * 1024
  val MaxBundleBytes: Long = 3L * 1024 * 1024
  val TextExtensions: Set[String] = Set("html", "htm", "css", "js", "mjs", "json")

  // Assets stay small because they are stored in SurrealDB with the version. SVG is excluded because it can run scripts.
  val AssetExtensions: Set[String] = Set("png", "jpg", "jpeg", "webp", "woff2")
  val MaxAssets = 10
  val MaxAssetBytes = 256 * 1024
  val MaxTotalAssetBytes = 1024 * 1024

  // The base64 length limit lets the review reject oversized assets before decoding them.
  private val MaxAssetBase64Length = (MaxAssetBytes + 2) / 3 * 4

  private val PngSignature = Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)
  private val JpegSignature = Array(0xff, 0xd8, 0xff).map(_.toByte)

  private val MaxPathLength = 200
  private val MaxPathDepth = 10
  private val MaxMatchesPerRule = 5
  private val MaxFindings = 100

  // Every segment starts with a letter, digit, underscore, or hyphen, so hidden files and `..` never pass.
  private val PathPattern = """[A-Za-z0-9_-][A-Za-z0-9._-]*(?:/[A-Za-z0-9_-][A-Za-z0-9._-]*)*""".r

  private val Html = Set("html", "htm")
  private val Script = Set("js", "mjs")
  private val Style = Set("css")
  private val ScriptCapable = Html ++ Script

  // A URL with any scheme or a protocol-relative URL leaves the bundle.
  private val Remote = """(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//)"""

  private final case class ContentRule(
      id: String,
      severity: ReviewSeverity,
      extensions: Set[String],
      pattern: Regex,
      message: String
  )

  private val ContentRules = List(
    ContentRule(
      "DYNAMIC_CODE",
      BLOCK,
      ScriptCapable,
      """(?<![\w$.])eval\s*\(|\bnew\s+Function\s*\(|(?<![\w$.])Function\s*\(\s*['"`]""".r,
      "문자열을 코드로 실행하는 eval과 Function 생성자는 사용할 수 없습니다."
    ),
    ContentRule(
      "STRING_TIMER",
      BLOCK,
      ScriptCapable,
      """\bset(?:Timeout|Interval)\s*\(\s*['"`]""".r,
      "setTimeout과 setInterval에 문자열 코드를 전달할 수 없습니다."
    ),
    ContentRule(
      "EXTERNAL_SCRIPT",
      BLOCK,
      Html,
      s"""(?i)<script\\b[^>]*\\bsrc\\s*=\\s*["']?\\s*$Remote""".r,
      "외부 스크립트를 불러올 수 없습니다. 스크립트를 번들에 포함하십시오."
    ),
    ContentRule(
      "EXTERNAL_MODULE",
      BLOCK,
      ScriptCapable,
      s"""\\bimport\\s*\\(\\s*["'`]\\s*$Remote|\\bfrom\\s*["']\\s*$Remote|\\bimportScripts\\s*\\(""".r,
      "외부 모듈을 불러올 수 없습니다. 모듈을 번들에 포함하십시오."
    ),
    ContentRule(
      "EXTERNAL_STYLESHEET",
      BLOCK,
      Html,
      s"""(?i)<link\\b[^>]*\\bhref\\s*=\\s*["']?\\s*$Remote""".r,
      "외부 리소스를 link 태그로 불러올 수 없습니다."
    ),
    ContentRule(
      "EXTERNAL_STYLESHEET",
      BLOCK,
      Style,
      s"""(?i)@import\\s+(?:url\\(\\s*)?["']?\\s*$Remote""".r,
      "외부 스타일시트를 불러올 수 없습니다."
    ),
    ContentRule(
      "JAVASCRIPT_URL",
      BLOCK,
      Html,
      """(?i)\b(?:href|src|action|formaction)\s*=\s*["']?\s*javascript:""".r,
      "javascript: URL은 사용할 수 없습니다."
    ),
    ContentRule(
      "COOKIE_ACCESS",
      BLOCK,
      ScriptCapable,
      """\bdocument\s*\.\s*cookie\b""".r,
      "쿠키에 접근할 수 없습니다."
    ),
    ContentRule(
      "FRAME_ESCAPE",
      BLOCK,
      ScriptCapable,
      """\b(?:top|parent|opener)\s*\.\s*location\b|\bwindow\s*\.\s*opener\b""".r,
      "앱 밖의 상위 창을 조작할 수 없습니다."
    ),
    ContentRule("BASE_TAG", BLOCK, Html, """(?i)<base\b""".r, "base 태그는 사용할 수 없습니다."),
    ContentRule(
      "META_REFRESH",
      BLOCK,
      Html,
      """(?i)<meta\b[^>]*http-equiv\s*=\s*["']?\s*refresh""".r,
      "meta refresh로 페이지를 이동할 수 없습니다."
    ),
    ContentRule(
      "EMBEDDED_FRAME",
      BLOCK,
      Html,
      """(?i)<(?:iframe|frame|frameset|object|embed|applet)\b""".r,
      "다른 문서를 포함하는 태그는 사용할 수 없습니다."
    ),
    ContentRule(
      "CRYPTO_MINER",
      BLOCK,
      ScriptCapable,
      """(?i)coinhive|coin-hive|cryptonight|cryptoloot|webminepool|deepminer""".r,
      "암호화폐 채굴 코드로 의심되는 내용이 있습니다."
    ),
    ContentRule(
      "NETWORK_API",
      WARN,
      ScriptCapable,
      """\bfetch\s*\(|\bXMLHttpRequest\b|\bnew\s+WebSocket\s*\(|\bnew\s+EventSource\s*\(|\bsendBeacon\s*\(""".r,
      "실행 환경은 직접 네트워크 요청을 차단합니다. 플랫폼 함수를 사용하십시오."
    ),
    ContentRule(
      "BROWSER_STORAGE",
      WARN,
      ScriptCapable,
      """\b(?:localStorage|sessionStorage|indexedDB)\b""".r,
      "실행 환경에서는 브라우저 저장소를 사용할 수 없습니다. 저장소 함수를 사용하십시오."
    ),
    ContentRule(
      "EXTERNAL_RESOURCE",
      WARN,
      Html ++ Style,
      // EXTERNAL_STYLESHEET already blocks `@import url(...)`.
      """(?i)(?<!@import\s{1,16})url\(\s*["']?\s*(?:https?:)?//|<(?:img|audio|video|source)\b[^>]*\bsrc\s*=\s*["']?\s*(?:https?:)?//""".r,
      "실행 환경은 외부 이미지와 미디어를 차단합니다."
    ),
    ContentRule(
      "WEB_ASSEMBLY",
      WARN,
      ScriptCapable,
      """\bWebAssembly\b""".r,
      "실행 환경은 WebAssembly를 차단합니다."
    )
  )

  def review(files: Seq[BundleFile]): Seq[ReviewFinding] =
    if files.isEmpty then return List(bundleFinding("EMPTY_BUNDLE", "업로드한 파일이 없습니다."))
    if files.size > MaxFiles then
      return List(bundleFinding("TOO_MANY_FILES", s"파일은 최대 ${MaxFiles}개까지 업로드할 수 있습니다."))

    val findings = mutable.ListBuffer.empty[ReviewFinding]
    val (assets, texts) = files.partition(file => AssetExtensions.contains(file.extension))
    if texts.iterator.map(file => byteLength(file.content)).sum > MaxBundleBytes then
      findings += bundleFinding("BUNDLE_TOO_LARGE", "텍스트 파일 전체 크기는 3MB를 넘을 수 없습니다.")
    if assets.size > MaxAssets then
      findings += bundleFinding("TOO_MANY_ASSETS", "이미지와 폰트 파일은 최대 10개까지 업로드할 수 있습니다.")
    if !files.exists(_.path == EntryPath) then
      findings += bundleFinding("MISSING_ENTRY", "번들 최상위에 index.html이 있어야 합니다.")

    val seenPaths = mutable.Set.empty[String]
    var assetBytes = 0L
    files.foreach { file =>
      val path = file.path.take(MaxPathLength)
      if !isValidPath(file.path) then findings += fileFinding("INVALID_PATH", "파일 경로가 올바르지 않습니다.", path)
      else if !seenPaths.add(file.path.toLowerCase) then
        findings += fileFinding("DUPLICATE_PATH", "같은 경로의 파일이 두 개 이상 있습니다.", path)
      else if AssetExtensions.contains(file.extension) then
        reviewAsset(file) match
          case Left(finding) => findings += finding
          case Right(size)   => assetBytes += size
      else if !TextExtensions.contains(file.extension) then
        findings += fileFinding(
          "UNSUPPORTED_FILE_TYPE",
          "HTML, CSS, JavaScript, JSON 파일과 PNG, JPEG, WebP, WOFF2 파일만 업로드할 수 있습니다.",
          path
        )
      else if file.encoding != FileEncoding.UTF8 then
        findings += fileFinding("ENCODING_MISMATCH", "텍스트 파일은 utf8 인코딩으로 업로드하십시오.", path)
      else if byteLength(file.content) > MaxFileBytes then
        findings += fileFinding("FILE_TOO_LARGE", "파일 하나의 크기는 1MB를 넘을 수 없습니다.", path)
      else if file.content.indexOf('\u0000') >= 0 then
        findings += fileFinding("BINARY_CONTENT", "텍스트 파일만 업로드할 수 있습니다.", path)
      else findings ++= reviewContent(file)
    }
    if assetBytes > MaxTotalAssetBytes then
      findings += bundleFinding("ASSETS_TOO_LARGE", "이미지와 폰트 파일 전체 크기는 1MB를 넘을 수 없습니다.")
    findings.take(MaxFindings).toList

  def passes(findings: Seq[ReviewFinding]): Boolean = !findings.exists(_.severity == BLOCK)

  private def reviewContent(file: BundleFile): Seq[ReviewFinding] =
    val rules = ContentRules.filter(_.extensions.contains(file.extension))
    rules.flatMap { rule =>
      rule.pattern
        .findAllMatchIn(file.content)
        .take(MaxMatchesPerRule)
        .map(found =>
          ReviewFinding(rule.id, rule.severity, rule.message, Some(file.path), Some(lineOf(file.content, found.start)))
        )
    }

  // Returns the decoded size, or the finding that rejects the asset.
  private def reviewAsset(file: BundleFile): Either[ReviewFinding, Long] =
    if file.encoding != FileEncoding.BASE64 then
      Left(fileFinding("ENCODING_MISMATCH", "이미지와 폰트 파일은 base64 인코딩으로 업로드하십시오.", file.path))
    else if file.content.length > MaxAssetBase64Length then
      Left(fileFinding("ASSET_TOO_LARGE", "이미지와 폰트 파일 하나의 크기는 256KB를 넘을 수 없습니다.", file.path))
    else
      decode(file.content) match
        case None => Left(fileFinding("INVALID_ENCODING", "base64 내용이 올바르지 않습니다.", file.path))
        case Some(bytes) if bytes.length > MaxAssetBytes =>
          Left(fileFinding("ASSET_TOO_LARGE", "이미지와 폰트 파일 하나의 크기는 256KB를 넘을 수 없습니다.", file.path))
        case Some(bytes) if !matchesSignature(file.extension, bytes) =>
          Left(fileFinding("ASSET_TYPE_MISMATCH", "파일 내용이 확장자의 형식과 다릅니다.", file.path))
        case Some(bytes) => Right(bytes.length.toLong)

  private def decode(content: String): Option[Array[Byte]] =
    try Some(Base64.getDecoder.decode(content))
    catch case _: IllegalArgumentException => None

  private def matchesSignature(extension: String, bytes: Array[Byte]): Boolean = extension match
    case "png"         => bytes.startsWith(PngSignature)
    case "jpg" | "jpeg" => bytes.startsWith(JpegSignature)
    case "webp" =>
      bytes.length >= 12 && ascii(bytes, 0, 4) == "RIFF" && ascii(bytes, 8, 12) == "WEBP"
    case "woff2" => bytes.length >= 4 && ascii(bytes, 0, 4) == "wOF2"
    case _       => false

  private def ascii(bytes: Array[Byte], from: Int, until: Int): String = String(bytes.slice(from, until), US_ASCII)

  private def isValidPath(path: String): Boolean =
    path.length <= MaxPathLength && path.count(_ == '/') < MaxPathDepth && PathPattern.matches(path)

  private def lineOf(content: String, offset: Int): Int =
    var line = 1
    var index = 0
    while index < offset do
      if content.charAt(index) == '\n' then line += 1
      index += 1
    line

  private def byteLength(content: String): Long = content.getBytes(UTF_8).length.toLong

  private def bundleFinding(ruleId: String, message: String) = ReviewFinding(ruleId, BLOCK, message)

  private def fileFinding(ruleId: String, message: String, path: String) =
    ReviewFinding(ruleId, BLOCK, message, Some(path))
