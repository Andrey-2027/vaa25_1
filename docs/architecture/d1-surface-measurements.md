# D1: замеры публичной поверхности (числа генерируются)

**Статус:** числа проверяются тестом `PlatformPublicSurfaceTest`; расхождение с кодом роняет сборку. Обновление — `mvn test -Dplatform.measurements.write=true`.

Карта [`d1-platform-boundary-map.md`](d1-platform-boundary-map.md) — исторический снимок на baseline `3793165` / тег `c4.8-d1-baseline`: её числа описывают состояние на тот момент и намеренно не правятся задним числом. Актуальные замеры — ниже.

```text
app-source-files=164
platform-tree-files=217
platform-artifact-files=264
named-platform-types=200
api-types=103
spi-types=24
legacy-internal-types=73
legacy-internal-usage-links=304
```

Расшифровка:

- `app-source-files` — прикладной код (`org.ip`, `src/main`);
- `platform-tree-files` — платформа, оставшаяся в дереве репозитория (`org.ipro`, `src/main`);
- `platform-artifact-files` — исходники вынесенных платформенных артефактов (`platform-*` плюс нейтральные leaf-контракты `platform-identity-api` и `platform-crud-api` соседнего реактора `crudui`);
- `named-platform-types` — типы платформы, которые называет приложение (методика D1 §1: по импортам);
- `api-types` / `spi-types` / `legacy-internal-types` — роли из reviewed-реестра `platform-public-surface.txt`;
- `legacy-internal-usage-links` — общее число зафиксированных ссылок приложения на внутренние типы (измеренный reach-through, бюджет только уменьшается).

Строковые связки платформы на `org.ip` в этом документе не считаются: их реестр пуст и принадлежит `PlatformStringDependencyTest` — там же и критерий.
