# Graph Report - .  (2026-09-05)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 402 nodes · 801 edges · 22 communities (21 shown, 1 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 53 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `a8c2c937`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- MienrConfiguration.kt
- MienrPlugin
- AnimeContentService
- CommandAliases
- properties
- ContentTestSupport.kt
- NewsContentService
- MienrPluginTest
- AutomaticDispatchLedger
- properties
- MIE News Reporter
- OnlineImageSmoke
- qqbot-plugin-schema.json
- news
- required
- MienrConfigurationTest
- items
- gradlew
- anime
- disabledMessage

## God Nodes (most connected - your core abstractions)
1. `MienrPlugin` - 30 edges
2. `AnimeContentService` - 26 edges
3. `ReportKind` - 21 edges
4. `NewsContentService` - 20 edges
5. `MienrConfigurationCodec` - 16 edges
6. `MienrPluginTest` - 15 edges
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

## Communities (22 total, 1 thin omitted)

### Community 0 - "MienrConfiguration.kt"
Cohesion: 0.10
Nodes (23): IllegalArgumentException, CommandConfiguration, defaults(), formatHour(), immutableSortedMap(), immutableSortedSet(), MienrConfiguration, MienrConfigurationCodec (+15 more)

### Community 1 - "MienrPlugin"
Cohesion: 0.10
Nodes (21): BotPlugin, BotPluginFactory, InboundMessage, MessageSendOptions, MessageTarget, PluginRuntimeContext, PluginTask, ReportKind (+13 more)

### Community 2 - "AnimeContentService"
Cohesion: 0.11
Nodes (25): Graphics2D, RuntimeException, AnimeContentException, AnimeContentService, AnimeScheduleItem, DatedRequest, decodeAnimeUtf8(), BufferedImage (+17 more)

### Community 3 - "CommandAliases"
Cohesion: 0.10
Nodes (17): CommandName, ANIME, GET_ANIME, GET_NEWS, HELP, NEWS, TIME_ANIME, TIME_NEWS (+9 more)

### Community 4 - "properties"
Cohesion: 0.06
Nodes (33): aliases, anime, getanime, getnews, help, mienr, news, timeanime (+25 more)

### Community 5 - "ContentTestSupport.kt"
Cohesion: 0.12
Nodes (18): Color, AnimeContentServiceTest, await(), bundledTestFont(), completed(), ContentTestAnchor, decodePng(), fixturePng() (+10 more)

### Community 6 - "NewsContentService"
Cohesion: 0.17
Nodes (13): FontMetrics, DatedRequest, decodeUtf8(), ByteArray, DatedRequest, Font, PluginHttpResponse, R (+5 more)

### Community 7 - "MienrPluginTest"
Cohesion: 0.23
Nodes (8): GroupMemberRole, MessageTargetType, PluginTestContext, ByteArray, DailyImageProvider, PluginEvent, MienrPluginTest, pngBytes()

### Community 8 - "AutomaticDispatchLedger"
Cohesion: 0.13
Nodes (8): ByteArray, writeFileAtomically(), AutomaticDispatchLedger, DailyPngCache, hasPngSignature(), isReadablePng(), ByteArray, AutomaticDispatchLedgerTest

### Community 9 - "properties"
Cohesion: 0.16
Nodes (15): properties, pattern, type, maxItems, type, maxLength, minLength, type (+7 more)

### Community 10 - "MIE News Reporter"
Cohesion: 0.20
Nodes (9): License, MIE News Reporter, 功能, 图片与数据源, 在线样例, 安装, 指令, 构建与验证 (+1 more)

### Community 11 - "OnlineImageSmoke"
Cohesion: 0.27
Nodes (5): JdkPluginHttpClient, PluginHttpClient, PluginHttpRequest, PluginHttpResponse, OnlineImageSmoke

### Community 12 - "qqbot-plugin-schema.json"
Cohesion: 0.22
Nodes (8): additionalProperties, maxItems, type, uniqueItems, $defs, commandAliasList, $schema, type

### Community 13 - "news"
Cohesion: 0.22
Nodes (9): additionalProperties, $ref, type, properties, news, timeZone, maxLength, minLength (+1 more)

### Community 14 - "required"
Cohesion: 0.48
Nodes (7): defaultTime, disabledMessage, enabledGroups, failureMessage, groupTimes, required, required

### Community 16 - "items"
Cohesion: 0.47
Nodes (6): items, items, maxLength, minLength, pattern, type

### Community 17 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 18 - "anime"
Cohesion: 0.50
Nodes (4): additionalProperties, $ref, type, anime

### Community 19 - "disabledMessage"
Cohesion: 0.50
Nodes (4): maxLength, minLength, type, disabledMessage

## Knowledge Gaps
- **65 isolated node(s):** `Help`, `NEWS`, `TIME_NEWS`, `GET_NEWS`, `ANIME` (+60 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **1 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MienrPlugin` connect `MienrPlugin` to `MienrPluginTest`?**
  _High betweenness centrality (0.302) - this node is a cross-community bridge._
- **Why does `ReportKind` connect `MienrPlugin` to `AutomaticDispatchLedger`, `MienrConfiguration.kt`, `CommandAliases`?**
  _High betweenness centrality (0.288) - this node is a cross-community bridge._
- **Why does `AnimeContentService` connect `AnimeContentService` to `MienrPlugin`, `OnlineImageSmoke`, `ContentTestSupport.kt`?**
  _High betweenness centrality (0.173) - this node is a cross-community bridge._
- **Are the 6 inferred relationships involving `MienrPlugin` (e.g. with `.`configured aliases execute through the plugin entry point`()` and `.`disabled get commands quote the matching configured reminder`()`) actually correct?**
  _`MienrPlugin` has 6 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `AnimeContentService` (e.g. with `.create()` and `.fetchesTimelineAndCoversThenPersistsOnlyCurrentAnimeImage()`) actually correct?**
  _`AnimeContentService` has 5 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `NewsContentService` (e.g. with `.create()` and `.fetchesRendersAndPersistsOnlyCurrentNewsImage()`) actually correct?**
  _`NewsContentService` has 5 INFERRED edges - model-reasoned connections that need verification._
- **What connects `Help`, `NEWS`, `TIME_NEWS` to the rest of the system?**
  _65 weakly-connected nodes found - possible documentation gaps or missing edges._