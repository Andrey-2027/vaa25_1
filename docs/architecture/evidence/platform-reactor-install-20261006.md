# Maven-агрегатор платформы: проверка 2026-10-06

- Агрегатор: [`platform-build/pom.xml`](../../../platform-build/pom.xml).
- Инструкция IDEA: [`platform-build/README.md`](../../../platform-build/README.md).
- Toolchain: Axiom JDK 21.0.11, IntelliJ bundled Maven 3.9.9.
- Установка: стандартный `C:\Users\strim\.m2\repository`, offline.

## Результаты

Список modules совпал со всеми 16 in-repo platform owners в действующем manifest.
Приложение и внешние sibling-библиотеки не включены в платформенный reactor.
Наследование и зависимости дочерних модулей сохранены.

```text
mvn -o -f platform-build/pom.xml validate
mvn -o -f platform-build/pom.xml install
mvn -o -f platform-build/pom.xml -pl :platform-rest-web -am validate
```

Полный install завершён 2026-10-06 в 12:34:37 +03:00 за 3:18:
17 reactor projects (16 модулей и агрегатор) — SUCCESS.
В сумме **966 tests, failures 0, errors 0, skipped 0**, без skipTests.

`platform-rest-web:1.0-SNAPSHOT` установлен в стандартный `.m2` вместе с остальными
платформенными artifacts. Installed/target JAR SHA-256 совпали:
`e6b27194a4f618e42e431f274134c812fb434c211a696493dc29d674d78583b4`.

Адресный validate с `-pl :platform-rest-web -am` также завершён успешно;
Maven выбрал зависимости REST-web внутри reactor.
Это проверка организации платформенной сборки. Bootstrap, application verify
и проверка воспроизводимости по отдельному repository остаются самостоятельными gates.

Raw logs сохранены в ignored `.local-maven-repository/`:

- `platform-reactor-validate-20261006.log`;
- `platform-reactor-default-install-20261006.log`;
- `platform-reactor-selected-validate-20261006.log`.
