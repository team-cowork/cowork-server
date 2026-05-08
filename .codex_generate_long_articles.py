#!/usr/bin/env python3
import csv
import html
import json
import pathlib
import re
import subprocess
import xml.etree.ElementTree as ET


ROOT = pathlib.Path(__file__).resolve().parent
MIN_CHARS = 50_000
TARGET_COUNT = 60
USER_AGENT = "Mozilla/5.0 (Codex article corpus generator)"

STEMS = [
    "한글_바람의기록", "日本語_星の旅", "中文_遠方故事", "العربية_رحلة_الليل",
    "Русский_зимняя_ночь", "Ελληνικά_θάλασσα", "עברית_מסע_ארוך", "हिन्दी_लंबी_कहानी",
    "ไทย_แสงดาว", "বাংলা_নদীর_কথা", "ქართული_მთვარე", "Հայերեն_ճանապարհ",
    "தமிழ்_கடல்", "తెలుగు_వెలుగు", "ಕನ್ನಡ_ಮಳೆ", "മലയാളം_കാറ്റ്",
    "ਪੰਜਾਬੀ_ਸਫ਼ਰ", "ગુજરાતી_વાર્તા", "සිංහල_ගමන", "ລາວ_ດາວ",
    "မြန်မာ_မိုး", "ខ្មែរ_ទន្លេ", "Tiếng_Việt_đêm", "Français_été",
    "Español_sueño", "Português_coração", "Deutsch_Überfahrt", "Türkçe_ışık",
    "Polski_księżyc", "Čeština_příběh", "Slovenčina_rieka", "Slovenščina_noč",
    "Hrvatski_more_č", "Српски_пут", "Български_утро", "Українська_мандрівка",
    "Беларуская_зорка", "Македонски_ветер", "Română_călătorie", "Magyar_árnyék",
    "Íslenska_ævintýri", "Gaeilge_oíche", "Cymraeg_bore_ŵ", "Esperanto_vojaĝo_ĉ",
    "Latviešu_mežs", "Lietuvių_kelias", "Eesti_järv", "Suomi_yö",
    "Svenska_dröm", "Norsk_fjell_å", "Dansk_øeventyr", "Nederlands_wereld_ë",
    "Shqip_ëndërr", "Bosanski_čarolija", "Malti_baħar", "Kiswahili_jua_ñ",
    "Yorùbá_ìrìn", "Māori_whenua", "Gàidhlig_eilean", "Føroyskt_ævintýr",
]

EXTENSIONS = ["txt", "md", "html", "json", "xml", "yaml", "csv", "rtf", "tex", "org"]
OLD_FILES = [
    "오만과_편견.txt", "모비딕_원문.html", "프랑켄슈타인_기록.md", "두_도시_이야기.json",
]


def fetch(url: str) -> bytes:
    if url.startswith("http://"):
        url = "https://" + url.removeprefix("http://")
    result = subprocess.run(
        ["curl", "-L", "--fail", "--silent", "--show-error", "--max-time", "45",
         "--user-agent", USER_AGENT, url],
        check=True,
        capture_output=True,
    )
    return result.stdout


def text_url(book: dict) -> str | None:
    formats = book.get("formats", {})
    for key in ("text/plain; charset=utf-8", "text/plain; charset=us-ascii", "text/plain"):
        if formats.get(key):
            return formats[key]
    return None


def safe_text(raw: bytes) -> str:
    text = raw.decode("utf-8-sig", errors="replace").replace("\x00", "")
    return text.replace("\r\n", "\n").replace("\r", "\n")


def metadata(book: dict, source: str) -> dict:
    authors = ", ".join(author.get("name", "") for author in book.get("authors", []))
    return {
        "ebook_id": book["id"],
        "title": book.get("title", "Untitled"),
        "authors": authors,
        "source": source,
        "license": "Project Gutenberg public-domain ebook",
    }


def render(extension: str, meta: dict, body: str) -> str:
    title = meta["title"]
    author = meta["authors"]
    source = meta["source"]
    if extension == "txt":
        return f"{title}\n{author}\nSource: {source}\n\n{body}"
    if extension == "md":
        return f"# {title}\n\n- Author: {author}\n- Source: {source}\n\n{body}"
    if extension == "html":
        return ("<!doctype html><html><head><meta charset=\"utf-8\"><title>"
                + html.escape(title) + "</title></head><body><h1>" + html.escape(title)
                + "</h1><p>" + html.escape(author) + "</p><pre>" + html.escape(body)
                + "</pre></body></html>\n")
    if extension == "json":
        return json.dumps({**meta, "content": body}, ensure_ascii=False, indent=2)
    if extension == "xml":
        root = ET.Element("article", ebook_id=str(meta["ebook_id"]))
        for key in ("title", "authors", "source", "license"):
            ET.SubElement(root, key).text = str(meta[key])
        ET.SubElement(root, "content").text = body
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + ET.tostring(root, encoding="unicode")
    if extension == "yaml":
        indented = "\n".join("  " + line for line in body.splitlines())
        return (f"ebook_id: {meta['ebook_id']}\ntitle: {json.dumps(title, ensure_ascii=False)}\n"
                f"authors: {json.dumps(author, ensure_ascii=False)}\nsource: {json.dumps(source)}\ncontent: |\n{indented}\n")
    if extension == "csv":
        import io
        stream = io.StringIO()
        writer = csv.writer(stream)
        writer.writerow(["ebook_id", "title", "authors", "source", "line_number", "text"])
        for number, line in enumerate(body.splitlines(), 1):
            writer.writerow([meta["ebook_id"], title, author, source, number, line])
        return stream.getvalue()
    if extension == "rtf":
        escaped = body.replace("\\", "\\\\").replace("{", "\\{").replace("}", "\\}")
        escaped = escaped.replace("\n", "\\par\n")
        return "{\\rtf1\\ansi\\deff0\n{\\b " + title + "}\\par\n" + escaped + "\n}"
    if extension == "tex":
        body = body.replace("\\end{verbatim}", "[end-verbatim]")
        return ("\\documentclass{article}\n\\usepackage[utf8]{inputenc}\n\\begin{document}\n"
                "\\section*{" + title.replace("{", "").replace("}", "") + "}\n"
                "\\begin{verbatim}\n" + body + "\n\\end{verbatim}\n\\end{document}\n")
    if extension == "org":
        return f"#+TITLE: {title}\n#+AUTHOR: {author}\n#+SOURCE: {source}\n\n{body}"
    raise ValueError(extension)


def main() -> None:
    assert len(STEMS) == TARGET_COUNT
    for old_name in OLD_FILES:
        (ROOT / old_name).unlink(missing_ok=True)

    selected = []
    seen_ids = set()
    api_url = "https://gutendex.com/books/?languages=en&copyright=false"
    page_count = 0
    while api_url and len(selected) < TARGET_COUNT and page_count < 20:
        page = json.loads(fetch(api_url).decode("utf-8"))
        page_count += 1
        for book in page.get("results", []):
            if book["id"] in seen_ids:
                continue
            source = text_url(book)
            if not source:
                continue
            seen_ids.add(book["id"])
            try:
                body = safe_text(fetch(source))
            except Exception as error:
                print(f"skip ebook {book['id']}: {error}", flush=True)
                continue
            if len(body) < MIN_CHARS:
                continue
            selected.append((book, source, body))
            print(f"selected {len(selected):02d}/{TARGET_COUNT}: ebook {book['id']} ({len(body)} chars)", flush=True)
            if len(selected) == TARGET_COUNT:
                break
        api_url = page.get("next")

    if len(selected) != TARGET_COUNT:
        raise RuntimeError(f"only found {len(selected)} qualifying distinct texts")

    for index, ((book, source, body), stem) in enumerate(zip(selected, STEMS)):
        extension = EXTENSIONS[index % len(EXTENSIONS)]
        output = ROOT / f"{stem}.{extension}"
        output.write_text(render(extension, metadata(book, source), body), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
