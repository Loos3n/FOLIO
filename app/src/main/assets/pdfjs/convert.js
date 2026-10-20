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
    // How much text lies over a box (counting only the part of each line inside it), and how
    // many lines run through it and out the other side: body text, not a picture's labels.
    var runsThrough = function (l, r) {
      var w = Math.max(1, l.x1 - l.x0);
      var out = Math.max(0, r[0] - l.x0) + Math.max(0, l.x1 - r[2]);
      return out > Math.max(20, w * 0.15) && l.chars >= 15;
    };
    var overlapping = function (r) {
      return lines.filter(function (l) {
        var mid = l.y + l.size * 0.3; // the middle of the letters, above the baseline
        return mid >= r[1] && mid <= r[3] && Math.min(l.x1, r[2] + 2) - Math.max(l.x0, r[0] - 2) > 0;
      });
    };
    /**
     * The box with body text trimmed off its top and bottom edges (a drawing's frame often
     * reaches into the paragraph next to it), or null when body text runs through the
     * middle of it, so it isn't a picture at all, or it has too much text over it.
     */
    var trimmed = function (b, maxChars) {
      var r = b.slice();
      for (var pass = 0; pass < 20; pass++) {
        var cut = false;
        var h = r[3] - r[1];
        overlapping(r).forEach(function (l) {
          if (cut || !runsThrough(l, r)) return;
          var mid = l.y + l.size * 0.3;
          if (mid < r[1] + h * 0.35) { r[1] = l.y + l.size * 0.95; cut = true; }
          else if (mid > r[3] - h * 0.35) { r[3] = l.y - l.size * 0.3; cut = true; }
        });
        if (!cut) break;
      }
      var w = r[2] - r[0], h2 = r[3] - r[1];
      if (w < 40 || h2 < 30) return null;
      if (h2 > w * 4 || w > h2 * 12) return null; // a sliver, not a figure
      var chars = 0;
      var over = overlapping(r);
      for (var i = 0; i < over.length; i++) {
        var l = over[i];
        if (runsThrough(l, r)) return null;
        var inside = Math.min(l.x1, r[2]) - Math.max(l.x0, r[0]);
        chars += l.chars * Math.min(1, Math.max(0, inside) / Math.max(1, l.x1 - l.x0));
      }
      return chars <= maxChars ? r : null;
    };
    var found = [];
    images.map(inPage).forEach(function (b) {
      var w = b[2] - b[0], h = b[3] - b[1];
      if (w < 40 || h < 30) return; // ornaments, bullets, rules
      // Not a page picture with text laid over it (a scan's text layer, a background).
      var t = trimmed(b, 300);
      if (t) found.push(t);
    });
    cluster(shapes.map(inPage).filter(function (b) { return b[2] > b[0] || b[3] > b[1]; }), 6).forEach(function (g) {
      var b = g.r, w = b[2] - b[0], h = b[3] - b[1];
      if (g.n < 3 || w < 60 || h < 40) return; // lines under headings, boxes, single frames
      if (w * h > pageW * pageH * 0.85) return; // a page background
      var t = trimmed(b, 200); // not a box around text (a sidebar, a table of words)
      if (t) found.push(t);
    });
    // A picture inside another (an image within a drawn frame) counts once.
    found = cluster(found, 2).map(function (g) { return g.r; }).map(function (b) { return trimmed(b, 300); }).filter(Boolean);
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
    // Pictures met in the middle of a paragraph (often one running over a page break) wait
    // until it ends, so they never split a sentence.
    var waiting = [];
    var flush = function () {
      if (para) { blocks.push(para); para = null; }
      if (waiting.length) { blocks.push.apply(blocks, waiting); waiting = []; }
    };

    pages.forEach(function (p, pageIndex) {
      if (outline[pageIndex]) { flush(); blocks.push({ type: 'break', title: outline[pageIndex], page: pageIndex }); }
      var prev = null;
      var pics = (p.pictures || []).slice().sort(function (a, b) { return a.top - b.top; });
      var placePictures = function (before) {
        while (pics.length && (before == null || pics[0].top < before)) {
          var pic = pics.shift();
          // How much of the text column the picture spans, so it's shown at the same share of the screen.
          var column = left != null && right != null && right - left > 50 ? right - left : p.width;
          var share = Math.round(Math.min(100, ((pic.rect[2] - pic.rect[0]) / column) * 100));
          var img = { type: 'img', page: pageIndex, rect: pic.rect, width: share >= 70 ? 100 : Math.max(25, share), text: '' };
          if (para && para.type === 'p') waiting.push(img);
          else { flush(); blocks.push(img); }
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

  var CHAPTER_WORDS = /^(chapter|part|book|section|prologue|epilogue|introduction|preface|foreword|afterword|conclusion|appendix|acknowledg(e)?ments|notes|index|glossary|bibliography|about the author)\b/i;
  var SMALL_WORDS = /^(a|an|and|as|at|but|by|for|in|of|on|or|the|to|vs?|with)$/;

  /** A contents-list title: no dot leaders or page numbers, and not shouting in capitals. */
  function cleanTitle(t) {
    t = String(t || '').replace(/\s+/g, ' ').replace(/[\s.·…_]{2,}\d+\s*$/, '').replace(/\s+\d{1,4}$/, function (m) {
      return /^(chapter|part|book|section|appendix)\b/i.test(t) ? m : '';
    }).trim();
    var letters = t.replace(/[^A-Za-z]/g, '');
    if (letters.length >= 4 && letters === letters.toUpperCase()) {
      t = t.toLowerCase().replace(/[a-z][a-z']*/g, function (w, i, all) {
        var opens = i === 0 || /[:—–-]\s*$/.test(all.slice(0, i));
        return !opens && SMALL_WORDS.test(w) ? w : w.charAt(0).toUpperCase() + w.slice(1);
      });
    }
    if (t.length > 80) t = t.slice(0, 77).trim() + '…';
    return t;
  }

  /** A heading that's only a label, like "Chapter 3" or "7", so the line after it is the real name. */
  function labelOnly(t) {
    return t.length < 24 && (/^(chapter|part|book)\s+[\w-]+\.?$/i.test(t) || /^[\divxlc]+\.?$/i.test(t) ||
      /^(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty)$/i.test(t));
  }

  /** Splits blocks into chapters: at the PDF's own sections, else at headings, else every few pages. */
  function chapters(blocks, body, pageCount) {
    var out = [];
    var cur = null;
    var hasBreaks = blocks.some(function (b) { return b.type === 'break'; });
    var start = function (title, page, fromOutline) {
      cur = { title: title, html: [], chars: 0, page: page, last: page, pictures: 0, outline: !!fromOutline, joinable: false };
      out.push(cur);
    };
    blocks.forEach(function (b) {
      if (b.type === 'break') { start(b.title, b.page, true); return; }
      if (!cur) start(null, b.page);
      if (!hasBreaks) {
        var big = b.type === 'h' && b.size > body * 1.3 && b.text.length < 100;
        var named = b.type === 'h' && b.text.length < 100 && CHAPTER_WORDS.test(b.text);
        if ((named && cur.chars > 0) || (big && cur.chars > 1500) || (cur.chars > 60000 && b.type === 'p')) start(null, b.page);
      }
      cur.last = b.page;
      if (b.type === 'img') {
        var r = b.rect.map(function (v) { return Math.round(v * 10) / 10; });
        cur.html.push('<img data-pdf="' + b.page + ',' + r.join(',') + ',' + (b.width || 100) + '"/>');
        cur.pictures++;
        return;
      }
      if (b.type === 'h') {
        // "Chapter 3" followed by its name makes one title: "Chapter 3: The Name".
        if (cur.chars === 0 && cur.joinable && b.text.length < 100) cur.title += ': ' + b.text;
        else if (!cur.title) cur.title = b.text;
        cur.joinable = cur.chars === 0 && labelOnly(b.text);
        var level = b.size > body * 1.6 ? 'h1' : b.size > body * 1.3 ? 'h2' : 'h3';
        cur.html.push('<' + level + '>' + b.html + '</' + level + '>');
        if (!hasBreaks) return; // headings don't count as text, so a run of them stays one title
      } else {
        cur.joinable = false;
        cur.html.push(b.centred ? '<p class="c">' + b.html + '</p>' : '<p>' + b.html + '</p>');
      }
      cur.chars += b.text.length;
    });
    out = out.filter(function (c) { return c.html.length; });
    // A page or two with hardly any text (a part title, a quote) joins the chapter after it.
    if (!hasBreaks) {
      for (var i = out.length - 2; i >= 0; i--) {
        var c = out[i];
        if (c.chars < 250 && !c.pictures) {
          var next = out[i + 1];
          next.html = c.html.concat(next.html);
          next.page = c.page;
          if (!next.title) next.title = c.title;
          next.chars += c.chars;
          out.splice(i, 1);
        }
      }
    }
    out.forEach(function (c, i) {
      c.title = cleanTitle(c.title);
      if (!c.title) {
        var end = Math.max(c.page, c.last);
        c.title = end > c.page ? 'Pages ' + (c.page + 1) + '–' + (end + 1) : 'Page ' + (c.page + 1);
      }
    });
    return out;
  }

  async function outlinePages(doc) {
    var map = {};
    var outline = await doc.getOutline().catch(function () { return null; });
    if (!outline || !outline.length) return map;
    // A contents list with one or two entries at the top (often just the book's own title)
    // is more useful a level down.
    if (outline.length < 3) {
      var flat = [];
      outline.forEach(function (o) { flat.push(o); (o.items || []).forEach(function (c) { flat.push(c); }); });
      outline = flat;
    }
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
