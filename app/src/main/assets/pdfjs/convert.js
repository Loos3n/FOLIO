/*
 * Turns a PDF's text back into flowing paragraphs, headings and chapters, so Folio can
 * show it like any other book. Runs in a hidden WebView in the app (and under Node for
 * testing). PDFs only store where each piece of text sits on a page, so this works out
 * lines from positions, then paragraphs from indents and gaps, drops running headers and
 * page numbers, and rejoins words hyphenated across lines.
 */
(function (root) {
  'use strict';

  function esc(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  /** The value covering the most weight (e.g. the body text size, weighted by characters). */
  function mode(pairs, bucket) {
    var counts = {};
    var best = null, bestN = -1;
    pairs.forEach(function (p) {
      var k = Math.round(p[0] / bucket) * bucket;
      counts[k] = (counts[k] || 0) + p[1];
      if (counts[k] > bestN) { bestN = counts[k]; best = k; }
    });
    return best;
  }


  // ------------------------------------------------------------------ pictures

  /** Applies [m] and then [ctm] (PDF matrices [a b c d e f]). */
  function compose(m, ctm) {
    return [
      m[0] * ctm[0] + m[1] * ctm[2], m[0] * ctm[1] + m[1] * ctm[3],
      m[2] * ctm[0] + m[3] * ctm[2], m[2] * ctm[1] + m[3] * ctm[3],
      m[4] * ctm[0] + m[5] * ctm[2] + ctm[4], m[4] * ctm[1] + m[5] * ctm[3] + ctm[5],
    ];
  }

  function boxOf(ctm, r) {
    var xs = [], ys = [];
    [[r[0], r[1]], [r[2], r[1]], [r[0], r[3]], [r[2], r[3]]].forEach(function (p) {
      xs.push(ctm[0] * p[0] + ctm[2] * p[1] + ctm[4]);
      ys.push(ctm[1] * p[0] + ctm[3] * p[1] + ctm[5]);
    });
    return [Math.min.apply(null, xs), Math.min.apply(null, ys), Math.max.apply(null, xs), Math.max.apply(null, ys)];
  }

  function near(a, b, pad) {
    return a[0] - pad <= b[2] && b[0] - pad <= a[2] && a[1] - pad <= b[3] && b[1] - pad <= a[3];
  }

  /** Merges boxes that touch or nearly touch, counting how many shapes went into each. */
  function cluster(boxes, pad) {
    var groups = boxes.map(function (b) { return { r: b.slice(), n: 1 }; });
    var merged = true;
    while (merged) {
      merged = false;
      for (var i = 0; i < groups.length; i++) {
        for (var j = i + 1; j < groups.length; j++) {
          if (near(groups[i].r, groups[j].r, pad)) {
            var a = groups[i].r, b = groups[j].r;
            groups[i].r = [Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[2], b[2]), Math.max(a[3], b[3])];
            groups[i].n += groups[j].n;
            groups.splice(j, 1);
            merged = true;
            j--;
          }
        }
      }
    }
    return groups;
  }

  /**
   * Where the pictures are on a page: photos and drawings (images), and charts or diagrams
   * drawn with lines and shapes. Returned in page space, as [x0, y0, x1, y1].
   */
  async function picturesOf(page, OPS, view, lines) {
    var list = await page.getOperatorList();
    var ctm = [1, 0, 0, 1, 0, 0];
    var stack = [];
    var images = [], shapes = [];
    var pageW = view[2] - view[0], pageH = view[3] - view[1];
    for (var i = 0; i < list.fnArray.length; i++) {
      var fn = list.fnArray[i], a = list.argsArray[i];
      if (fn === OPS.save) stack.push(ctm);
      else if (fn === OPS.restore) ctm = stack.pop() || ctm;
      else if (fn === OPS.transform) ctm = compose(a, ctm);
      else if (fn === OPS.paintFormXObjectBegin) { stack.push(ctm); if (a && a[0]) ctm = compose(a[0], ctm); }
      else if (fn === OPS.paintFormXObjectEnd) ctm = stack.pop() || ctm;
      else if (fn === OPS.paintImageXObject || fn === OPS.paintInlineImageXObject || fn === OPS.paintImageMaskXObject) {
        images.push(boxOf(ctm, [0, 0, 1, 1]));
      } else if (fn === OPS.constructPath && a && a[2] && isFinite(a[2][0])) {
        shapes.push(boxOf(ctm, a[2]));
      }
    }
    var inPage = function (b) {
      return [Math.max(b[0], view[0]), Math.max(b[1], view[1]), Math.min(b[2], view[2]), Math.min(b[3], view[3])];
    };
    var textIn = function (r) {
      var n = 0;
      lines.forEach(function (l) {
        var cx = (l.x0 + l.x1) / 2;
        if (cx > r[0] - 2 && cx < r[2] + 2 && l.y > r[1] - 2 && l.y < r[3] + 2) n += l.chars;
      });
      return n;
    };
    var found = [];
    images.map(inPage).forEach(function (b) {
      var w = b[2] - b[0], h = b[3] - b[1];
      if (w < 40 || h < 30) return; // ornaments, bullets, rules
      if (textIn(b) > 300) return; // a page photo with text laid over it (a scan's text layer)
      found.push(b);
    });
    cluster(shapes.map(inPage).filter(function (b) { return b[2] > b[0] || b[3] > b[1]; }), 6).forEach(function (g) {
      var b = g.r, w = b[2] - b[0], h = b[3] - b[1];
      if (g.n < 3 || w < 60 || h < 40) return; // lines under headings, boxes, single frames
      if (w * h > pageW * pageH * 0.85) return; // a page background
      if (textIn(b) > 200) return; // a box around text (a sidebar, a table of words)
      found.push(b);
    });
    // A picture inside another (an image within a drawn frame) counts once.
    found = cluster(found, 2).map(function (g) { return g.r; });
    return found;
  }

  /** Lines on one page, from pdf.js text items. */
  function linesOf(items, pageHeight) {
    var lines = [];
    var cur = null;
    items.forEach(function (it) {
      if (typeof it.str !== 'string') return;
      var t = it.transform;
      var size = Math.hypot(t[2], t[3]) || Math.abs(t[3]) || 10;
      var x = t[4], y = t[5];
      var w = it.width || 0;
      if (!it.str.length) {
        if (it.hasEOL && cur) cur.eol = true;
        return;
      }
      var sameLine = cur && !cur.eol && Math.abs(y - cur.y) < Math.max(size, cur.size) * 0.45;
      // Smaller text a little above the line is a superscript (a note number).
      var raised = cur && size < cur.size * 0.85 && y > cur.y && y - cur.y < cur.size * 0.7 && x >= cur.x1 - 1;
      if (cur && (sameLine || raised) && x > cur.x0 - size) {
        var gap = x - cur.x1;
        var text = it.str;
        if (raised && /^[\d*†‡§a-z]{1,3}$/i.test(text.trim())) {
          cur.parts.push({ sup: text.trim() });
        } else {
          if (gap > size * 0.18 && !/\s$/.test(cur.lastText) && !/^\s/.test(text)) cur.parts.push({ text: ' ' });
          cur.parts.push({ text: text });
          cur.chars += text.length;
          cur.sizes.push([size, text.length]);
        }
        cur.lastText = text;
        cur.x1 = Math.max(cur.x1, x + w);
        if (it.hasEOL) cur.eol = true;
        return;
      }
      cur = {
        x0: x, x1: x + w, y: y, size: size, top: pageHeight - y,
        parts: [{ text: it.str }], chars: it.str.length, sizes: [[size, it.str.length]],
        lastText: it.str, eol: !!it.hasEOL, fonts: {},
      };
      lines.push(cur);
    });
    lines.forEach(function (l) {
      l.size = mode(l.sizes, 0.25) || l.size;
      l.text = l.parts.map(function (p) { return p.text || ''; }).join('').replace(/\s+/g, ' ').trim();
      l.html = l.parts.map(function (p) { return p.sup ? '<sup>' + esc(p.sup) + '</sup>' : esc(p.text); }).join('')
        .replace(/\s+/g, ' ').trim();
    });
    return lines.filter(function (l) { return l.text.length || /<sup>/.test(l.html); });
  }

  /** Text that repeats at the top or bottom of many pages (running heads, page numbers). */
  function furniture(pages, body) {
    var seen = {};
    var key = function (l) { return l.text.replace(/\d+/g, '#').replace(/\s+/g, ' ').toLowerCase(); };
    pages.forEach(function (p) {
      var keys = {};
      p.lines.forEach(function (l) {
        if (l.top < p.height * 0.1 || l.top > p.height * 0.9) keys[key(l)] = 1;
      });
      Object.keys(keys).forEach(function (k) { seen[k] = (seen[k] || 0) + 1; });
    });
    var limit = Math.max(3, pages.length * 0.2);
    return function (l, p) {
      var edge = l.top < p.height * 0.1 || l.top > p.height * 0.9;
      // Running heads and page numbers are never larger than the body text; titles are.
      if (!edge || l.size > body * 1.15) return false;
      if (/^(page\s*)?[\divxlc]+(\s*(of|\/)\s*\d+)?$/i.test(l.text)) return true;
      return (seen[key(l)] || 0) >= limit && l.text.length < 120;
    };
  }

  function endsSentence(s) {
    return /[.!?:;…"”’)\]]$/.test(s);
  }

  /**
   * Builds the book from page text. [pages] are {lines, height, width}; [outline] maps page
   * index to section title where the PDF has a table of contents.
   */
  function layout(pages, outline) {
    var all = [];
    pages.forEach(function (p) { p.lines.forEach(function (l) { all.push([l.size, l.chars]); }); });
    var body = mode(all, 0.5) || 10;
    var isFurniture = furniture(pages, body);

    // Typical left edge, right edge and line spacing of body text.
    var lefts = [], rights = [], gaps = [];
    pages.forEach(function (p) {
      var prev = null;
      p.lines.forEach(function (l) {
        if (Math.abs(l.size - body) > body * 0.15) { prev = null; return; }
        lefts.push([l.x0, l.chars]);
        rights.push([l.x1, l.chars]);
        if (prev) {
          var g = prev.y - l.y;
          if (g > 0 && g < body * 3) gaps.push([g, 1]);
        }
        prev = l;
      });
    });
    var left = mode(lefts, 2);
    var right = mode(rights, 4);
    var spacing = mode(gaps, 0.5) || body * 1.3;

    var blocks = [];
    var para = null;
    var flush = function () { if (para) { blocks.push(para); para = null; } };

    pages.forEach(function (p, pageIndex) {
      if (outline[pageIndex]) { flush(); blocks.push({ type: 'break', title: outline[pageIndex], page: pageIndex }); }
      var prev = null;
      var pics = (p.pictures || []).slice().sort(function (a, b) { return a.top - b.top; });
      var placePictures = function (before) {
        while (pics.length && (before == null || pics[0].top < before)) {
          flush();
          blocks.push({ type: 'img', page: pageIndex, rect: pics.shift().rect, text: '' });
          prev = null;
        }
      };
      p.lines.forEach(function (l) {
        placePictures(l.top);
        if (isFurniture(l, p)) return;
        var big = l.size > body * 1.18;
        var centred = left != null && right != null && l.x0 > left + body * 2 && Math.abs((l.x0 - left) - (right - l.x1)) < body * 2;
        var shortCaps = l.text.length < 70 && l.text === l.text.toUpperCase() && /[A-Z]{3}/.test(l.text);
        var heading = big || (centred && shortCaps) || (shortCaps && prev && prev.y - l.y > spacing * 1.8);
        if (heading) {
          if (para && para.type === 'h' && prev && prev.y - l.y < l.size * 1.8 && Math.abs(para.size - l.size) < 1) {
            para.html += ' ' + l.html;
            para.text += ' ' + l.text;
          } else {
            flush();
            para = { type: 'h', html: l.html, text: l.text, size: l.size, page: pageIndex };
          }
          prev = l;
          return;
        }
        var start = false;
        if (!para || para.type !== 'p') start = true;
        else if (prev) {
          var gap = prev.y - l.y;
          if (gap > spacing * 1.45 || gap < -body) start = true;
        }
        var indented = left != null && l.x0 > left + body * 0.8 && !centred;
        if (!start && indented) start = true;
        // A new page continues the paragraph unless the last line looked like an ending.
        if (!start && !prev && para && para.type === 'p') {
          start = indented || (endsSentence(para.text) && para.lastShort);
        }
        if (!start && prev && endsSentence(prev.text) && right != null && prev.x1 < right - body * 3) start = true;
        if (!start && centred && l.text.length < 60) start = true;
        if (start) {
          flush();
          para = { type: 'p', html: l.html, text: l.text, centred: centred && l.text.length < 60, page: pageIndex };
        } else {
          // Rejoin a word split across lines ("improve-" + "ment").
          if (/[a-z]-$/.test(para.text) && /^[a-z]/.test(l.text)) {
            // Decided once the whole text is known (see joinHyphens).
            para.html = para.html.replace(/-$/, SPLIT) + l.html;
            para.text = para.text.replace(/-$/, SPLIT) + l.text;
          } else if (/[—–/]$/.test(para.text)) {
            para.html += l.html;
            para.text += l.text;
          } else {
            para.html += ' ' + l.html;
            para.text += ' ' + l.text;
          }
        }
        para.lastShort = right != null && l.x1 < right - body * 3;
        prev = l;
      });
      placePictures(null);
    });
    flush();
    joinHyphens(blocks);
    return { blocks: blocks, body: body };
  }

  var SPLIT = '\u0001';

  /**
   * A word broken across lines lost or kept its hyphen depending on whether it really has
   * one: "self-improvement" stays hyphenated if the book writes it that way elsewhere, and
   * "improve-ment" (only there to break the line) becomes "improvement".
   */
  function joinHyphens(blocks) {
    var words = {};
    blocks.forEach(function (b) {
      (b.text || '').replace(/[A-Za-z]+(?:-[A-Za-z]+)+/g, function (w) { words[w.toLowerCase()] = 1; return w; });
    });
    var fix = function (s) {
      return s.replace(/([A-Za-z]+)\u0001([A-Za-z]+)/g, function (_, a, b) {
        return words[(a + '-' + b).toLowerCase()] ? a + '-' + b : a + b;
      }).replace(/\u0001/g, '');
    };
    blocks.forEach(function (b) {
      if (b.text) b.text = fix(b.text);
      if (b.html) b.html = fix(b.html);
    });
  }

  /** Splits blocks into chapters: at the PDF's own sections, else at big headings, else every few pages. */
  function chapters(blocks, body, pageCount) {
    var out = [];
    var cur = null;
    var hasBreaks = blocks.some(function (b) { return b.type === 'break'; });
    var start = function (title, page) {
      cur = { title: title, html: [], chars: 0, page: page };
      out.push(cur);
    };
    blocks.forEach(function (b) {
      if (b.type === 'break') { start(b.title, b.page); return; }
      if (!cur) start(null, b.page);
      if (!hasBreaks) {
        var bigHeading = b.type === 'h' && b.size > body * 1.3 && b.text.length < 100;
        if ((bigHeading && cur.chars > 1500) || (cur.chars > 60000 && b.type === 'p')) start(bigHeading ? b.text : null, b.page);
      }
      if (b.type === 'img') {
        var r = b.rect.map(function (v) { return Math.round(v * 10) / 10; });
        cur.html.push('<img data-pdf="' + b.page + ',' + r.join(',') + '"/>');
        return;
      }
      if (b.type === 'h') {
        if (!cur.title) cur.title = b.text;
        var level = b.size > body * 1.6 ? 'h1' : b.size > body * 1.3 ? 'h2' : 'h3';
        cur.html.push('<' + level + '>' + b.html + '</' + level + '>');
      } else {
        cur.html.push(b.centred ? '<p class="c">' + b.html + '</p>' : '<p>' + b.html + '</p>');
      }
      cur.chars += b.text.length;
    });
    out = out.filter(function (c) { return c.html.length; });
    out.forEach(function (c, i) {
      if (!c.title) c.title = 'Section ' + (i + 1);
      if (c.title.length > 80) c.title = c.title.slice(0, 77) + '…';
    });
    return out;
  }

  async function outlinePages(doc) {
    var map = {};
    var outline = await doc.getOutline().catch(function () { return null; });
    if (!outline || !outline.length) return map;
    var found = 0;
    for (var i = 0; i < outline.length; i++) {
      var item = outline[i];
      try {
        var dest = item.dest;
        if (typeof dest === 'string') dest = await doc.getDestination(dest);
        if (!dest || !dest[0]) continue;
        var index = typeof dest[0] === 'number' ? dest[0] : await doc.getPageIndex(dest[0]);
        if (map[index] == null && item.title) { map[index] = item.title.trim(); found++; }
      } catch (e) { /* a broken entry is skipped */ }
    }
    return found >= 2 ? map : {};
  }

  /** Reads the PDF in [data] and returns {title, author, chapters, scanned, pageCount}. */
  async function convert(pdfjsLib, data, opts) {
    opts = opts || {};
    var task = pdfjsLib.getDocument({
      data: data,
      cMapUrl: opts.cMapUrl,
      cMapPacked: true,
      isEvalSupported: false,
      disableFontFace: true,
      useSystemFonts: false,
    });
    var doc = await task.promise;
    var meta = await doc.getMetadata().catch(function () { return {}; });
    var info = (meta && meta.info) || {};
    var pages = [];
    var chars = 0;
    for (var i = 1; i <= doc.numPages; i++) {
      var page = await doc.getPage(i);
      var vp = page.getViewport({ scale: 1 });
      var tc = await page.getTextContent();
      var lines = linesOf(tc.items, vp.height);
      lines.forEach(function (l) { chars += l.chars; });
      var view = vp.viewBox;
      var pics = [];
      if (pdfjsLib.OPS) {
        try { pics = await picturesOf(page, pdfjsLib.OPS, view, lines); } catch (e) { pics = []; }
      }
      // Labels inside a chart belong to the picture, not the text.
      if (pics.length) {
        lines = lines.filter(function (l) {
          var cx = (l.x0 + l.x1) / 2;
          return !pics.some(function (r) { return cx > r[0] - 2 && cx < r[2] + 2 && l.y > r[1] - 2 && l.y < r[3] + 2; });
        });
      }
      pages.push({
        lines: lines, height: vp.height, width: vp.width,
        pictures: pics.map(function (r) {
          // Kept as distances from the page's top-left corner, as Android draws pages.
          return { top: view[3] - r[3], rect: [r[0] - view[0], view[3] - r[3], r[2] - view[0], view[3] - r[1]] };
        }),
      });
      page.cleanup();
      if (opts.onProgress) opts.onProgress(i, doc.numPages);
    }
    var scanned = chars < doc.numPages * 80;
    var result = {
      title: (info.Title || '').trim(),
      author: (info.Author || '').trim(),
      pageCount: doc.numPages,
      scanned: scanned,
      chapters: [],
    };
    if (!scanned) {
      if (opts.onWorking) opts.onWorking();
      // A damaged table of contents mustn't hold everything up.
      var outline = await Promise.race([
        outlinePages(doc),
        new Promise(function (done) { setTimeout(function () { done({}); }, 15000); }),
      ]);
      if (opts.onWorking) opts.onWorking();
      var laid = layout(pages, outline);
      result.chapters = chapters(laid.blocks, laid.body, doc.numPages).map(function (c) {
        return { title: c.title, page: c.page, html: c.html.join('\n') };
      });
    }
    doc.destroy().catch(function () {});
    return result;
  }

  var api = { convert: convert, layout: layout, linesOf: linesOf };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.FolioPdf = api;
})(this);
