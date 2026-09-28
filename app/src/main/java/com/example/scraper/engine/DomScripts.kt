package com.example.scraper.engine

import com.example.scraper.model.WebsiteStrategy
import org.json.JSONObject

/**
 * JavaScript evaluated inside the page. Both scripts return `JSON.stringify({...})` with the shape
 * [PageAnalyzer.parse] expects:
 *
 * `url, canonicalUrl, novelTitle, chapterTitle, paragraphs[], linkDensity, elementFound,
 *  isLoginWall, isChallenge, isErrorPage, contentSelector, titleSelector, nextSelector,
 *  nextCandidates[{url,text,score,reason,selector}], atBottom`
 *
 * Text is read with `innerText` from the live element, so CSS-hidden junk is skipped and `<br>`
 * separated layouts become separate paragraphs. The page itself is never modified.
 */
object DomScripts {

    const val MARKER_DISCOVER = "/*scraper:discover*/"
    const val MARKER_EXTRACT = "/*scraper:extract*/"

    /** Scores the whole page to find the reading container, title and next link. */
    fun discover(): String = build(MARKER_DISCOVER, "discover", "", "", "")

    /** Reads the page with the selectors learned for this site. */
    fun extract(strategy: WebsiteStrategy): String =
        build(MARKER_EXTRACT, "extract", strategy.contentSelector, strategy.titleSelector, strategy.nextSelector)

    const val MARKER_CLICK = "/*scraper:click*/"

    /** Clicks the element matching [selector]; returns "clicked", "missing" or "error". */
    fun click(selector: String): String =
        MARKER_CLICK + "(function(){try{var el=document.querySelector(" + JSONObject.quote(selector) + ");" +
            "if(!el)return 'missing';if(el.scrollIntoView)el.scrollIntoView({block:'center'});el.click();return 'clicked';}" +
            "catch(e){return 'error';}})();"

    /** Scrolls one screen down (or back to the top at the end) so lazily rendered text loads. */
    const val SCROLL_STEP: String = "(function(){try{var h=Math.max(document.documentElement.scrollHeight,document.body?document.body.scrollHeight:0);" +
        "var y=window.scrollY+window.innerHeight;if(y>=h-20){window.scrollTo(0,0);return 'top';}" +
        "window.scrollBy(0,Math.max(300,Math.floor(window.innerHeight*0.9)));return 'down';}catch(e){return 'error';}})();"

    private fun build(marker: String, mode: String, content: String, title: String, next: String): String =
        marker + "(function(){var MODE=" + JSONObject.quote(mode) +
            ";var CFG={content:" + JSONObject.quote(content) +
            ",title:" + JSONObject.quote(title) +
            ",next:" + JSONObject.quote(next) + "};" + BODY + "})();"

    private val BODY = """
try {
  var NEG = {ad:1,ads:1,adsbygoogle:1,advert:1,advertisement:1,adv:1,banner:1,sponsor:1,sponsored:1,promo:1,promotion:1,
    share:1,sharing:1,social:1,related:1,recommend:1,recommended:1,comment:1,comments:1,footer:1,nav:1,navbar:1,
    navigation:1,menu:1,sidebar:1,breadcrumb:1,breadcrumbs:1,pagination:1,pager:1,toolbar:1,popup:1,modal:1,cookie:1};
  var STRONG_NEG = {ad:1,ads:1,adsbygoogle:1,advert:1,advertisement:1,adv:1,banner:1,sponsor:1,sponsored:1,promo:1,
    share:1,social:1,related:1,recommend:1,comment:1,comments:1,pagination:1,pager:1,breadcrumb:1,nav:1,navbar:1,toolbar:1};
  var POS = {chapter:1,content:1,reading:1,reader:1,read:1,entry:1,article:1,novel:1,txt:1,text:1,story:1,prose:1,
    chaptercontent:1,booktext:1,htmlcontent:1,chr:1};
  var NEXT_EXACT = /^(next|next chapter|next ch\.?|next page|next episode|continue reading|下一章|下一页|下一頁|下一节|下章|›|»|→|>|>>|next\s*[›»→>]+|[›»→>]+\s*next)$/i;
  var NEXT_LABEL = /\bnext\s*(chapter|ch\b|page|episode)|下一[章页頁节]/i;
  var PREV_RE = /\b(prev|previous)\b|上一[章页頁节]|‹|«|←/i;
  var TOC_RE = /\b(index|contents|table of contents|toc|catalog|chapter list|all chapters)\b|目录|目錄/i;

  function norm(s) { return String(s || '').replace(/\s+/g, ' ').trim(); }
  function tokens(el) {
    var cls = (typeof el.className === 'string') ? el.className : ((el.getAttribute && el.getAttribute('class')) || '');
    return (cls + ' ' + (el.id || '')).toLowerCase().split(/[\s_\-]+/).filter(function (t) { return t.length > 0; });
  }
  function hasTok(el, set) { var t = tokens(el); for (var i = 0; i < t.length; i++) { if (set[t[i]]) return true; } return false; }
  function textLen(el) { return norm(el.textContent).length; }
  function linkDensity(el) {
    var total = textLen(el);
    if (total === 0) return 1;
    var n = 0, links = el.getElementsByTagName('a');
    for (var i = 0; i < links.length; i++) n += norm(links[i].textContent).length;
    return Math.min(1, n / total);
  }
  function isVisible(el) {
    if (!el || !el.getBoundingClientRect) return false;
    var st = window.getComputedStyle(el);
    if (!st || st.display === 'none' || st.visibility === 'hidden') return false;
    var r = el.getBoundingClientRect();
    return r.width > 1 && r.height > 1;
  }
  function cssEscape(s) {
    if (window.CSS && CSS.escape) return CSS.escape(s);
    return String(s).replace(/[^a-zA-Z0-9_\-]/g, function (c) { return '\\' + c; });
  }
  function stableToken(t) {
    return !!t && t.length <= 40 && !/\d{3,}/.test(t) &&
      !/^(active|selected|current|show|shown|open|opened|hide|hidden|visible|fade|in|lazy|loaded|loading|night|dark|light|day)$/i.test(t) &&
      !/^(css|sc|jsx|svelte|emotion)-/i.test(t);
  }
  function uniqueSelector(el) {
    if (!el || el.nodeType !== 1) return '';
    try {
      if (el.id && stableToken(el.id)) {
        var s1 = '#' + cssEscape(el.id);
        if (document.querySelectorAll(s1).length === 1) return s1;
      }
      var tag = el.tagName.toLowerCase();
      var cls = Array.prototype.filter.call(el.classList || [], stableToken).slice(0, 3);
      if (cls.length) {
        var s2 = tag + '.' + cls.map(cssEscape).join('.');
        if (document.querySelectorAll(s2).length === 1) return s2;
      }
      var parts = [], cur = el, depth = 0;
      while (cur && cur.nodeType === 1 && cur !== document.body && cur !== document.documentElement && depth < 8) {
        if (cur.id && stableToken(cur.id) && document.querySelectorAll('#' + cssEscape(cur.id)).length === 1) {
          parts.unshift('#' + cssEscape(cur.id));
          break;
        }
        var part = cur.tagName.toLowerCase();
        var c = Array.prototype.filter.call(cur.classList || [], stableToken).slice(0, 2);
        if (c.length) part += '.' + c.map(cssEscape).join('.');
        var parent = cur.parentElement;
        if (parent) {
          var tagName = cur.tagName;
          var same = Array.prototype.filter.call(parent.children, function (x) { return x.tagName === tagName; });
          if (same.length > 1) part += ':nth-of-type(' + (same.indexOf(cur) + 1) + ')';
        }
        parts.unshift(part);
        cur = parent;
        depth++;
      }
      var sel = parts.join(' > ');
      if (sel && document.querySelector(sel) === el) return sel;
    } catch (e) {}
    return '';
  }

  function blockScore(len, text) {
    var marks = (text.match(/[,.，。!?！？;；:：]/g) || []).length;
    return 1 + Math.min(len, 1000) / 100 + Math.min(marks, 10) * 0.3;
  }
  function discoverContent() {
    var list = [], score = new Map();
    function add(el, s) {
      if (!el || el === document.documentElement || el === document.head) return;
      if (!score.has(el)) { score.set(el, 0); list.push(el); }
      score.set(el, score.get(el) + s);
    }
    var ps = document.getElementsByTagName('p');
    for (var i = 0; i < ps.length && i < 5000; i++) {
      var pt = norm(ps[i].textContent);
      if (pt.length < 25) continue;
      var bs = blockScore(pt.length, pt);
      var pp = ps[i].parentElement;
      add(pp, bs);
      if (pp) add(pp.parentElement, bs / 2);
    }
    var boxes = document.querySelectorAll('div, article, section, main, td, pre, span');
    for (var j = 0; j < boxes.length && j < 6000; j++) {
      var box = boxes[j], direct = 0, brs = 0, directText = '';
      for (var ch = box.firstChild; ch; ch = ch.nextSibling) {
        if (ch.nodeType === 3) {
          var tv = norm(ch.nodeValue);
          if (tv) { direct += tv.length; if (directText.length < 400) directText += tv; }
        } else if (ch.nodeType === 1) {
          var tg = ch.tagName;
          if (tg === 'BR') brs++;
          else if (tg === 'FONT' || tg === 'EM' || tg === 'STRONG' || tg === 'I' || tg === 'B' || tg === 'Q') direct += textLen(ch);
        }
      }
      if (direct < 25) continue;
      if (brs >= 2 || direct >= 400) {
        add(box, direct / 100 + brs * 0.5);
        add(box.parentElement, blockScore(direct, directText) / 2);
      } else if (box.tagName !== 'SPAN') {
        var bsc = blockScore(direct, directText);
        add(box.parentElement, bsc);
        if (box.parentElement) add(box.parentElement.parentElement, bsc / 2);
      }
    }
    var KNOWN = ['#chapter-content', '.chapter-content', '#chaptercontent', '#chapterContent', '.chapter-body', '#content',
      '.read-content', '.reading-content', '#reading-content', '.entry-content', '.novel-content', '.chapter-text',
      '.text-content', '#txt', '.txt', '#booktxt', '#htmlContent', '.chapter-inner', '.chr-c', '#chr-content', '.reader-content'];
    for (var k = 0; k < KNOWN.length; k++) {
      try { var kel = document.querySelector(KNOWN[k]); if (kel && textLen(kel) >= 200) add(kel, 5); } catch (e) {}
    }
    var best = null, bestScore = 0;
    for (var m = 0; m < list.length; m++) {
      var el = list[m];
      if (el === document.body || el.tagName === 'SPAN') continue;
      if (textLen(el) < 100) continue;
      var sc = score.get(el) * (1 - linkDensity(el));
      if (hasTok(el, POS)) sc += 15;
      if (hasTok(el, NEG)) sc -= 30;
      if (el.tagName === 'ARTICLE') sc += 5;
      if (sc > bestScore) { bestScore = sc; best = el; }
    }
    return best;
  }
  function extractParagraphs(el) {
    var raw = (typeof el.innerText === 'string') ? el.innerText : el.textContent;
    var lines = String(raw || '').split(/\n+/);
    var junk = {};
    var bad = el.querySelectorAll('a, button, select, script, style, noscript, iframe, ins, [role="button"]');
    for (var i = 0; i < bad.length && i < 800; i++) {
      var bt = norm(bad[i].innerText || bad[i].textContent);
      if (bt && bt.length < 120) junk[bt] = 1;
    }
    var totalLen = textLen(el);
    var all = el.getElementsByTagName('*');
    for (var j = 0; j < all.length && j < 4000; j++) {
      var d = all[j];
      if (!hasTok(d, STRONG_NEG)) continue;
      if (textLen(d) > totalLen * 0.4) continue;
      var dl = String(d.innerText || d.textContent || '').split(/\n+/);
      for (var q = 0; q < dl.length; q++) { var jl = norm(dl[q]); if (jl) junk[jl] = 1; }
    }
    var out = [];
    for (var k = 0; k < lines.length; k++) {
      var line = norm(lines[k]);
      if (!line || junk[line]) continue;
      out.push(line);
      if (out.length >= 5000) break;
    }
    return out;
  }
  function discoverTitle(contentEl) {
    var cands = document.querySelectorAll('h1, h2, h3, [class*="title"], [id*="title"]');
    var best = null, bestScore = -1;
    for (var i = 0; i < cands.length && i < 300; i++) {
      var el = cands[i];
      var t = norm(el.innerText || el.textContent);
      if (t.length < 2 || t.length > 150) continue;
      if (el.closest && el.closest('nav, footer, [class*="breadcrumb"]')) continue;
      var s = 0, tag = el.tagName;
      if (tag === 'H1') s += 30; else if (tag === 'H2') s += 20; else if (tag === 'H3') s += 10;
      if (/chapter|episode|\bch\.?\s*\d|第.{1,8}[章节回]/i.test(t)) s += 25;
      var tk = tokens(el);
      for (var j = 0; j < tk.length; j++) { if (tk[j] === 'title' || tk[j] === 'chapter' || tk[j] === 'heading') { s += 10; break; } }
      if (contentEl) {
        if (contentEl.contains(el)) s += 10;
        else if (contentEl.compareDocumentPosition(el) & 2) s += 15;
        else s -= 20;
      }
      if (s > bestScore) { bestScore = s; best = el; }
    }
    return best;
  }
  function titleParts() {
    return String(document.title || '').split(/\s[-–—|•]\s|_|\|/).map(norm).filter(function (s) { return s.length > 0; });
  }
  function findNovelTitle(chapterTitle) {
    var m = document.querySelector('meta[property="og:novel:book_name"], meta[name="og:novel:book_name"], meta[property="og:novel:title"], meta[property="books:title"], meta[name="novel-title"], meta[name="book-title"]');
    if (m && m.content && norm(m.content)) return norm(m.content);
    var bc = document.querySelectorAll('.breadcrumb a, .breadcrumbs a, [class*="breadcrumb"] a, nav[aria-label*="readcrumb"] a');
    for (var i = bc.length - 1; i >= 0; i--) {
      var t = norm(bc[i].innerText || bc[i].textContent);
      if (t && t.length > 1 && t.length < 120 && t !== chapterTitle && !/^(home|homepage|首页|novels?|library)$/i.test(t)) return t;
    }
    var siteMeta = document.querySelector('meta[property="og:site_name"]');
    var site = siteMeta && siteMeta.content ? norm(siteMeta.content).toLowerCase() : '';
    var parts = titleParts();
    for (var j = 0; j < parts.length; j++) {
      var p = parts[j];
      if (p === chapterTitle || (site && p.toLowerCase() === site)) continue;
      if (/chapter\s*\d|\bch\.?\s*\d|第.{1,8}[章节回]/i.test(p)) continue;
      if (j === parts.length - 1 && parts.length > 2) continue;
      return p;
    }
    return '';
  }
  function collectNext(nextSel) {
    var out = [], index = {};
    var here = location.href.split('#')[0];
    function hrefOf(el) {
      var h = el.getAttribute('href') || el.getAttribute('data-href') || el.getAttribute('data-url') || '';
      h = String(h).trim();
      if (!h || h.charAt(0) === '#' || /^(javascript|mailto|tel):/i.test(h)) return '';
      try { return new URL(h, document.baseURI).href; } catch (e) { return ''; }
    }
    function push(el, score, reason, text) {
      var url = hrefOf(el);
      if (!url) return;
      var key = url.split('#')[0];
      if (key === here) return;
      if (index[key] !== undefined) {
        var ex = out[index[key]];
        if (score > ex.score) { ex.score = score; ex.reason = reason; ex.el = el; }
        return;
      }
      index[key] = out.length;
      out.push({url: url, text: norm(text).slice(0, 80), score: score, reason: reason, el: el, selector: ''});
    }
    if (nextSel) {
      try { var n = document.querySelector(nextSel); if (n) push(n, 95, 'saved', n.innerText || n.textContent); } catch (e) {}
    }
    var rel = document.querySelectorAll('a[rel~="next"], link[rel~="next"]');
    for (var i = 0; i < rel.length; i++) push(rel[i], 90, 'rel', rel[i].innerText || rel[i].textContent || '');
    var anchors = document.querySelectorAll('a[href], [data-href]');
    for (var j = 0; j < anchors.length && j < 4000; j++) {
      var a = anchors[j];
      var text = norm(a.innerText || a.textContent);
      var label = (text + ' ' + (a.getAttribute('title') || '') + ' ' + (a.getAttribute('aria-label') || '')).toLowerCase();
      if (PREV_RE.test(label) || TOC_RE.test(label)) continue;
      var tk = ' ' + tokens(a).join(' ') + ' ';
      var s = 0, why = '';
      if (NEXT_EXACT.test(text)) { s = 80; why = 'text'; }
      else if (NEXT_LABEL.test(label)) { s = 70; why = 'label'; }
      else if (tk.indexOf(' next ') >= 0 || tk.indexOf(' nextchapter ') >= 0) { s = 60; why = 'class'; }
      if (s > 0) push(a, s, why, text);
    }
    out.sort(function (x, y) { return y.score - x.score; });
    var top = out.slice(0, 10);
    for (var k = 0; k < top.length; k++) {
      if (k < 3) top[k].selector = uniqueSelector(top[k].el);
      delete top[k].el;
    }
    return top;
  }
  // A visible "Next" control without a usable URL (button or javascript: link). Clicked as a last resort.
  function findNextButton() {
    var els = document.querySelectorAll('button, [role="button"], a:not([href]), a[href^="javascript"], a[href="#"], [onclick]');
    for (var i = 0; i < els.length && i < 2000; i++) {
      var b = els[i];
      var bt = norm(b.innerText || b.textContent);
      var extra = (b.getAttribute('title') || '') + ' ' + (b.getAttribute('aria-label') || '');
      if (!bt && !norm(extra)) continue;
      var bl = (bt + ' ' + extra).toLowerCase();
      if (PREV_RE.test(bl) || TOC_RE.test(bl)) continue;
      if (!(NEXT_EXACT.test(bt) || NEXT_LABEL.test(bl))) continue;
      if (b.disabled || /\bdisabled\b/i.test(typeof b.className === 'string' ? b.className : '')) continue;
      if (!isVisible(b)) continue;
      var sel = uniqueSelector(b);
      if (sel) return sel;
    }
    return '';
  }

  var bodyText = document.body ? String(document.body.innerText || document.body.textContent || '') : '';
  var head = bodyText.slice(0, 1500);
  var docTitle = String(document.title || '');
  var isChallenge = /just a moment|checking your browser|verify you are human|attention required|ddos-guard|please wait while we verify/i.test(docTitle + ' ' + head.slice(0, 400)) ||
    !!document.querySelector('#challenge-form, #cf-challenge-running, .cf-turnstile, #turnstile-wrapper, iframe[src*="challenges.cloudflare.com"]');
  var isErrorPage = (/\b(404|403|410|500|502|503|504)\b/.test(docTitle) && /not found|forbidden|error|unavailable|gone|bad gateway/i.test(docTitle)) ||
    /^(page not found|not found|404)$/i.test(norm(docTitle)) ||
    (bodyText.length < 2000 && /404 not found|page not found|page doesn.t exist|bad gateway|service unavailable|internal server error/i.test(head));

  var out = {url: location.href, canonicalUrl: '', novelTitle: '', chapterTitle: '', paragraphs: [], linkDensity: 0,
    elementFound: false, isLoginWall: false, isChallenge: isChallenge, isErrorPage: isErrorPage, contentSelector: '',
    titleSelector: '', nextSelector: '', nextCandidates: [], atBottom: true};
  var canon = document.querySelector('link[rel="canonical"]');
  if (canon && canon.href) out.canonicalUrl = canon.href;

  var contentEl = null;
  if (MODE === 'extract' && CFG.content) {
    try { contentEl = document.querySelector(CFG.content); } catch (e) { contentEl = null; }
  } else {
    contentEl = discoverContent();
  }
  if (contentEl) {
    out.elementFound = true;
    out.paragraphs = extractParagraphs(contentEl);
    out.linkDensity = linkDensity(contentEl);
    out.contentSelector = (MODE === 'extract' && CFG.content) ? CFG.content : uniqueSelector(contentEl);
  }

  var titleEl = null;
  if (CFG.title) { try { titleEl = document.querySelector(CFG.title); } catch (e) { titleEl = null; } }
  var learnedTitle = !!titleEl;
  if (!titleEl) titleEl = discoverTitle(contentEl);
  var chapterTitle = titleEl ? norm(titleEl.innerText || titleEl.textContent) : '';
  if (!chapterTitle || chapterTitle.length > 150) {
    var tp = titleParts();
    chapterTitle = tp.length ? tp[0] : '';
  }
  out.chapterTitle = chapterTitle.slice(0, 200);
  out.titleSelector = learnedTitle ? CFG.title : (titleEl ? uniqueSelector(titleEl) : '');
  out.novelTitle = findNovelTitle(out.chapterTitle);

  var chars = 0;
  for (var pi = 0; pi < out.paragraphs.length; pi++) chars += out.paragraphs[pi].length;
  var pw = document.querySelectorAll('input[type="password"]');
  var visiblePw = false;
  for (var wi = 0; wi < pw.length; wi++) { if (isVisible(pw[wi])) { visiblePw = true; break; } }
  // Sites like TomatoMTL replace the chapter text with a short "log in to read" box.
  var LOGIN_TEXT = /\b(log ?in|sign ?in)\b[^.!?]{0,40}\bto (read|continue|view|access|unlock)|\b(login|log in|sign[- ]?in) required\b|\byou (need|have|must) to (log|sign) ?in\b|\bplease (log|sign) ?in\b|\bmembers? only\b|登录后|请登录|需要登录|登入後/i;
  var probe = contentEl ? out.paragraphs.join(' ').slice(0, 400) : (bodyText.length < 3000 ? head : '');
  var loginLink = !!(contentEl && contentEl.querySelector('a[href*="login"], a[href*="signin"], a[href*="sign-in"], a[href*="sign_in"]'));
  out.isLoginWall = (visiblePw && chars < 600) ||
    (LOGIN_TEXT.test(probe) && (chars < 800 || (loginLink && chars < 1500)));

  out.nextCandidates = collectNext(CFG.next);
  if (CFG.next) out.nextSelector = CFG.next;
  else if (out.nextCandidates.length) out.nextSelector = out.nextCandidates[0].selector || '';
  out.nextClickSelector = out.nextCandidates.length ? '' : findNextButton();

  var docH = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
  out.atBottom = (window.scrollY + window.innerHeight) >= docH - 20;
  return JSON.stringify(out);
} catch (err) {
  return JSON.stringify({error: String((err && err.message) || err), url: location.href});
}
""".trimIndent()
}
