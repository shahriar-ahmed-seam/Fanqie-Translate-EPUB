What's fixed
Scraper and Tomato
- The app no longer crashes when you open the Scraper browser after using the Tomato tab.
- Looking up a novel by ID is reliable: it retries while the downloader engine is starting, asks Fanqie's website when the engine can't answer, and shows which ID it looked up. IDs typed with other digits (Bengali, full-width) or copied with hidden characters work, and a number that isn't a valid ID gets a clear message instead of unrelated search results.

Translation
- Chapters no longer come out as [""]: empty answers from Google are retried instead of being saved.
- Paragraphs, words and chapter titles that Google left in Chinese are translated again until they are English.
- Chunks that fail are retried automatically before a book is marked as failed.
- Exported EPUBs: chapters whose file names have Chinese or special characters are now translated, and the table of contents is fully in English.
- Books you translated with earlier versions are fixed automatically: chapters showing [""] are translated again the first time the app opens with internet, and Chinese left in other chapters is translated in the background while no other book is translating. Your books stay readable meanwhile.

Installing: this version installs over 1.0.11 and newer and keeps your library. Versions 1.0.10 or older must be uninstalled first (different signing key).
