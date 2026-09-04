# Graph Report - .  (2026-09-05)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 400 nodes · 792 edges · 18 communities (17 shown, 1 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 52 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `f4a66cc7`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MienrConfiguration.kt
- AnimeContentService
- anime
- MienrPlugin
- CommandAliases
- ContentTestSupport.kt
- properties
- NewsContentService
- MienrPluginTest
- .create
- qqbot-plugin-schema.json
- DailyPngCache
- MIE News Reporter
- OnlineImageSmoke
- MienrConfigurationTest
- gradlew

## God Nodes (most connected - your core abstractions)
1. `MienrPlugin` - 29 edges
2. `AnimeContentService` - 26 edges
3. `ReportKind` - 21 edges
4. `NewsContentService` - 20 edges
5. `MienrConfigurationCodec` - 16 edges
6. `MienrPluginTest` - 14 edges
7. `CommandAliases` - 12 edges
8. `MienrConfiguration` - 12 edges
9. `MienrConfigurationException` - 12 edges
10. `AnimeContentException` - 12 edges

## Surprising Connections (you probably didn't know these)
- `bundledTestFont()` --references--> `FontResource`  [EXTRACTED]
  src/test/kotlin/com/mieai/qqbot/plugin/mienr/content/ContentTestSupport.kt → src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/FontResource.kt
- `NewsContentException` --inherits--> `ContentServiceException`  [EXTRACTED]
  src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/NewsContentService.kt → src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/FontResource.kt
- `AnimeContentException` --inherits--> `ContentServiceException`  [EXTRACTED]
  src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/AnimeContentService.kt → src/main/kotlin/com/mieai/qqbot/plugin/mienr/content/FontResource.kt

## Import Cycles
- None detected.

## Communities (18 total, 1 thin omitted)

### Community 0 - "MienrConfiguration.kt"
Cohesion: 0.10
Nodes (23): IllegalArgumentException, CommandConfiguration, defaults(), formatHour(), immutableSortedMap(), immutableSortedSet(), MienrConfiguration, MienrConfigurationCodec (+15 more)

### Community 1 - "AnimeContentService"
Cohesion: 0.11
Nodes (25): Graphics2D, RuntimeException, AnimeContentException, AnimeContentService, AnimeScheduleItem, DatedRequest, decodeAnimeUtf8(), BufferedImage (+17 more)

### Community 2 - "anime"
Cohesion: 0.06
Nodes (44): aliases, defaultTime, disabledMessage, enabledGroups, failureMessage, groupTimes, additionalProperties, properties (+36 more)

### Community 3 - "MienrPlugin"
Cohesion: 0.11
Nodes (14): InboundMessage, MessageSendOptions, MessageTarget, PluginTask, AutomaticDispatchLedger, ReportKind, ANIME, NEWS (+6 more)

### Community 4 - "CommandAliases"
Cohesion: 0.10
Nodes (17): CommandName, ANIME, GET_ANIME, GET_NEWS, HELP, NEWS, TIME_ANIME, TIME_NEWS (+9 more)

### Community 5 - "ContentTestSupport.kt"
Cohesion: 0.12
Nodes (18): Color, AnimeContentServiceTest, await(), bundledTestFont(), completed(), ContentTestAnchor, decodePng(), fixturePng() (+10 more)

### Community 6 - "properties"
Cohesion: 0.07
Nodes (28): anime, getanime, getnews, help, mienr, news, timeanime, timenews (+20 more)

### Community 7 - "NewsContentService"
Cohesion: 0.17
Nodes (13): FontMetrics, DatedRequest, decodeUtf8(), ByteArray, DatedRequest, Font, PluginHttpResponse, R (+5 more)

### Community 8 - "MienrPluginTest"
Cohesion: 0.24
Nodes (7): GroupMemberRole, PluginTestContext, ByteArray, DailyImageProvider, PluginEvent, MienrPluginTest, pngBytes()

### Community 9 - ".create"
Cohesion: 0.17
Nodes (9): BotPlugin, BotPluginFactory, PluginRuntimeContext, DailyImageProvider, ByteArray, DailyImageProvider, MienrPluginFactory, DailyImageProvider (+1 more)

### Community 10 - "qqbot-plugin-schema.json"
Cohesion: 0.15
Nodes (14): additionalProperties, items, maxItems, type, uniqueItems, $defs, commandAliasList, items (+6 more)

### Community 11 - "DailyPngCache"
Cohesion: 0.27
Nodes (6): ByteArray, writeFileAtomically(), DailyPngCache, hasPngSignature(), isReadablePng(), ByteArray

### Community 12 - "MIE News Reporter"
Cohesion: 0.20
Nodes (9): License, MIE News Reporter, 功能, 图片与数据源, 在线样例, 安装, 指令, 构建与验证 (+1 more)

### Community 13 - "OnlineImageSmoke"
Cohesion: 0.27
Nodes (5): JdkPluginHttpClient, PluginHttpClient, PluginHttpRequest, PluginHttpResponse, OnlineImageSmoke

### Community 15 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **65 isolated node(s):** `Help`, `NEWS`, `TIME_NEWS`, `GET_NEWS`, `ANIME` (+60 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MienrPlugin` connect `MienrPlugin` to `MienrPluginTest`, `.create`?**
  _High betweenness centrality (0.299) - this node is a cross-community bridge._
- **Why does `ReportKind` connect `MienrPlugin` to `MienrConfiguration.kt`, `CommandAliases`?**
  _High betweenness centrality (0.288) - this node is a cross-community bridge._
- **Why does `AnimeContentService` connect `AnimeContentService` to `.create`, `OnlineImageSmoke`, `ContentTestSupport.kt`?**
  _High betweenness centrality (0.174) - this node is a cross-community bridge._
- **Are the 5 inferred relationships involving `MienrPlugin` (e.g. with `.`configured aliases execute through the plugin entry point`()` and `.`disabled get commands quote the matching configured reminder`()`) actually correct?**
  _`MienrPlugin` has 5 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `AnimeContentService` (e.g. with `.create()` and `.fetchesTimelineAndCoversThenPersistsOnlyCurrentAnimeImage()`) actually correct?**
  _`AnimeContentService` has 5 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `NewsContentService` (e.g. with `.create()` and `.fetchesRendersAndPersistsOnlyCurrentNewsImage()`) actually correct?**
  _`NewsContentService` has 5 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Help`, `NEWS`, `TIME_NEWS` to the rest of the system?**
  _65 weakly-connected nodes found - possible documentation gaps or missing edges._