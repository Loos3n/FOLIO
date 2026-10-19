"""Makes the files the UI check imports: a PDF, a book that tries to run scripts, and a fake book."""
import sys
import zipfile

out = sys.argv[1] if len(sys.argv) > 1 else "."


def pdf(path, pages):
    """A plain PDF, one text page per entry in pages (title, lines), without any library."""
    objects = []

    def add(body):
        objects.append(body)
        return len(objects)

    font = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    bold = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>")
    pages_id = len(objects) + 1 + 2 * len(pages)
    kids = []
    for title, lines in pages:
        text = ["BT", "/F2 28 Tf", "72 760 Td", "(%s) Tj" % title, "/F1 13 Tf", "0 -40 Td", "18 TL"]
        for line in lines:
            text.append("(%s) '" % line.replace("(", "[").replace(")", "]"))
        text.append("ET")
        # A grey rule near the bottom, so a zoomed page has something to show at the edges.
        text += ["0.6 g", "72 80 451 2 re f"]
        stream = "\n".join(text).encode("latin-1")
        content = add(b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream")
        kids.append(add(b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 595 842] /Contents %d 0 R "
                        b"/Resources << /Font << /F1 %d 0 R /F2 %d 0 R >> >> >>" % (pages_id, content, font, bold)))
    assert add(b"<< /Type /Pages /Kids [%s] /Count %d >>" % (b" ".join(b"%d 0 R" % k for k in kids), len(kids))) == pages_id
    catalog = add(b"<< /Type /Catalog /Pages %d 0 R >>" % pages_id)
    data = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, body in enumerate(objects, 1):
        offsets.append(len(data))
        data += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(data)
    data += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objects) + 1)
    for o in offsets:
        data += b"%010d 00000 n \n" % o
    data += b"trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objects) + 1, catalog, xref)
    open(path, "wb").write(bytes(data))


para = [
    "It is a truth universally acknowledged, that a single man in possession",
    "of a good fortune, must be in want of a wife. However little known the",
    "feelings or views of such a man may be on his first entering a",
    "neighbourhood, this truth is so well fixed in the minds of the",
    "surrounding families, that he is considered as the rightful property",
    "of some one or other of their daughters.",
    "",
]
pdf(f"{out}/Sample_Report.pdf", [("Page %d" % (n + 1), para * 5) for n in range(8)])

# An EPUB whose chapter tries everything a hostile book might: inline and linked scripts,
# event-handler attributes, a redirect to a website, and loading from the internet.
CHAPTER = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter 1</title>
<meta http-equiv="refresh" content="4;url=https://example.com/"/>
<script>document.addEventListener('DOMContentLoaded', function () {
  document.body.innerHTML = '<h1 style="color:red">SCRIPT RAN</h1>';
  try { FolioBridge.onTap('menu'); } catch (e) {}
});</script>
<script src="evil.js"></script>
</head><body>
<h1>Chapter 1</h1>
<p>If you can read this sentence, the scripts inside this book were blocked.</p>
<p><img src="https://example.com/tracker.png" alt="" onerror="document.body.style.background='red'"/></p>
<p><a href="javascript:document.body.innerHTML='LINK SCRIPT RAN'">A link that tries to run a script.</a></p>
<p>Plain text continues here, and the reader should behave normally.</p>
</body></html>"""

with zipfile.ZipFile(f"{out}/hostile.epub", "w") as z:
    z.writestr(zipfile.ZipInfo("mimetype"), "application/epub+zip")
    z.writestr("META-INF/container.xml", """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
    z.writestr("OEBPS/content.opf", """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">hostile-test</dc:identifier>
<dc:title>The Hostile Book</dc:title><dc:creator>Test Author</dc:creator><dc:language>en</dc:language></metadata>
<manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
<item id="js" href="evil.js" media-type="text/javascript"/></manifest>
<spine><itemref idref="c1"/></spine></package>""")
    z.writestr("OEBPS/c1.xhtml", CHAPTER)
    z.writestr("OEBPS/evil.js", "document.documentElement.innerHTML = '<h1>LINKED SCRIPT RAN</h1>';")

# Not a book at all, just named like one.
open(f"{out}/not-a-book.epub", "wb").write(b"MZ\x90\x00\x03\x00\x00\x00 this is a program, not a book " * 20)
