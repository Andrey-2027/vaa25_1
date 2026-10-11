# Клиент REST-чтения v1

Документ описывает опубликованные ресурсы `specifications` и `nomenclature`.
HTTP opt-in в обычной конфигурации выключен. Локальная приёмка 4.7 завершена
([evidence 4.7](rest-read-api-stage4-47-evidence-20261006.md)). Внешнее включение требует общего gate C5/C, согласования consumer
контракта, учётки/grants/TLS и deployment acceptance на выбранной СУБД.

## Включение для локальной проверки в IDEA

Наличие зависимости `platform-rest-web` само по себе REST не включает.
В Run → Edit Configurations у конфигурации запуска приложения добавить
в **Program arguments**:

```text
--platform.rest.read.enabled=true --platform.rest.web.enabled=true
```

Перезапустить приложение. Первый флаг включает read pipeline и регистрацию
REST/attribute прав в «Администрирование → Доступ (RLS)»; второй включает HTTP.
При выключенном `platform.rest.read.enabled` ключи `REST:nomenclature:v1:LIST`
и `REST:nomenclature:v1:DETAIL` в списке не появляются.
Эти параметры предназначены для локальной проверки; условия внешнего включения
перечислены выше.

## Запросы

Basic credentials передаются в каждом запросе. Сессионный cookie не является
учётными данными REST. В deployment используется HTTPS и отдельно созданная
учётка потребителя. Ниже placeholders, а не выдаваемые платформой credentials.

```sh
curl --user '<username>:<password>' --get 'https://<host>/api/v1/specifications' \
  --data-urlencode 'journalId=1001' \
  --data-urlencode 'fields=id,codeSpec,draft,nomenclatureCode' \
  --data-urlencode 'sort=codeSpec,asc' --data-urlencode 'page=0' --data-urlencode 'size=50'

curl --user '<username>:<password>' --get 'https://<host>/api/v1/specifications/2001' \
  --data-urlencode 'fields=id,codeSpec,draft,comment,journalId,nomenclatureCode'

curl --user '<username>:<password>' --get 'https://<host>/api/v1/nomenclature' \
  --data-urlencode 'typeNom=material' --data-urlencode 'fields=id,code,name,typeNom'

curl --user '<username>:<password>' 'https://<host>/api/v1/nomenclature/3001'
```

`fields` выбирает подмножество объявленных aliases; `id` добавляется всегда.
Без `fields` возвращается default projection. Пустое значение, повтор alias,
неизвестное поле или JPA path отклоняются. LIST/DETAIL имеют собственные наборы.
Связи выдаются скалярными aliases, например `nomenclatureCode`, а не объектами JPA.
Допустимые поля, фильтры и сортировки: [OpenAPI v1](contracts/rest-read/openapi-v1.json).

Страница нумеруется с нуля. Размер по умолчанию 50, максимум 200; `size=0` означает
размер по умолчанию. Конечное окно задаётся сервером: `page * size + size <= W`.
`totalElements`/`totalPages` описывают все доступные субъекту строки, включая строки
за этим окном; они не обещают доступность любой вычисленной страницы.
Значение W в пилотах равно 200 и не является общим deployment default.

LIST envelope: `content`, `page`, `size`, `totalElements`, `totalPages`.
DETAIL envelope: `data`. Nullable поля присутствуют с JSON `null`.
`int64` передаётся JSON number; клиент должен разбирать его без потери целочисленной
точности (HTTP pilot проверяет `9007199254740993`). Для decimal контракт также
предусматривает точный JSON number; текущие два ресурса decimal поля не публикуют.

## Права

### Создание учётки для локальной проверки

Формы пользователей и ролей доступны по `/lists/user` и `/lists/role`.
Для администратора, который заводит учётку, через «Администрирование → Доступ (RLS)»
назначить явные C5 read grants `ENTITY:org.ip.model.User` и
`ENTITY:org.ip.model.Role` (субъект USER или назначенная администратору роль).
Наличие `ROLE_ADMIN` само эти C5 grants не заменяет. Нельзя трактовать отказ чтения
как отсутствие пользователей в БД.

В форме пользователя задать логин, пароль и «Включён». Роль необязательна, если
REST grants назначаются напрямую пользователю; доступ к `Role` всё равно нужен
администратору для открытия формы с выбором ролей. Пароль хэшируется `UserService`,
а пустой пароль при редактировании сохраняет существующий хэш.
Эти административные права не нужно выдавать REST-потребителю.

### Права REST-потребителя

Актуальная модель: [ADR-0016](decisions/ADR-0016-rest-resource-field-access.md).
Для списка номенклатуры назначить чтение `REST:nomenclature:v1:LIST` и
`ENTITY:org.ip.model.Nomenclature`; для карточки — также независимый
`REST:nomenclature:v1:DETAIL`. Права на поля по отдельности не назначаются.

Администратор назначает права фактической учётке либо назначенной ей роли:

- API: `REST:<resource>:v1:LIST` и/или `REST:<resource>:v1:DETAIL` независимо.
- Entity read опубликованного типа. Отдельные права на поля не требуются:
  операция разрешает все явно опубликованные поля, фильтры и сортировки.
- Для используемых reference aliases/фильтров — чтение целевой сущности и её
  применимые ограничения RLS согласно декларации ресурса.
- Для спецификаций — разрешённые JOURNAL. Номенклатура не фильтруется по JOURNAL,
  но API/entity права обязательны.

Регистрация ресурса и каталога ключей прав сама grants не выдаёт. Неназначенная
роль не даёт доступа. Отзыв grant или назначения роли действует в следующем
HTTP-запросе. Не запрошенная связь в фиксированном fetch graph не требует
дополнительного права и не попадает в ответ.

## Исходы

| HTTP | Значение |
| --- | --- |
| 200 | LIST/DETAIL; отсутствие строк в LIST — успешный пустой список |
| 400 | Недопустимый запрос, поле, id, фильтр, сортировка или окно |
| 401 | Credentials отсутствуют/неверны или учётка отключена; Basic challenge |
| 403 | Недостаточно API/C5 прав, в том числе при malformed input |
| 404 | Неизвестный ресурс/major либо отсутствующая/скрытая DETAIL строка |
| 405 | Метод отличен от GET; `Allow: GET` |
| 406 | Accept не допускает JSON |
| 500/503 | Ошибка исполнения/недоступность зависимости; не пустой успешный ответ |

Ошибки имеют `application/problem+json`, `code`, `status`, `traceId` и
`Cache-Control: no-store`. `traceId` передаётся сопровождающему для поиска в
серверном журнале. HEAD сохраняет статус/headers, HTTP-сервер подавляет body.
Скрытый и отсутствующий id имеют одинаковый 404 без раскрытия причины.

Форматы и examples: [reviewed snapshots](contracts/rest-read/README.md).
Стоимость и покрытие настоящего транспорта: [evidence 4.6](rest-read-api-stage4-46-evidence-20261005.md).
