# Сборка платформы в IntelliJ IDEA

`pom.xml` объединяет 16 существующих `platform-*` модулей в Maven reactor.
Агрегатор не меняет их parent, версии или зависимости. Приложение собирается
отдельно из корневого `GitVaa25/pom.xml`.

## Первое подключение

1. В IDEA открыть окно Maven и выполнить Reload All Maven Projects. Агрегатор
   добавлен в список Maven projects проекта (`.idea/misc.xml`). Если он ещё
   не появился, выбрать Add Maven Project и `platform-build/pom.xml`.
2. В Settings → Build, Execution, Deployment → Build Tools → Maven оставить
   Local repository по умолчанию, без Override. Для стандартной конфигурации это
   `%USERPROFILE%\.m2\repository`. Проверить также отсутствие собственного
   `localRepository` в пользовательском `settings.xml`.
3. Выбрать JDK 21 для Maven Runner и Importer.
4. Внешние библиотеки предварительно установить в этот же репозиторий из их
   проектов: `crudui` (включая `platform-identity-api` и `platform-crud-api`)
   и используемые модули FilterGrid. Платформенный reactor их не собирает.
5. У проекта **GitVaa platform reactor** открыть Lifecycle → install.
   Maven соберёт модули в порядке реальных зависимостей и установит JAR/POM
   в выбранный local repository. Включены BOM и оба starter-модуля.
6. Перезагрузить Maven-проекты приложения. `platform-rest-web` должен разрешиться
   как `org.ipro:platform-rest-web:1.0-SNAPSHOT`.

Для сборки самого приложения в `.m2` также нужны его внешние report-библиотеки;
полный список и порядок находятся в `scripts/local-dependencies.json`.

## Команды из корня GitVaa25

Maven должен быть доступен в PATH; JAVA_HOME должен указывать на JDK 21.
Команды не задают `maven.repo.local` и используют обычные Maven settings.

```powershell
# Вся платформа, включая tests и установку артефактов.
mvn -f platform-build/pom.xml install

# После первичной установки — REST-web и его зависимости внутри reactor.
mvn -f platform-build/pom.xml -pl :platform-rest-web -am install
```

Запуск install только у отдельного дочернего модуля из IDEA не добавляет его
зависимости в сборку автоматически. Для этого использовать install агрегатора
либо команду с `-pl` и `-am` в Maven Run Configuration.

## Отношение к bootstrap и будущему выделению платформы

Обычная разработка в IDEA использует стандартный `.m2`. Проверка воспроизводимости
по manifest остаётся за `scripts/bootstrap-local-dependencies.ps1` с отдельным
репозиторием `.local-maven-repository` и проверками fingerprints. Агрегатор не
заменяет этот gate и не включён как новая runtime-зависимость или owner в manifest.

При выделении платформы агрегатор можно сделать её корневым POM, скорректировав
пути modules. Прикладной проект продолжит использовать платформенные BOM/starter
и optional add-on зависимости.

Проверка 2026-10-06: общий install в стандартный `.m2` прошёл для всех модулей,
966 tests без failures/errors/skipped. Подробности — в
[evidence](../docs/architecture/evidence/platform-reactor-install-20261006.md).
