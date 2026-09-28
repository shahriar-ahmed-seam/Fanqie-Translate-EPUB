package com.example.scraper

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest

/**
 * A small novel website served to the real WebView. It has the things that break scrapers:
 * menus, a sidebar chapter list, ads inside the text, a hidden login popup, comments, live
 * "translation" that swaps Chinese for English, late-loading text, a different layout on one
 * chapter, and a last chapter without a next link.
 */
class FakeNovelSite(private val lastChapter: Int = 5) : Dispatcher() {

    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path ?: "/"
        requests += path
        if (path.startsWith("/locked/")) return html(LOGIN_WALL)
        val n = Regex("^/novel/chapter-(\\d+)$").find(path)?.groupValues?.get(1)?.toIntOrNull()
        if (n == null || n < 1 || n > lastChapter) {
            return html(NOT_FOUND).setResponseCode(404)
        }
        return html(chapter(n, lastChapter))
    }

    private fun html(body: String) = MockResponse()
        .setHeader("Content-Type", "text/html; charset=utf-8")
        .setBody(body)

    companion object {
        const val PARAGRAPHS = 8

        fun englishParagraph(n: Int, p: Int) =
            "Chapter $n, paragraph $p. Lin Feng walked through the quiet courtyard, thinking about what the old master had said, and the wind carried the smell of rain."

        private fun chineseParagraph(p: Int) = "林枫走过安静的庭院，心里想着老师父说过的话，风里带着雨的味道。第${p}段。"

        /** What TomatoMTL shows instead of the chapter when you're not logged in. */
        private const val LOGIN_WALL = "<!DOCTYPE html><html><head><title>Chapter 1 - Locked Novel</title></head><body>" +
            "<nav class=\"navbar\"><a href=\"/\">Home</a> <a href=\"/user/login\">Login</a></nav>" +
            "<div id=\"noidung\" class=\"chapter-content\"><div class=\"text-center py-5\"><h2>Login Required</h2>" +
            "<p>You need to log in to read chapter content.</p>" +
            "<p><small>Because the website has been maliciously crawled/scraped, we had to require login to view content.</small></p>" +
            "<a href=\"/user/login\">Log In</a></div></div></body></html>"

        private const val NOT_FOUND = "<!DOCTYPE html><html><head><title>404 Not Found</title></head><body><h1>404 Not Found</h1><p>Page not found.</p></body></html>"

        private fun nav(n: Int, last: Int): String {
            val prev = if (n > 1) "<a href=\"/novel/chapter-${n - 1}\">Previous Chapter</a>" else "<span>Previous Chapter</span>"
            val next = if (n < last) "<a class=\"btn next\" href=\"/novel/chapter-${n + 1}\">Next Chapter</a>" else "<span class=\"disabled\">Next Chapter</span>"
            return "<div class=\"chapter-nav\">$prev <a href=\"/novel\">Table of Contents</a> $next</div>"
        }

        fun chapter(n: Int, last: Int = 5): String {
            val english = (1..PARAGRAPHS).joinToString("") { "<p>${englishParagraph(n, it)}</p>" }
            val sidebar = (1..last).joinToString("") { "<li><a href=\"/novel/chapter-$it\">Chapter $it</a></li>" }
            val content = when (n) {
                // Live translation: Chinese first, English appears paragraph by paragraph after 3 s.
                2 -> """
                    <div id="chapter-content">${(1..PARAGRAPHS).joinToString("") { "<p>${chineseParagraph(it)}</p>" }}
                    <p>Join our Discord for updates!</p></div>
                    <script>
                      var en = ${(1..PARAGRAPHS).joinToString(",", "[", "]") { "\"" + englishParagraph(n, it) + "\"" }};
                      setTimeout(function () {
                        var ps = document.querySelectorAll('#chapter-content p');
                        var i = 0;
                        var t = setInterval(function () {
                          if (i >= en.length) { clearInterval(t); return; }
                          ps[i].textContent = en[i]; i++;
                        }, 250);
                      }, 3000);
                    </script>
                """
                // Text arrives late (loaded by script after 2.5 s).
                3 -> """
                    <div id="chapter-content"><p>Loading chapter…</p></div>
                    <script>
                      setTimeout(function () {
                        document.getElementById('chapter-content').innerHTML = ${"\"" + english.replace("\"", "\\\"") + "\""} +
                          '<div class="ads-inline">Advertisement: read more stories at fakenovels.example</div>';
                      }, 2500);
                    </script>
                """
                // A different layout: plain text separated by <br>, and rel=next instead of the usual button.
                4 -> "<div class=\"txt\" id=\"booktxt\">" +
                    (1..PARAGRAPHS).joinToString("<br><br>") { englishParagraph(n, it) } +
                    "</div><a rel=\"next\" href=\"/novel/chapter-5\">→</a>"
                else -> "<div id=\"chapter-content\">$english<div class=\"ads-inline\">Advertisement: read more stories at fakenovels.example</div><p>Join our Discord for updates!</p></div>"
            }
            return """
                <!DOCTYPE html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta property="og:site_name" content="FakeNovels">
                <title>Chapter $n - The Test Novel - FakeNovels</title></head>
                <body>
                <header class="site-header"><nav class="navbar"><a href="/">Home</a> <a href="/novels">Novels</a> <a href="/login">Login</a></nav></header>
                <div class="breadcrumb"><a href="/">Home</a> › <a href="/novel">The Test Novel</a> › Chapter $n</div>
                <div id="login-modal" style="display:none"><form><input type="password" name="pw"><button>Log in</button></form></div>
                <aside class="sidebar"><div class="ads">Buy cheap stuff now! Advertisement</div><ul>$sidebar</ul></aside>
                <main>
                  <h1 class="chapter-title">Chapter $n: Title $n</h1>
                  ${if (n != 4) nav(n, last) else ""}
                  $content
                  ${if (n != 4) nav(n, last) else ""}
                </main>
                <section class="comments"><h3>Comments</h3>
                  <p>Great chapter! I loved it so much, thanks for translating this one for us.</p>
                  <p>Can't wait for the next part, the story keeps getting better every week.</p>
                </section>
                <footer class="footer">© FakeNovels. All rights reserved.</footer>
                </body></html>
            """.trimIndent()
        }
    }
}
