# RUUA: сериалы / озвучки — что сломалось и рецепт лечения

Дата: 2026-08-07  
Контекст: фильмы (HDrezka Anubis v11) уже работали; жалобы на сериалы/мультсериалы (Симпсоны и др.) — пропали озвучки / серии.

Живые пробы: `tools/probe_*.py`, артефакты `docs/research/RUUA_*_PROBE.json`.

---

## Симптом

В каталоге (TMDB) фильмы открываются и играют. Сериалы: мало/ноль серий, одна «озвучка» или пустые линки у UA-провайдеров. Пользовательский эффект — «пропали озвучки».

---

## Корневые причины (по провайдерам)

### 1. HDrezka (v11 → **v12**) — главная поломка сериалов

| Что сломано | Почему |
|---|---|
| Селектор `div#simple-episodes-tabs ul li` → **0 эпизодов** | В DOM узлы — `<a class="b-simple_episode__item">`, не `<li>` |
| Даже после фикса селектора в DOM часто только **последние** сезоны для дефолтной озвучки | Полная сетка S×E приходит AJAX `action=get_episodes` по translator id |
| Пример Симпсоны | `broken_selector_eps=0` → после expand **805** eps, 13 озвучек |

**Рецепт:** правильные селекторы + `expandSeriesEpisodes()` (обход translators → `get_episodes` → merge). Ветка **фильмов не трогалась**.

### 2. Uakino (→ **v27**)

| Что сломано | Почему |
|---|---|
| `findEpisodeData` в каталоге не находит S1… | У эпизодов не было `season`/`episode` |
| Одна страница = один сезон | Соседние сезоны — отдельные посты `*-N-sezon.html` |
| Лейблы | `Серія 12` vs `Серія 1-2` парсились плохо |

**Рецепт:** `UakinoParsing` (S/E из текста/URL) + discovery sibling seasons (search + related BFS).

### 3. Eneyida (→ **v19**)

| Что сломано | Почему |
|---|---|
| `/embed/` часто «Доступ обмежено» | Гео/сеть; вкладка трейлера `?tr=1` бесполезна |
| Фильмы `/vid/` обычно OK | Отдельный путь |

**Рецепт:** `resolveWorkingPlayer` — перебор iframe, пропуск trailer/blocked.

### 4. UAFlix (→ **v20**)

| Что сломано | Почему |
|---|---|
| Поиск пустой | `a.sres-wrap` — href на самом элементе, старый код искал descendant |
| Кириллица в story | Нужен `URLEncoder.encode` |

**Рецепт:** href с `sres-wrap` + encode query.

### 5. KinoVezha

Не регрессия кода: часто **одна** озвучка на сайте. `torDecrypt` ок.

### Не баг

Новые 2026-тайтлы на HDrezka могут быть `film OK` с `translators=0` (только трейлер на сайте) — не CookieJar.

---

## Версии и бандл

| Провайдер | Version |
|---|---|
| HDrezkaProvider | 12 |
| UakinoProvider | 27 |
| EneyidaProvider | 19 |
| UAFlixProvider | 20 |

Ядро: скопировать `.cs3` → `cloudstream-tv-ruua/app/src/main/assets/extensions/`, bump `BUNDLED_EXTENSIONS_MANIFEST_VERSION` (→ **9**), чтобы bootstrap переустановил плагины поверх старых offline/CDN копий.

---

## Деплой на ТВ (X96Q)

1. `assembleStableRelease` → zipalign + `apksigner` (debug keystore).
2. Очистить кэш плагинов/temp на ТВ (`files/plugins`, `/sdcard/Cloudstream3/plugins`, cache).
3. `adb install -r` на `com.lxnhere.cloudstream.ruua`.
4. Force-stop → launch; logcat: HDrezka `load series` / expand, Uakino multi-season, UAFlix search hits.
5. Регресс фильма: Shawshank / Interstellar — `film OK`, translators > 0.

Онлайн CDN может отставать от local push до CI `builds/` — bundled assets + manifest bump закрывают cold-start.
