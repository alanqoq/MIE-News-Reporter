# Graph Report - .  (2026-08-12)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 392 nodes · 829 edges · 21 communities (20 shown, 1 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 50 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `9ca97430`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MienrConfiguration.kt
- MienrPlugin
- AnimeContentService
- CommandAliases
- ContentTestSupport.kt
- NewsContentService
- properties
- DailyPngCache
- MienrPluginTest
- properties
- required
- OnlineImageSmoke
- aliases
- qqbot-plugin-schema.json
- anime
- required
- MienrConfigurationTest
- gradlew
- news

## God Nodes (most connected - your core abstractions)
1. `MienrPlugin` - 30 edges
2. `AnimeContentService` - 30 edges
3. `NewsContentService` - 24 edges
4. `ReportKind` - 21 edges
5. `MienrConfigurationCodec` - 16 edges
6. `MienrPluginTest` - 15 edges
7. `MienrConfiguration` - 12 edges
8. `MienrConfigurationException` - 12 edges
9. `AnimeContentException` - 12 edges
10. `CommandAliases` - 12 edges

## Surprising Connections (you probably didn't know these)
- `AnimeContentService` --calls--> `DailyPngCache`  [INFERRED]
  src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/AnimeContentService.kt → src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/DailyPngCache.kt
- `NewsContentService` --calls--> `ContentRenderException`  [INFERRED]
  src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/NewsContentService.kt → src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/FontResource.kt
- `NewsContentService` --calls--> `DailyPngCache`  [INFERRED]
  src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/NewsContentService.kt → src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/DailyPngCache.kt
- `AnimeContentService` --calls--> `ContentRenderException`  [INFERRED]
  src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/AnimeContentService.kt → src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/FontResource.kt
- `NewsContentException` --inherits--> `ContentServiceException`  [EXTRACTED]
  src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/NewsContentService.kt → src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/FontResource.kt

## Import Cycles
- None detected.

## Communities (21 total, 1 thin omitted)

### Community 0 - "MienrConfiguration.kt"
Cohesion: 0.10
Nodes (22): IllegalArgumentException, CommandConfiguration, formatHour(), immutableSortedMap(), immutableSortedSet(), MienrConfiguration, MienrConfigurationCodec, MienrConfigurationException (+14 more)

### Community 1 - "MienrPlugin"
Cohesion: 0.11
Nodes (17): BotPlugin, BotPluginFactory, InboundMessage, MessageSendOptions, MessageTarget, PluginRuntimeContext, PluginTask, DailyImageProvider (+9 more)

### Community 2 - "AnimeContentService"
Cohesion: 0.12
Nodes (24): Graphics2D, RuntimeException, AnimeContentException, AnimeContentService, AnimeScheduleItem, DatedRequest, decodeAnimeUtf8(), BufferedImage (+16 more)

### Community 3 - "CommandAliases"
Cohesion: 0.11
Nodes (17): CommandName, ANIME, GET_ANIME, GET_NEWS, HELP, NEWS, TIME_ANIME, TIME_NEWS (+9 more)

### Community 4 - "ContentTestSupport.kt"
Cohesion: 0.12
Nodes (18): Color, AnimeContentServiceTest, URI, await(), bundledTestFont(), ContentTestAnchor, decodePng(), fixturePng() (+10 more)

### Community 5 - "NewsContentService"
Cohesion: 0.17
Nodes (14): FontMetrics, DatedRequest, decodeUtf8(), ByteArray, DatedRequest, Font, PluginHttpResponse, R (+6 more)

### Community 6 - "properties"
Cohesion: 0.10
Nodes (25): properties, items, pattern, type, maxLength, minLength, type, items (+17 more)

### Community 7 - "DailyPngCache"
Cohesion: 0.14
Nodes (8): ByteArray, writeFileAtomically(), AutomaticDispatchLedger, DailyPngCache, hasPngSignature(), isReadablePng(), ByteArray, AutomaticDispatchLedgerTest

### Community 8 - "MienrPluginTest"
Cohesion: 0.26
Nodes (6): GroupMemberRole, PluginTestContext, ByteArray, DailyImageProvider, PluginEvent, MienrPluginTest

### Community 9 - "properties"
Cohesion: 0.15
Nodes (13): properties, $ref, $ref, $ref, $ref, getanime, getnews, help (+5 more)

### Community 10 - "required"
Cohesion: 0.20
Nodes (11): anime, getanime, getnews, help, mienr, news, timeanime, timenews (+3 more)

### Community 11 - "OnlineImageSmoke"
Cohesion: 0.33
Nodes (5): JdkPluginHttpClient, PluginHttpClient, PluginHttpRequest, PluginHttpResponse, OnlineImageSmoke

### Community 12 - "aliases"
Cohesion: 0.22
Nodes (9): aliases, additionalProperties, type, additionalProperties, properties, required, type, aliases (+1 more)

### Community 13 - "qqbot-plugin-schema.json"
Cohesion: 0.22
Nodes (8): additionalProperties, maxItems, type, uniqueItems, $defs, commandAliasList, $schema, type

### Community 14 - "anime"
Cohesion: 0.22
Nodes (9): additionalProperties, $ref, type, properties, anime, timeZone, maxLength, minLength (+1 more)

### Community 15 - "required"
Cohesion: 0.48
Nodes (7): defaultTime, disabledMessage, enabledGroups, failureMessage, groupTimes, required, required

### Community 17 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 18 - "news"
Cohesion: 0.50
Nodes (4): additionalProperties, $ref, type, news

## Knowledge Gaps
- **57 isolated node(s):** `Help`, `ContentTestAnchor`, `ANIME`, `NEWS`, `additionalProperties` (+52 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ReportKind` connect `MienrConfiguration.kt` to `MienrPlugin`, `CommandAliases`, `DailyPngCache`?**
  _High betweenness centrality (0.243) - this node is a cross-community bridge._
- **Why does `AnimeContentService` connect `AnimeContentService` to `MienrPlugin`, `OnlineImageSmoke`, `ContentTestSupport.kt`, `DailyPngCache`?**
  _High betweenness centrality (0.169) - this node is a cross-community bridge._
- **Why does `MienrPlugin` connect `MienrPlugin` to `MienrPluginTest`?**
  _High betweenness centrality (0.161) - this node is a cross-community bridge._
- **Are the 5 inferred relationships involving `MienrPlugin` (e.g. with `.`configured aliases execute through the plugin entry point`()` and `.`disabled get commands quote the matching configured reminder`()`) actually correct?**
  _`MienrPlugin` has 5 INFERRED edges - model-reasoned connections that need verification._
- **Are the 6 inferred relationships involving `AnimeContentService` (e.g. with `DailyPngCache` and `ContentRenderException`) actually correct?**
  _`AnimeContentService` has 6 INFERRED edges - model-reasoned connections that need verification._
- **Are the 6 inferred relationships involving `NewsContentService` (e.g. with `DailyPngCache` and `ContentRenderException`) actually correct?**
  _`NewsContentService` has 6 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Help`, `ContentTestAnchor`, `ANIME` to the rest of the system?**
  _57 weakly-connected nodes found - possible documentation gaps or missing edges._